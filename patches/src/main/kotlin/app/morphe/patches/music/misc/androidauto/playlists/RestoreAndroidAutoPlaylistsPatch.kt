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
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.ArrayDeque

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val MEDIA_ITEM_CLASS =
    "Landroid/support/v4/media/MediaBrowserCompat\$MediaItem;"
private const val MEDIA_DESCRIPTION_CLASS =
    "Landroid/support/v4/media/MediaDescriptionCompat;"
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595
private const val PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER = 52_666_186
private const val HOME_BROWSE_ID_MARKER = "FEmusic_home"
private const val FOUR_BIT_REGISTER_LIMIT = 16
private const val EIGHT_BIT_REGISTER_LIMIT = 256
private const val RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "c"
private const val RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "g"
private const val RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "h"

private val Method.instructionList
    get() = implementation?.instructions?.toList().orEmpty()

private inline fun <reified T : Reference> Method.references() =
    instructionList.asSequence().mapNotNull { it.getReference<T>() }

private fun Method.hasSignature(result: String, vararg parameters: String) =
    returnType == result && parameterTypes.map(CharSequence::toString) == parameters.toList()

private fun ClassDef.constructorField(
    parameterTypes: List<String>,
    parameterIndex: Int,
): FieldReference {
    val constructor = methods.asSequence()
        .filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map(CharSequence::toString) == parameterTypes
        }
        .singleOrError("Could not uniquely resolve the $type constructor")
    val parameterRegister = constructor.p0Register + 1 + parameterTypes
        .take(parameterIndex)
        .sumOf { parameterType -> if (parameterType == "J" || parameterType == "D") 2 else 1 }

    return constructor.instructionList.asSequence()
        .filterIsInstance<TwoRegisterInstruction>()
        .filter { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                instruction.registerA == parameterRegister &&
                instruction.registerB == constructor.p0Register
        }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field -> field.definingClass == type }
        .singleOrError("Could not uniquely resolve constructor parameter $parameterIndex in $type")
}

private fun <T> Sequence<T>.singleOrError(message: String) =
    distinct().toList().let { it.singleOrNull() ?: error("$message: ${it.joinToString()}") }

private fun String.toRuntimeClassName() = removePrefix("L").removeSuffix(";").replace('/', '.')

private data class BrowseProvider(val field: FieldReference, val getter: MethodReference)

private data class RendererEndpoints(
    val fields: List<FieldReference>,
    val mediaIdMethod: Method,
)

private fun Instruction.receiverRegister() = when (this) {
    is FiveRegisterInstruction -> registerC.takeIf { registerCount > 0 }
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount > 0 }
    else -> null
}

private fun List<Instruction>.browseProviderAt(
    browseServiceCastIndex: Int,
    browseServiceType: String,
): BrowseProvider? {
    val browseServiceCast =
        getOrNull(browseServiceCastIndex) as? OneRegisterInstruction ?: return null
    if (browseServiceCast.opcode != Opcode.CHECK_CAST ||
        browseServiceCast.getReference<TypeReference>()?.type != browseServiceType
    ) return null

    val moveResultIndex = browseServiceCastIndex - 1
    val moveResult = getOrNull(moveResultIndex) as? OneRegisterInstruction ?: return null
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT ||
        moveResult.registerA != browseServiceCast.registerA
    ) {
        return null
    }
    val providerGetterIndex = moveResultIndex - 1
    val providerGetterCall = getOrNull(providerGetterIndex) ?: return null
    if (providerGetterCall.opcode != Opcode.INVOKE_INTERFACE &&
        providerGetterCall.opcode != Opcode.INVOKE_INTERFACE_RANGE
    ) return null
    val getter = providerGetterCall.getReference<MethodReference>() ?: return null
    if (getter.parameterTypes.isNotEmpty() || getter.returnType != "Ljava/lang/Object;") return null

    val providerReadIndex = providerGetterIndex - 1
    val providerRead = getOrNull(providerReadIndex) as? TwoRegisterInstruction ?: return null
    if (providerRead.opcode != Opcode.IGET_OBJECT ||
        providerRead.registerA != providerGetterCall.receiverRegister()
    ) return null
    val field = providerRead.getReference<FieldReference>() ?: return null
    return field.takeIf { it.type == getter.definingClass }?.let { BrowseProvider(it, getter) }
}

private fun findShortestFieldPaths(
    startType: String,
    fieldsForType: (String) -> Iterable<FieldReference>,
    matches: (FieldReference) -> Boolean,
): List<List<FieldReference>> {
    val pending = ArrayDeque<List<FieldReference>>()
    pending.add(emptyList())
    val results = mutableListOf<List<FieldReference>>()
    var matchDepth: Int? = null

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (matchDepth?.let { current.size >= it } == true) continue
        fieldsForType(current.lastOrNull()?.type ?: startType).forEach { field ->
            val path = current + field
            if (matches(field)) {
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

private fun findBrowseProviders(
    startType: String,
    providers: List<BrowseProvider>,
    classes: Map<String, ClassDef>,
): List<Pair<List<FieldReference>, MethodReference>> {
    val providersByField = providers.associateBy { it.field }
    return findShortestFieldPaths(
        startType,
        fieldsForType = { type ->
            classes[type]?.fields?.filter { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) &&
                    (classes.containsKey(field.type) || providersByField.containsKey(field))
            }.orEmpty()
        },
        matches = providersByField::containsKey,
    ).map { path -> path to providersByField.getValue(path.last()).getter }
}

private fun findStringPaths(
    startType: String,
    fields: List<FieldReference>,
): List<List<FieldReference>> {
    val fieldsByOwner = fields.groupBy { it.definingClass }
    return findShortestFieldPaths(
        startType,
        fieldsForType = { type -> fieldsByOwner[type].orEmpty() },
        matches = { field -> field.type == "Ljava/lang/String;" },
    )
}

@Suppress("unused")
val restoreAndroidAutoPlaylistsPatch = bytecodePatch(
    name = "Restore Playlists in Android Auto",
    description = "Restores YouTube Music playlists as directly playable items in Android Auto.",
) {
    extendWith("extensions/android-auto-playlists.mpe")
    dependsOn(
        standaloneExtensionPatch,
        resourceMappingPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val classesByType = linkedMapOf<String, ClassDef>()
        classDefForEach { classDef -> classesByType[classDef.type] = classDef }
        val allMethods = classesByType.values.asSequence().flatMap { it.methods.asSequence() }

        val mediaItemDescriptionField = classesByType[MEDIA_ITEM_CLASS]
            ?.constructorField(listOf(MEDIA_DESCRIPTION_CLASS, "I"), 0)
            ?: error("Could not resolve $MEDIA_ITEM_CLASS")
        val mediaDescriptionClass = classesByType[MEDIA_DESCRIPTION_CLASS]
            ?: error("Could not resolve $MEDIA_DESCRIPTION_CLASS")
        val mediaDescriptionConstructor = listOf(
            "Ljava/lang/String;",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Landroid/graphics/Bitmap;",
            "Landroid/net/Uri;",
            "Landroid/os/Bundle;",
            "Landroid/net/Uri;",
        )
        // YTM 9.15/9.29/9.30/9.31: MediaDescriptionCompat keeps the same constructor layout.
        // Resolve the fields written by its parameters instead of relying on reflection field order.
        val mediaDescriptionMediaIdField =
            mediaDescriptionClass.constructorField(mediaDescriptionConstructor, 0)
        val mediaDescriptionTitleField =
            mediaDescriptionClass.constructorField(mediaDescriptionConstructor, 1)
        val playlistTitleResourceId =
            getResourceId(ResourceType.STRING, "library_playlists_shelf_title")

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
        // Verify their types before passing these generated field names to runtime.
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

        // YTM 9.15/9.29/9.30/9.31: each responsive renderer keeps its endpoints in i and k.
        // Pass both so runtime can identify the Browse and playback endpoints from their contents.
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

        // YTM 9.15/9.29/9.30/9.31: Android Auto can create this controller before the phone UI
        // initializes Browse. Initialize Browse from the controller startup path too.
        val androidAutoProviderMethod = allMethods
            .filter { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "Ljava/lang/Object;" &&
                    method.instructionList.any { instruction ->
                        instruction.getReference<MethodReference>()?.let { reference ->
                            reference.definingClass == androidAutoControllerType &&
                                reference.name == "<init>"
                        } == true
                    }
            }
            .singleOrError("Could not uniquely resolve the Android Auto controller provider")

        val browseProviders = allMethods.flatMap { method ->
            val instructions = method.instructionList
            instructions.indices.asSequence()
                .mapNotNull { castIndex ->
                    instructions.browseProviderAt(castIndex, browseServiceType)
                }
        }.distinctBy { provider -> provider.field }.toList()
        check(browseProviders.isNotEmpty()) { "Could not resolve a provider for the Browse service" }

        // YTM 9.15/9.29/9.30/9.31: the provider field/getter pairs are eY/gG, gc/hE, gd/hC,
        // and gi/hx. Resolve the only shortest path instead of hardcoding those generated names.
        val (browseProviderPath, browseProviderGetter) = findBrowseProviders(
            androidAutoProviderMethod.definingClass,
            browseProviders,
            classesByType,
        ).asSequence().singleOrError(
            "Could not uniquely reach the Browse service provider from the Android Auto provider",
        )

        val encodedRuntimeSchema = listOf(
            responsiveRendererType.toRuntimeClassName(),
            playlistEndpointType.toRuntimeClassName(),
            rendererEndpoints.fields[0].name,
            rendererEndpoints.fields[1].name,
            endpointMediaIdMethod.definingClass.toRuntimeClassName(),
            endpointMediaIdMethod.name,
            browseEndpointIdField.definingClass.toRuntimeClassName(),
            browseEndpointIdField.name,
            browseIdSetterMethod.name,
            mediaIdFieldPath.joinToString(",") { field -> field.name },
            browseBuilderFactoryMethod.name,
            browseRequestMethod.name,
            browseClientDataSetterMethod.name,
            resultDeliveryMethod.name,
            resultDeliveryMethod.parameterTypes.size.toString(),
            mediaItemDescriptionField.name,
            mediaDescriptionMediaIdField.name,
            mediaDescriptionTitleField.name,
            playlistTitleResourceId.toString(),
            rendererArtworkField.name,
            rendererTitleField.name,
            rendererSubtitleField.name,
        ).joinToString("|")

        val mutableAndroidAutoProviderMethod =
            mutableClassDefBy(androidAutoProviderMethod.definingClass).methods.single { method ->
                method.name == androidAutoProviderMethod.name &&
                    method.returnType == androidAutoProviderMethod.returnType &&
                    method.parameterTypes == androidAutoProviderMethod.parameterTypes
        }
        val controllerConstructorIndex = mutableAndroidAutoProviderMethod
            .indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == androidAutoControllerType && reference.name == "<init>"
                } == true
            }
        val controllerRegister = mutableAndroidAutoProviderMethod
            .getInstruction<Instruction>(controllerConstructorIndex)
            .receiverRegister() ?: error("Could not resolve the Android Auto controller register")
        // Morphe patches may add setup after YTM's controller constructor.
        // Inject before the return that carries the constructed controller.
        val controllerReturnIndex = mutableAndroidAutoProviderMethod.indexOfFirstInstructionOrThrow(
            controllerConstructorIndex,
        ) {
            opcode == Opcode.RETURN_OBJECT &&
                (this as? OneRegisterInstruction)?.registerA == controllerRegister
        }
        val providerRegister = mutableAndroidAutoProviderMethod.findFreeRegister(
            controllerReturnIndex,
            mutableAndroidAutoProviderMethod.p0Register,
        )
        check(providerRegister < FOUR_BIT_REGISTER_LIMIT) {
            "Android Auto provider has no free 4-bit register"
        }
        val browseProviderTraversalInstructions = buildString {
            appendLine("move-object/from16 v$providerRegister, p0")
            browseProviderPath.forEach { field ->
                appendLine(
                    "iget-object v$providerRegister, v$providerRegister, " +
                        "${field.definingClass}->${field.name}:${field.type}",
                )
                appendLine("if-eqz v$providerRegister, :skip_playlist_initialization")
            }
            appendLine(
                "invoke-interface/range { v$providerRegister .. v$providerRegister }, " +
                    "${browseProviderGetter.definingClass}->" +
                    "${browseProviderGetter.name}()${browseProviderGetter.returnType}",
            )
            appendLine("move-result-object v$providerRegister")
            append("if-eqz v$providerRegister, :skip_playlist_initialization")
        }
        mutableAndroidAutoProviderMethod.addInstructionsWithLabels(
            controllerReturnIndex,
            """
                const-string v$providerRegister, "$encodedRuntimeSchema"
                invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->configure(Ljava/lang/String;)V
                $browseProviderTraversalInstructions
                invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->initialize(Ljava/lang/Object;)V
            """,
            ExternalLabel(
                "skip_playlist_initialization",
                mutableAndroidAutoProviderMethod.getInstruction<Instruction>(controllerReturnIndex),
            ),
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
        androidAutoLoadChildrenMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handlePlaylistNode(Ljava/lang/Object;)Z
                move-result v$playlistHandledRegister
                if-eqz v$playlistHandledRegister, :resume
                return-void
            """,
            ExternalLabel("resume", androidAutoLoadChildrenMethod.getInstruction<Instruction>(0)),
        )

        val mutableResultDeliveryMethod = mutableClassDefBy(loadResultType).methods.single { method ->
            method.name == resultDeliveryMethod.name &&
                method.returnType == "V" &&
                method.parameterTypes == resultDeliveryMethod.parameterTypes
        }
        mutableResultDeliveryMethod.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->rememberNativePlaylistNode(Ljava/lang/Object;Ljava/util/List;)V
            """,
        )
    }
}
