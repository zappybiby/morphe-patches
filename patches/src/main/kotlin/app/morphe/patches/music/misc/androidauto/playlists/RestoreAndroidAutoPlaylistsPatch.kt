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
import app.morphe.patcher.patch.BytecodePatchContext
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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
private const val OPTIONAL_CLASS = "Lj$/util/Optional;"
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

private val MEDIA_DESCRIPTION_CONSTRUCTOR_PARAMETERS = listOf(
    "Ljava/lang/String;",
    "Ljava/lang/CharSequence;",
    "Ljava/lang/CharSequence;",
    "Ljava/lang/CharSequence;",
    "Landroid/graphics/Bitmap;",
    "Landroid/net/Uri;",
    "Landroid/os/Bundle;",
    "Landroid/net/Uri;",
)

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

private data class ResolvedRendererEndpoints(
    val fields: List<FieldReference>,
    val mediaIdMethod: Method,
)

private data class ResolvedExtensionMapAccessors(
    val field: FieldReference,
    val iteratorMethod: Method,
)

private data class RendererResolution(
    val responsiveRendererType: String,
    val playlistThumbnailRendererType: String,
    val playlistEndpointType: String,
    val endpoints: ResolvedRendererEndpoints,
    val artworkField: FieldReference,
    val titleField: FieldReference,
    val subtitleField: FieldReference,
    val extensionMapAccessors: ResolvedExtensionMapAccessors,
)

private data class BrowseResolution(
    val builderFactoryMethod: MethodReference,
    val requestMethod: Method,
    val clientDataSetterMethod: Method,
    val idSetterMethod: Method,
    val endpointIdField: FieldReference,
)

private data class DeliveryResolution(
    val controllerType: String,
    val loadResultType: String,
    val mediaIdFieldPath: List<FieldReference>,
    val resultDeliveryMethod: MethodReference,
)

private data class ResolvedRuntimeHooks(
    val encodedSchema: String,
    val browseBuilderFactoryMethod: MethodReference,
    val androidAutoControllerType: String,
    val loadResultType: String,
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

private fun findShortestReferencedStringPaths(
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

        injectNativePlaylistsNodeCapture(allMethods)

        val runtimeHooks = resolveRuntimeHooks(classesByType, allMethods)
        injectRuntimeHooks(runtimeHooks)
    }
}

private fun Method.matches(reference: MethodReference) =
    name == reference.name &&
        returnType == reference.returnType &&
        parameterTypes == reference.parameterTypes

private fun MethodReference.isMediaDescriptionConstructor() =
    definingClass == MEDIA_DESCRIPTION_CLASS &&
        name == "<init>" && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == MEDIA_DESCRIPTION_CONSTRUCTOR_PARAMETERS

private fun BytecodePatchContext.injectNativePlaylistsNodeCapture(allMethods: Sequence<Method>) {
    val androidAutoMediaItemMapper = allMethods
        .filter { method ->
            method.returnType == OPTIONAL_CLASS &&
                method.parameterTypes.map(CharSequence::toString).let { parameters ->
                    parameters.size == 3 && parameters[1] == "Ljava/util/Set;"
                } &&
                method.references<MethodReference>()
                    .count { reference -> reference.isMediaDescriptionConstructor() } == 3
        }
        .singleOrError("Could not resolve Android Auto media-item mapper")
    val mutableAndroidAutoMediaItemMapper =
        mutableClassDefBy(androidAutoMediaItemMapper.definingClass).methods.single { method ->
            method.matches(androidAutoMediaItemMapper)
        }
    val descriptionConstructorIndexes =
        mutableAndroidAutoMediaItemMapper.instructionList.mapIndexedNotNull { index, instruction ->
            instruction.getReference<MethodReference>()
                ?.takeIf { reference -> reference.isMediaDescriptionConstructor() }
                ?.let { index }
        }
    check(descriptionConstructorIndexes.size == 3) {
        "Android Auto media-item mapper does not have three description constructors"
    }

    val browsableConstructorIndex = descriptionConstructorIndexes.last()
    val browsableConstructor =
        mutableAndroidAutoMediaItemMapper.getInstruction<RegisterRangeInstruction>(
            browsableConstructorIndex,
        )
    val nativeNodeMediaIdRegister = browsableConstructor.startRegister + 1
    val nativeNodeTitleRegister = browsableConstructor.startRegister + 2

    // YTM 9.15/9.29/9.30/9.31: browsable Android Auto items use the mapper's final
    // description-constructor path. Check the localized title only on that path.
    mutableAndroidAutoMediaItemMapper.addInstructions(
        browsableConstructorIndex,
        """
            invoke-static/range { v$nativeNodeMediaIdRegister .. v$nativeNodeTitleRegister }, $EXTENSION_CLASS->rememberNativePlaylistsMediaId(Ljava/lang/String;Ljava/lang/CharSequence;)V
        """,
    )
}

private fun BytecodePatchContext.resolveRuntimeHooks(
    classesByType: Map<String, ClassDef>,
    allMethods: Sequence<Method>,
): ResolvedRuntimeHooks {
    val renderer = resolveRenderer(classesByType, allMethods)
    val browse = resolveBrowse(classesByType)
    val delivery = resolveDelivery()

    val encodedSchema = RuntimeSchema(
        responsiveRendererClassName = renderer.responsiveRendererType.toRuntimeClassName(),
        playlistEndpointClassName = renderer.playlistEndpointType.toRuntimeClassName(),
        responsiveRendererEndpointFieldNames = renderer.endpoints.fields.map { it.name },
        endpointMediaIdMethod = renderer.endpoints.mediaIdMethod.toRuntimeMethod(),
        browseEndpointClassName = browse.endpointIdField.definingClass.toRuntimeClassName(),
        browseIdFieldName = browse.endpointIdField.name,
        browseIdSetterMethod = browse.idSetterMethod.toRuntimeMethod(),
        loadResultMediaIdFieldPath = delivery.mediaIdFieldPath.map { it.name },
        browseBuilderFactoryMethod = browse.builderFactoryMethod.toRuntimeMethod(),
        browseRequestMethod = browse.requestMethod.toRuntimeMethod(),
        clientDataSetterMethod = browse.clientDataSetterMethod.toRuntimeMethod(),
        resultDeliveryMethod = delivery.resultDeliveryMethod.toRuntimeMethod(),
        responsiveRendererArtworkFieldName = renderer.artworkField.name,
        playlistThumbnailRendererClassName =
            renderer.playlistThumbnailRendererType.toRuntimeClassName(),
        responsiveRendererTitleFieldName = renderer.titleField.name,
        responsiveRendererSubtitleFieldName = renderer.subtitleField.name,
        extensionMapClassName =
            renderer.extensionMapAccessors.field.definingClass.toRuntimeClassName(),
        extensionMapFieldName = renderer.extensionMapAccessors.field.name,
        extensionMapIteratorMethod =
            renderer.extensionMapAccessors.iteratorMethod.toRuntimeMethod(),
    ).encode()

    return ResolvedRuntimeHooks(
        encodedSchema = encodedSchema,
        browseBuilderFactoryMethod = browse.builderFactoryMethod,
        androidAutoControllerType = delivery.controllerType,
        loadResultType = delivery.loadResultType,
    )
}

private fun resolveRenderer(
    classesByType: Map<String, ClassDef>,
    allMethods: Sequence<Method>,
): RendererResolution {
    val responsiveRendererType = resolveExtensionMessageType(
        allMethods,
        MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER,
    )
    val playlistThumbnailRendererType = resolveExtensionMessageType(
        allMethods,
        PLAYLIST_THUMBNAIL_EXTENSION_FIELD_NUMBER,
    )
    val playlistEndpointType = resolveExtensionMessageType(
        allMethods,
        PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER,
    )
    val responsiveRendererFields = classesByType[responsiveRendererType]
        ?.fields
        ?.toList()
        ?: error("Could not resolve responsive renderer class $responsiveRendererType")

    fun responsiveRendererField(name: String) = responsiveRendererFields.asSequence()
        .filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }
        .singleOrError("Could not resolve responsive renderer field $name")

    // YTM 9.15/9.29/9.30/9.31: artwork field c has a different type; title g and
    // subtitle h share the text type. Check that relationship before using these names.
    val artworkField = responsiveRendererField(RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME)
    val titleField = responsiveRendererField(RESPONSIVE_RENDERER_TITLE_FIELD_NAME)
    val subtitleField = responsiveRendererField(RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME)
    check(artworkField.type != titleField.type && titleField.type == subtitleField.type) {
        "Unexpected responsive renderer artwork, title, or subtitle fields"
    }

    // YTM 9.15/9.29/9.30/9.31: Browse and playback are the only same-typed pair of
    // renderer endpoint fields. The media-ID method accepts that type and stores it in MediaItemInfo.
    val endpoints = responsiveRendererFields.asSequence()
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
                ResolvedRendererEndpoints(
                    fields.sortedBy { field -> field.name },
                    mediaIdMethod,
                )
            }
        }
        .singleOrError("Could not uniquely resolve the responsive renderer endpoints")

    // YTM 9.15/9.29/9.30/9.31: endpoint messages inherit one extension-map field, and
    // that map type has one zero-argument Iterator method. Resolve both here instead of
    // searching generated protobuf classes at runtime.
    val endpointContainerType = endpoints.fields.first().type
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
                ResolvedExtensionMapAccessors(field, method)
            }
        }
        .singleOrError("Could not uniquely resolve the protobuf extension map")

    return RendererResolution(
        responsiveRendererType = responsiveRendererType,
        playlistThumbnailRendererType = playlistThumbnailRendererType,
        playlistEndpointType = playlistEndpointType,
        endpoints = endpoints,
        artworkField = artworkField,
        titleField = titleField,
        subtitleField = subtitleField,
        extensionMapAccessors = extensionMapAccessors,
    )
}

private fun BytecodePatchContext.resolveBrowse(
    classesByType: Map<String, ClassDef>,
): BrowseResolution {
    // YTM 9.15/9.29/9.30/9.31: Browse class and method names change, but their signatures remain.
    // Resolve the factory, builder, and request methods from that shared structure.
    val requestBuilderMethod = BrowseRequestBuilderFingerprint.method
    val requestBuilderInstructions = requestBuilderMethod.instructionList
    val builderType = requestBuilderMethod.returnType
    val builderFactoryMethod = requestBuilderMethod.references<MethodReference>()
        .filter { reference ->
            reference.parameterTypes.isEmpty() && reference.returnType == builderType
        }
        .singleOrError("Could not uniquely resolve the Browse service factory")
    val serviceType = builderFactoryMethod.definingClass
    val requestMethod = classesByType[serviceType]
        ?.methods
        ?.asSequence()
        ?.filter { method ->
            method.hasSignature(
                "Lcom/google/common/util/concurrent/ListenableFuture;",
                builderType,
                "Ljava/util/concurrent/Executor;",
            )
        }
        ?.filter { method ->
            method.instructionList.any { instruction ->
                instruction.getReference<FieldReference>()?.let { field ->
                    field.definingClass == builderType &&
                        field.type == "Ljava/lang/String;"
                } == true
            }
        }
        ?.singleOrError("Could not uniquely resolve the authenticated Browse request method")
        ?: error("Could not resolve Browse service class $serviceType")

    val builderClasses = generateSequence(classesByType[builderType]) { classDef ->
        classesByType[classDef.superclass]
    }.toList()
    val builderMethods = builderClasses.flatMap { it.methods }
    val clientDataSetterMethod = builderMethods.asSequence()
        .filter { it.hasSignature("V", "[B") }
        .singleOrError("Could not uniquely resolve the authenticated Browse client-data setter")
    val browseBuilderIdField = requestMethod.references<FieldReference>()
        .filter { field ->
            field.definingClass == builderType && field.type == "Ljava/lang/String;"
        }
        .singleOrError("Could not uniquely resolve the Browse builder ID field")
    val idSetterMethod = builderMethods.asSequence()
        .filter { method ->
            method.hasSignature("V", "Ljava/lang/String;") &&
                method.instructionList.any { instruction ->
                    instruction.opcode == Opcode.IPUT_OBJECT &&
                        instruction.getReference<FieldReference>() == browseBuilderIdField
                }
        }
        .singleOrError("Could not uniquely resolve the Browse ID setter")

    // YTM 9.15/9.29/9.30/9.31: the request builder reads the Browse ID before FEmusic_home.
    // Use that field read instead of relying on its generated name.
    val endpointIdField = requestBuilderInstructions.indices.asSequence()
        .mapNotNull { index ->
            if (requestBuilderInstructions[index].getReference<StringReference>()?.string !=
                HOME_BROWSE_ID_MARKER
            ) return@mapNotNull null
            requestBuilderInstructions.getOrNull(index - 1)
                ?.takeIf { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                ?.getReference<FieldReference>()
                ?.takeIf { field ->
                    field.type == "Ljava/lang/String;" &&
                        classesByType.containsKey(field.definingClass)
                }
        }
        .singleOrError("Could not uniquely resolve the Browse endpoint ID field")

    return BrowseResolution(
        builderFactoryMethod = builderFactoryMethod,
        requestMethod = requestMethod,
        clientDataSetterMethod = clientDataSetterMethod,
        idSetterMethod = idSetterMethod,
        endpointIdField = endpointIdField,
    )
}

private fun BytecodePatchContext.resolveDelivery(): DeliveryResolution {
    val mediaIdValidationMethod = AndroidAutoMediaIdValidationFingerprint.method
    val controllerType = mediaIdValidationMethod.definingClass
    val loadResultType = mediaIdValidationMethod.parameterTypes.first().toString()
    val loadResultFieldReferences = mediaIdValidationMethod
        .references<FieldReference>()
        .distinct()
        .toList()

    // YTM 9.15 stores this media ID at i.k, 9.29 at j.k, and 9.30/9.31 at j.l.
    // Derive the path from YTM's validation method instead of maintaining a version table.
    val mediaIdFieldPath = findShortestReferencedStringPaths(
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

    return DeliveryResolution(
        controllerType = controllerType,
        loadResultType = loadResultType,
        mediaIdFieldPath = mediaIdFieldPath,
        resultDeliveryMethod = resultDeliveryMethod,
    )
}

// YTM 9.15 stores parsers on generated messages; 9.29+ gets them from the protobuf runtime.
// Extension numbers stay fixed, so resolve each message from the initializer containing its number.
private fun resolveExtensionMessageType(
    allMethods: Sequence<Method>,
    extensionFieldNumber: Int,
): String {
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
        .singleOrError("Could not uniquely resolve protobuf extension $extensionFieldNumber")
}

private fun BytecodePatchContext.injectRuntimeHooks(runtimeHooks: ResolvedRuntimeHooks) {
    val mutableBrowseBuilderFactoryMethod =
        mutableClassDefBy(runtimeHooks.browseBuilderFactoryMethod.definingClass).methods
            .single { method -> method.matches(runtimeHooks.browseBuilderFactoryMethod) }
    val schemaRegister = mutableBrowseBuilderFactoryMethod.findFreeRegister(0)
    check(schemaRegister < EIGHT_BIT_REGISTER_LIMIT) {
        "Browse builder factory has no free 8-bit register"
    }

    // YTM 9.15/9.29/9.30/9.31: Android Auto calls this factory before it asks for Library.
    // Save the Browse service here so playlists work even when YTM was not already open.
    mutableBrowseBuilderFactoryMethod.addInstructions(
        0,
        """
            const-string v$schemaRegister, "${runtimeHooks.encodedSchema}"
            invoke-static/range { v$schemaRegister .. v$schemaRegister }, $EXTENSION_CLASS->configure(Ljava/lang/String;)V
            invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Ljava/lang/Object;)V
        """,
    )

    val androidAutoLoadChildrenMethod =
        mutableClassDefBy(runtimeHooks.androidAutoControllerType).methods.single { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                AccessFlags.FINAL.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) ==
                listOf(runtimeHooks.loadResultType)
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
}
