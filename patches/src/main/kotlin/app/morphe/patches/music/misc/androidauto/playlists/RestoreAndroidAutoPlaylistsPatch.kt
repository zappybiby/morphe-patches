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
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
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
private val REQUIRED_MORPHE_EXTENSION_CLASSES = setOf(
    "Lapp/morphe/extension/shared/Logger;",
    "Lapp/morphe/extension/shared/Utils;",
    "Lapp/morphe/extension/shared/settings/BooleanSetting;",
)
private const val MEDIA_ITEM_CLASS =
    "Landroid/support/v4/media/MediaBrowserCompat\$MediaItem;"
private const val MEDIA_DESCRIPTION_CLASS =
    "Landroid/support/v4/media/MediaDescriptionCompat;"
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595
private const val MUSIC_SHELF_RENDERER_EXTENSION_FIELD_NUMBER = 175_617_300
private const val HOME_BROWSE_ID_MARKER = "FEmusic_home"
private const val FOUR_BIT_REGISTER_LIMIT = 16
private const val EIGHT_BIT_REGISTER_LIMIT = 256
private val TESTED_YOUTUBE_MUSIC_VERSIONS = setOf(
    "9.15.51",
    "9.29.54",
    "9.30.52",
    "9.31.51",
)
private val ANDROID_AUTO_PLAYLIST_COMPATIBILITY = Compatibility(
    name = requireNotNull(COMPATIBILITY_YOUTUBE_MUSIC.name),
    packageName = requireNotNull(COMPATIBILITY_YOUTUBE_MUSIC.packageName),
    description = COMPATIBILITY_YOUTUBE_MUSIC.description,
    apkFileType = COMPATIBILITY_YOUTUBE_MUSIC.apkFileType,
    appIconColor = COMPATIBILITY_YOUTUBE_MUSIC.appIconColor,
    signatures = COMPATIBILITY_YOUTUBE_MUSIC.signatures,
    targets = COMPATIBILITY_YOUTUBE_MUSIC.targets
        .filter { target -> target.version in TESTED_YOUTUBE_MUSIC_VERSIONS },
)

// YouTube Music uses these field names in every supported 9.x version.
private const val STABLE_ENDPOINT_TYPE_FIELD_NAME = "k"
private val RESPONSIVE_RENDERER_ENDPOINT_FIELD_NAMES = setOf("i", "j")
private val RESPONSIVE_RENDERER_IDENTIFYING_FIELD_NAMES = setOf("c", "g", "h")
private val MUSIC_SHELF_IDENTIFYING_FIELD_NAMES = setOf("f", "k")

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
    name = "Browse Android Auto playlists",
    description = "Restores browsable YouTube Music playlists in Android Auto.",
) {
    extendWith("extensions/android-auto-playlists.mpe")
    dependsOn(resourceMappingPatch, standaloneSettingsPatch)

    compatibleWith(ANDROID_AUTO_PLAYLIST_COMPATIBILITY)

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
        val mediaDescriptionMediaIdField =
            mediaDescriptionClass.constructorField(mediaDescriptionConstructor, 0)
        val mediaDescriptionTitleField =
            mediaDescriptionClass.constructorField(mediaDescriptionConstructor, 1)
        val playlistTitleResourceId =
            getResourceId(ResourceType.STRING, "library_playlists_shelf_title")

        fun resolveExtensionMessageType(
            extensionFieldNumber: Int,
            requiredFieldNames: Set<String>,
        ): String {
            return allMethods
                .filter { method ->
                    method.instructionList.any { instruction ->
                        (instruction as? NarrowLiteralInstruction)?.narrowLiteral ==
                            extensionFieldNumber
                    }
                }
                .flatMap { method ->
                    method.references<TypeReference>().map { it.type }
                }
                .distinct()
                .filter { type ->
                    classesByType[type]?.fields
                        ?.map { field -> field.name }
                        ?.containsAll(requiredFieldNames) == true
                }
                .singleOrError(
                    "Could not uniquely resolve protobuf extension $extensionFieldNumber",
                )
        }

        val responsiveRendererType = resolveExtensionMessageType(
            MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER,
            RESPONSIVE_RENDERER_IDENTIFYING_FIELD_NAMES,
        )
        val shelfRendererType = resolveExtensionMessageType(
            MUSIC_SHELF_RENDERER_EXTENSION_FIELD_NUMBER,
            MUSIC_SHELF_IDENTIFYING_FIELD_NAMES,
        )
        val responsiveRendererFields = classesByType[responsiveRendererType]!!.fields.toList()
        // YouTube Music 9.30 moved the endpoint from i to j. Field k still has the same type,
        // which distinguishes the right endpoint in every supported version.
        val stableEndpointType = responsiveRendererFields
            .single { field -> field.name == STABLE_ENDPOINT_TYPE_FIELD_NAME }
            .type
        val rendererEndpointField = responsiveRendererFields.single { field ->
            field.name in RESPONSIVE_RENDERER_ENDPOINT_FIELD_NAMES &&
                field.type == stableEndpointType
        }
        val endpointMediaIdMethod = allMethods
            .filter { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.hasSignature("Ljava/lang/String;", stableEndpointType) &&
                    method.instructionList.any { instruction ->
                        instruction.opcode == Opcode.IPUT_OBJECT &&
                            instruction.getReference<FieldReference>()?.type == stableEndpointType
                    }
            }
            .singleOrError(
                "Could not uniquely resolve endpoint media-ID helper for $stableEndpointType",
            )

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
        val builderTypes = builderClasses.mapTo(mutableSetOf()) { it.type }
        val builderMethods = builderClasses.flatMap { it.methods }
        val browseClientDataSetterMethod = builderMethods.asSequence()
            .filter { it.hasSignature("V", "[B") }
            .singleOrError("Could not uniquely resolve the authenticated Browse client-data setter")
        val continuationSetterMethod = builderMethods.asSequence()
            .filter { method -> method.returnType == "V" && method.parameterTypes.size == 1 }
            .flatMap { method ->
                val sourceType = method.parameterTypes.single().toString()
                val references = method.references<MethodReference>().toList()
                val sourceReturns = references.asSequence().filter { reference ->
                    reference.definingClass == sourceType && reference.parameterTypes.isEmpty()
                }.mapTo(mutableSetOf()) { reference -> reference.returnType }
                if (!sourceReturns.containsAll(setOf("Ljava/lang/String;", "[B"))) {
                    emptySequence()
                } else {
                    references.asSequence().filter { reference ->
                        reference.definingClass in builderTypes && reference.returnType == "V" &&
                            reference.parameterTypes.map(CharSequence::toString) ==
                            listOf("Ljava/lang/String;")
                    }
                }
            }
            .singleOrError("Could not uniquely resolve the authenticated Browse continuation setter")
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

        // The FEmusic_home request gives us a stable way to find this field across versions.
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
        val mediaIdFieldPath = findStringPaths(
            loadResultType,
            loadResultFieldReferences,
        ).asSequence()
            .singleOrError("Could not uniquely resolve the Android Auto media ID field path")
        val resultDeliveryMethod = mediaIdValidationMethod.references<MethodReference>()
            .filter { reference ->
                val parameters = reference.parameterTypes.map(CharSequence::toString)
                // YouTube Music 9.30 added an optional second object parameter.
                reference.definingClass == loadResultType && reference.returnType == "V" &&
                    parameters.firstOrNull() == "Ljava/util/List;" &&
                    parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
            }
            .singleOrError("Could not uniquely resolve the Android Auto result delivery method")

        // Android Auto can start YouTube Music first, so this path must initialize Browse too.
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

        val (browseProviderPath, browseProviderGetter) = findBrowseProviders(
            androidAutoProviderMethod.definingClass,
            browseProviders,
            classesByType,
        ).asSequence().singleOrError(
            "Could not uniquely reach the Browse service provider from the Android Auto provider",
        )

        val encodedRuntimeSchema = listOf(
            responsiveRendererType.toRuntimeClassName(),
            shelfRendererType.toRuntimeClassName(),
            rendererEndpointField.name,
            endpointMediaIdMethod.definingClass.toRuntimeClassName(),
            endpointMediaIdMethod.name,
            browseEndpointIdField.definingClass.toRuntimeClassName(),
            browseEndpointIdField.name,
            browseIdSetterMethod.name,
            continuationSetterMethod.name,
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
        // Search for the matching return because other patches may add setup after the constructor.
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
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleCollection(Ljava/lang/Object;)Z
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

    finalize {
        if (REQUIRED_MORPHE_EXTENSION_CLASSES.any { classDescriptor ->
            mutableClassDefByOrNull(classDescriptor) == null
        }) {
            throw PatchException("Requires Morphe's official YouTube Music patches")
        }
    }
}
