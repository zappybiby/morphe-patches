/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.ArrayDeque

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val MEDIA_DESCRIPTION_CLASS =
    "Landroid/support/v4/media/MediaDescriptionCompat;"
private const val ITERATOR_CLASS = "Ljava/util/Iterator;"
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595
private const val PLAYLIST_THUMBNAIL_EXTENSION_FIELD_NUMBER = 164_480_666
private const val PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER = 52_666_186
private const val HOME_BROWSE_ID_MARKER = "FEmusic_home"
private const val EIGHT_BIT_REGISTER_LIMIT = 256
private const val RUNTIME_SCHEMA_VERSION = 2
private const val RUNTIME_SCHEMA_DELIMITER = "|"
private const val RUNTIME_METHOD_DELIMITER = "#"
private const val RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "c"
private const val RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "g"
private const val RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "h"

private val Method.instructionList
    get() = implementation?.instructions?.toList().orEmpty()

private inline fun <reified T : Reference> Method.references() =
    instructionList.asSequence().mapNotNull { it.getReference<T>() }

private fun Method.hasSignature(result: String, vararg parameters: String) =
    returnType == result && parameterTypes.map(CharSequence::toString) == parameters.toList()

private fun <T> Sequence<T>.singleOrError(message: String) =
    distinct().toList().let { it.singleOrNull() ?: error("$message: ${it.joinToString()}") }

private fun String.toRuntimeClassName() = when {
    startsWith("[") -> replace('/', '.')
    startsWith("L") && endsWith(";") -> substring(1, lastIndex).replace('/', '.')
    else -> this
}

private data class RuntimeMethod(
    val ownerClassName: String,
    val name: String,
    val descriptor: String,
) {
    fun encode(): String {
        val values = listOf(ownerClassName, name, descriptor)
        require(values.none { value -> RUNTIME_METHOD_DELIMITER in value })
        return values.joinToString(RUNTIME_METHOD_DELIMITER)
    }
}

private fun MethodReference.toRuntimeMethod() = RuntimeMethod(
    ownerClassName = definingClass.toRuntimeClassName(),
    name = name,
    descriptor = parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") +
        returnType,
)

private data class RendererEndpoints(
    val fields: List<FieldReference>,
    val mediaIdMethod: Method,
)

private data class ExtensionMapAccessors(
    val field: FieldReference,
    val iteratorMethod: Method,
)

/**
 * Names, methods, and fields the Java extension needs from the installed APK.
 * Keep the encoded order in sync with the Java RuntimeSchema.
 */
private data class RuntimeSchema(
    val responsiveRendererClassName: String,
    val playlistEndpointClassName: String,
    val responsiveRendererEndpointFieldNames: List<String>,
    val endpointMediaIdMethod: RuntimeMethod,
    val browseEndpointClassName: String,
    val browseIdFieldName: String,
    val browseIdSetterMethod: RuntimeMethod,
    val loadResultMediaIdFieldPath: List<String>,
    val browseBuilderFactoryMethod: RuntimeMethod,
    val browseRequestMethod: RuntimeMethod,
    val clientDataSetterMethod: RuntimeMethod,
    val resultDeliveryMethod: RuntimeMethod,
    val responsiveRendererArtworkFieldName: String,
    val playlistThumbnailRendererClassName: String,
    val responsiveRendererTitleFieldName: String,
    val responsiveRendererSubtitleFieldName: String,
    val extensionMapClassName: String,
    val extensionMapFieldName: String,
    val extensionMapIteratorMethod: RuntimeMethod,
) {
    fun encode(): String {
        require(responsiveRendererEndpointFieldNames.size == 2)
        require(loadResultMediaIdFieldPath.isNotEmpty())

        val values = listOf(
            RUNTIME_SCHEMA_VERSION.toString(),
            responsiveRendererClassName,
            playlistEndpointClassName,
            responsiveRendererEndpointFieldNames.joinToString(","),
            endpointMediaIdMethod.encode(),
            browseEndpointClassName,
            browseIdFieldName,
            browseIdSetterMethod.encode(),
            loadResultMediaIdFieldPath.joinToString(","),
            browseBuilderFactoryMethod.encode(),
            browseRequestMethod.encode(),
            clientDataSetterMethod.encode(),
            resultDeliveryMethod.encode(),
            responsiveRendererArtworkFieldName,
            playlistThumbnailRendererClassName,
            responsiveRendererTitleFieldName,
            responsiveRendererSubtitleFieldName,
            extensionMapClassName,
            extensionMapFieldName,
            extensionMapIteratorMethod.encode(),
        )
        require(values.none { value -> RUNTIME_SCHEMA_DELIMITER in value })
        return values.joinToString(RUNTIME_SCHEMA_DELIMITER)
    }
}

private fun findStringPaths(
    startType: String,
    fields: List<FieldReference>,
): List<List<FieldReference>> {
    val fieldsByOwner = fields.groupBy { it.definingClass }
    val pending = ArrayDeque<List<FieldReference>>()
    pending.add(emptyList())
    val results = mutableListOf<List<FieldReference>>()
    var matchDepth: Int? = null

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (matchDepth?.let { current.size >= it } == true) continue
        fieldsByOwner[current.lastOrNull()?.type ?: startType].orEmpty().forEach { field ->
            val path = current + field
            if (field.type == "Ljava/lang/String;") {
                results += path
                matchDepth = path.size
            } else if (matchDepth == null && field.type != startType &&
                current.none { it.type == field.type }
            ) {
                pending.addLast(path)
            }
        }
    }
    return results.distinct()
}

@Suppress("unused")
val restoreAndroidAutoPlaylistsPatch = bytecodePatch(
    name = "Restore Playlists in Android Auto",
    description = "Restores YouTube Music playlists as directly playable items in Android Auto.",
) {
    extendWith("extensions/android-auto-playlists.mpe")
    dependsOn(
        standaloneExtensionPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val classesByType = linkedMapOf<String, ClassDef>()
        classDefForEach { classDef -> classesByType[classDef.type] = classDef }
        val allMethods = classesByType.values.asSequence().flatMap { it.methods.asSequence() }

        // YTM 9.15/9.29/9.30/9.31: the method that directly references the Playlists title
        // builds offline Library items, while Android Auto reaches this shared constructor.
        // Capture the ID here and let the extension compare YTM's localized Playlists title.
        val mediaDescriptionConstructorParameters = listOf(
            "Ljava/lang/String;",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Landroid/graphics/Bitmap;",
            "Landroid/net/Uri;",
            "Landroid/os/Bundle;",
            "Landroid/net/Uri;",
        )
        val mediaDescriptionConstructor =
            mutableClassDefBy(MEDIA_DESCRIPTION_CLASS).methods.single { method ->
                method.name == "<init>" &&
                    method.parameterTypes.map(CharSequence::toString) ==
                    mediaDescriptionConstructorParameters
            }
        val mediaDescriptionConstructorReturnIndex =
            mediaDescriptionConstructor.indexOfFirstInstructionOrThrow {
                opcode == Opcode.RETURN_VOID
            }

        // YTM 9.15 stores parsers on generated messages; 9.29+ gets them from the protobuf runtime.
        // Extension numbers stay fixed, so resolve each message from the initializer containing its number.
        fun resolveExtensionMessageType(extensionFieldNumber: Int): String {
            return allMethods
                .filter { method ->
                    method.name == "<clinit>" &&
                    method.instructionList.any { instruction ->
                        (instruction as? NarrowLiteralInstruction)?.narrowLiteral ==
                            extensionFieldNumber
                    }
                }
                .flatMap { method ->
                    method.instructionList.asSequence()
                        .filter { instruction -> instruction.opcode == Opcode.CONST_CLASS }
                        .mapNotNull { instruction ->
                            instruction.getReference<TypeReference>()?.type
                        }
                }
                .singleOrError(
                    "Could not uniquely resolve protobuf extension $extensionFieldNumber",
                )
        }

        val responsiveRendererType = resolveExtensionMessageType(
            MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER,
        )
        val playlistThumbnailRendererType = resolveExtensionMessageType(
            PLAYLIST_THUMBNAIL_EXTENSION_FIELD_NUMBER,
        )
        val playlistEndpointType = resolveExtensionMessageType(
            PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER,
        )
        val responsiveRendererFields = classesByType[responsiveRendererType]!!.fields.toList()

        fun responsiveRendererField(name: String) = responsiveRendererFields.asSequence()
            .filter { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
            }
            .singleOrError("Could not resolve responsive renderer field $name")

        // YTM 9.15/9.29/9.30/9.31: artwork, title, and subtitle use fields c, g, and h.
        // Sanity-check their relative types before passing these generated field names to runtime.
        val rendererArtworkField = responsiveRendererField(
            RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME,
        )
        val rendererTitleField = responsiveRendererField(
            RESPONSIVE_RENDERER_TITLE_FIELD_NAME,
        )
        val rendererSubtitleField = responsiveRendererField(
            RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME,
        )
        check(rendererArtworkField.type != rendererTitleField.type &&
            rendererTitleField.type == rendererSubtitleField.type
        ) {
            "Unexpected responsive renderer artwork, title, or subtitle fields"
        }

        // YTM 9.15/9.29/9.30/9.31: two same-typed renderer fields hold the Browse and
        // playback endpoints. Their media-ID helper writes that endpoint type into MediaItemInfo.
        val rendererEndpoints = responsiveRendererFields.asSequence()
            .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
            .groupBy { field -> field.type }
            .values.asSequence()
            .filter { fields -> fields.size == 2 }
            .mapNotNull { fields ->
                val endpointType = fields.first().type
                val mediaIdMethods = allMethods
                    .filter { method ->
                        AccessFlags.STATIC.isSet(method.accessFlags) &&
                            method.hasSignature("Ljava/lang/String;", endpointType) &&
                            method.instructionList.any { instruction ->
                                instruction.opcode == Opcode.IPUT_OBJECT &&
                                    instruction.getReference<FieldReference>()?.type == endpointType
                            }
                    }
                    .distinct()
                    .toList()
                mediaIdMethods.singleOrNull()?.let { mediaIdMethod ->
                    RendererEndpoints(fields.sortedBy { field -> field.name }, mediaIdMethod)
                }
            }
            .singleOrError(
                "Could not uniquely resolve the responsive renderer endpoints",
            )
        val endpointMediaIdMethod = rendererEndpoints.mediaIdMethod

        // YTM 9.15/9.29/9.30/9.31: endpoint messages inherit one extension-map field, and
        // that map type has one zero-argument Iterator method. Resolve both here instead of
        // searching generated protobuf classes at runtime.
        val endpointContainerType = rendererEndpoints.fields.first().type
        val extensionMapAccessors = generateSequence(
            classesByType[endpointContainerType]?.superclass,
        ) { type -> classesByType[type]?.superclass }
            .flatMap { type -> classesByType[type]?.fields?.asSequence() ?: emptySequence() }
            .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
            .mapNotNull { field ->
                val iteratorMethods = classesByType[field.type]?.methods?.filter { method ->
                    !AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.parameterTypes.isEmpty() && method.returnType == ITERATOR_CLASS
                }.orEmpty()
                iteratorMethods.singleOrNull()?.let { method ->
                    ExtensionMapAccessors(field, method)
                }
            }
            .singleOrError("Could not uniquely resolve the protobuf extension map")

        // YTM 9.15/9.29/9.30/9.31: Browse class and method names change, but their signatures remain.
        // Resolve the factory, builder, and request methods from that shared structure.
        val browseRequestBuilderMethod = BrowseRequestBuilderFingerprint.method
        val browseRequestBuilderInstructions = browseRequestBuilderMethod.instructionList
        val browseBuilderType = browseRequestBuilderMethod.returnType
        val browseBuilderFactoryMethod = browseRequestBuilderMethod.references<MethodReference>()
            .filter { reference ->
                reference.parameterTypes.isEmpty() &&
                    reference.returnType == browseBuilderType
            }
            .singleOrError("Could not uniquely resolve the Browse service factory")
        val browseServiceType = browseBuilderFactoryMethod.definingClass
        val browseRequestMethod = classesByType[browseServiceType]!!.methods.asSequence()
            .filter { method ->
                method.hasSignature(
                    "Lcom/google/common/util/concurrent/ListenableFuture;",
                    browseBuilderType,
                    "Ljava/util/concurrent/Executor;",
                )
            }
            .filter { method ->
                method.instructionList.any { instruction ->
                    instruction.getReference<FieldReference>()?.let { field ->
                        field.definingClass == browseBuilderType &&
                            field.type == "Ljava/lang/String;"
                    } == true
                }
            }
            .singleOrError("Could not uniquely resolve the authenticated Browse request method")
        val builderClasses = generateSequence(classesByType[browseBuilderType]) { classDef ->
            classesByType[classDef.superclass]
        }.toList()
        val builderMethods = builderClasses.flatMap { it.methods }
        val browseClientDataSetterMethod = builderMethods.asSequence()
            .filter { it.hasSignature("V", "[B") }
            .singleOrError("Could not uniquely resolve the authenticated Browse client-data setter")
        val browseRequestIdField = browseRequestMethod.references<FieldReference>()
            .filter { field ->
                field.definingClass == browseBuilderType && field.type == "Ljava/lang/String;"
            }
            .singleOrError("Could not uniquely resolve the Browse request ID field")
        val browseIdSetterMethod = builderMethods.asSequence().filter { method ->
            method.hasSignature("V", "Ljava/lang/String;") &&
                method.instructionList.any { instruction ->
                    instruction.opcode == Opcode.IPUT_OBJECT &&
                            instruction.getReference<FieldReference>() == browseRequestIdField
                }
        }
            .singleOrError("Could not uniquely resolve the Browse ID setter")

        // YTM 9.15/9.29/9.30/9.31: the request builder reads the Browse ID before FEmusic_home.
        // Use that field read instead of relying on its generated name.
        val browseEndpointIdField = browseRequestBuilderInstructions.indices.asSequence()
            .mapNotNull { index ->
                if (browseRequestBuilderInstructions[index].getReference<StringReference>()?.string !=
                    HOME_BROWSE_ID_MARKER
                ) return@mapNotNull null
                browseRequestBuilderInstructions.getOrNull(index - 1)
                    ?.takeIf { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                    ?.getReference<FieldReference>()
                    ?.takeIf { field ->
                        field.type == "Ljava/lang/String;" && classesByType.containsKey(field.definingClass)
                    }
            }
            .singleOrError("Could not uniquely resolve the Browse endpoint ID field")

        val mediaIdValidationMethod = AndroidAutoMediaIdValidationFingerprint.method
        val androidAutoControllerType = mediaIdValidationMethod.definingClass
        val loadResultFieldReferences = mediaIdValidationMethod
            .references<FieldReference>().distinct().toList()

        val loadResultType = mediaIdValidationMethod.parameterTypes.first().toString()
        // YTM 9.15 stores this media ID at i.k, 9.29 at j.k, and 9.30/9.31 at j.l.
        // Derive the path from YTM's validation method instead of maintaining a version table.
        val mediaIdFieldPath = findStringPaths(
            loadResultType,
            loadResultFieldReferences,
        ).asSequence()
            .singleOrError("Could not uniquely resolve the Android Auto media ID field path")
        // YTM 9.15/9.29: validation calls a wrapper that forwards (items, null).
        // YTM 9.30/9.31: the wrapper is removed and validation calls the two-argument method directly.
        val resultDeliveryMethod = mediaIdValidationMethod.references<MethodReference>()
            .filter { reference ->
                val parameters = reference.parameterTypes.map(CharSequence::toString)
                reference.definingClass == loadResultType && reference.returnType == "V" &&
                    parameters.size in 1..2 &&
                    parameters.firstOrNull() == "Ljava/util/List;" &&
                    parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
            }
            .singleOrError("Could not uniquely resolve the Android Auto result delivery method")

        val encodedRuntimeSchema = RuntimeSchema(
            responsiveRendererClassName = responsiveRendererType.toRuntimeClassName(),
            playlistEndpointClassName = playlistEndpointType.toRuntimeClassName(),
            responsiveRendererEndpointFieldNames = rendererEndpoints.fields.map { it.name },
            endpointMediaIdMethod = endpointMediaIdMethod.toRuntimeMethod(),
            browseEndpointClassName = browseEndpointIdField.definingClass.toRuntimeClassName(),
            browseIdFieldName = browseEndpointIdField.name,
            browseIdSetterMethod = browseIdSetterMethod.toRuntimeMethod(),
            loadResultMediaIdFieldPath = mediaIdFieldPath.map { it.name },
            browseBuilderFactoryMethod = browseBuilderFactoryMethod.toRuntimeMethod(),
            browseRequestMethod = browseRequestMethod.toRuntimeMethod(),
            clientDataSetterMethod = browseClientDataSetterMethod.toRuntimeMethod(),
            resultDeliveryMethod = resultDeliveryMethod.toRuntimeMethod(),
            responsiveRendererArtworkFieldName = rendererArtworkField.name,
            playlistThumbnailRendererClassName =
                playlistThumbnailRendererType.toRuntimeClassName(),
            responsiveRendererTitleFieldName = rendererTitleField.name,
            responsiveRendererSubtitleFieldName = rendererSubtitleField.name,
            extensionMapClassName =
                extensionMapAccessors.field.definingClass.toRuntimeClassName(),
            extensionMapFieldName = extensionMapAccessors.field.name,
            extensionMapIteratorMethod = extensionMapAccessors.iteratorMethod.toRuntimeMethod(),
        ).encode()

        val mutableBrowseBuilderFactoryMethod = mutableClassDefBy(browseServiceType).methods
            .single { method ->
                method.name == browseBuilderFactoryMethod.name &&
                    method.returnType == browseBuilderFactoryMethod.returnType &&
                    method.parameterTypes == browseBuilderFactoryMethod.parameterTypes
            }
        val schemaRegister = mutableBrowseBuilderFactoryMethod.findFreeRegister(0)
        check(schemaRegister < EIGHT_BIT_REGISTER_LIMIT) {
            "Browse builder factory has no free 8-bit register"
        }
        // YTM 9.15/9.29/9.30/9.31: Android Auto calls this factory before it asks for Library.
        // Save the Browse service here so playlists work even when YTM was not already open.
        mutableBrowseBuilderFactoryMethod.addInstructions(
            0,
            """
                const-string v$schemaRegister, "$encodedRuntimeSchema"
                invoke-static/range { v$schemaRegister .. v$schemaRegister }, $EXTENSION_CLASS->configure(Ljava/lang/String;)V
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Ljava/lang/Object;)V
            """,
        )

        val androidAutoLoadChildrenMethod = mutableClassDefBy(androidAutoControllerType).methods
            .single { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                    AccessFlags.FINAL.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(loadResultType)
            }
        val playlistHandledRegister = androidAutoLoadChildrenMethod.findFreeRegister(0)
        check(playlistHandledRegister < EIGHT_BIT_REGISTER_LIMIT) {
            "Android Auto load-children method has no free 8-bit register"
        }
        // A true result claims this exact Playlists request. The extension delivers the result
        // asynchronously, so YTM's original loader must not also run.
        androidAutoLoadChildrenMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handlePlaylistsNode(Ljava/lang/Object;)Z
                move-result v$playlistHandledRegister
                if-eqz v$playlistHandledRegister, :resume
                return-void
            """,
            ExternalLabel("resume", androidAutoLoadChildrenMethod.getInstruction<Instruction>(0)),
        )

        mediaDescriptionConstructor.addInstructions(
            mediaDescriptionConstructorReturnIndex,
            """
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->rememberNativePlaylistsMediaId(Ljava/lang/String;Ljava/lang/CharSequence;)V
            """,
        )
    }
}
