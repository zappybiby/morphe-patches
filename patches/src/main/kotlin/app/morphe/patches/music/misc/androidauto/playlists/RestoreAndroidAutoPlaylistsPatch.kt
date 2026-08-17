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
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patcher.util.smali.ExternalLabel
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
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.util.ArrayDeque

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val EXTENSION_RUNTIME_VALUES_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch\$RuntimeValues;"
private const val MEDIA_DESCRIPTION_CLASS =
    "Landroid/support/v4/media/MediaDescriptionCompat;"
private const val OPTIONAL_CLASS = "Lj$/util/Optional;"
private const val ITERATOR_CLASS = "Ljava/util/Iterator;"
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595
private const val PLAYLIST_THUMBNAIL_EXTENSION_FIELD_NUMBER = 164_480_666
private const val PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER = 52_666_186
private const val HOME_BROWSE_ID_MARKER = "FEmusic_home"
private const val FOUR_BIT_REGISTER_LIMIT = 16
private const val EIGHT_BIT_REGISTER_LIMIT = 256
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

private data class BrowseProvider(
    val field: FieldReference,
    val getter: MethodReference,
)

private data class StartupResolution(
    val androidAutoProviderMethod: Method,
    val browseProviderPath: List<FieldReference>,
    val browseProviderGetter: MethodReference,
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
    val runtimeValues: Map<String, String>,
    val startup: StartupResolution,
    val androidAutoControllerType: String,
    val loadResultType: String,
)

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

private fun findShortestReferencedStringPaths(
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

    val moveResult = getOrNull(browseServiceCastIndex - 1) as? OneRegisterInstruction ?: return null
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT ||
        moveResult.registerA != browseServiceCast.registerA
    ) return null

    val providerGetterCall = getOrNull(browseServiceCastIndex - 2) ?: return null
    if (providerGetterCall.opcode != Opcode.INVOKE_INTERFACE &&
        providerGetterCall.opcode != Opcode.INVOKE_INTERFACE_RANGE
    ) return null
    val getter = providerGetterCall.getReference<MethodReference>() ?: return null
    if (getter.parameterTypes.isNotEmpty() || getter.returnType != "Ljava/lang/Object;") return null

    val providerRead = getOrNull(browseServiceCastIndex - 3) as? TwoRegisterInstruction
        ?: return null
    if (providerRead.opcode != Opcode.IGET_OBJECT ||
        providerRead.registerA != providerGetterCall.receiverRegister()
    ) return null
    val field = providerRead.getReference<FieldReference>() ?: return null
    return field.takeIf { it.type == getter.definingClass }?.let { BrowseProvider(it, getter) }
}

private fun findBrowseProviders(
    startType: String,
    providers: List<BrowseProvider>,
    classesByType: Map<String, ClassDef>,
): List<Pair<List<FieldReference>, MethodReference>> {
    val providersByField = providers.associateBy { it.field }
    return findShortestFieldPaths(
        startType,
        fieldsForType = { type ->
            classesByType[type]?.fields?.filter { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) &&
                    (classesByType.containsKey(field.type) || providersByField.containsKey(field))
            }.orEmpty()
        },
        matches = providersByField::containsKey,
    ).map { path -> path to providersByField.getValue(path.last()).getter }
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

    // YTM 9.15 can return Library items through more than one mapper branch. Check each
    // branch, but only inside this Android Auto mapper and only for the localized title.
    descriptionConstructorIndexes.asReversed().forEach { constructorIndex ->
        val constructor =
            mutableAndroidAutoMediaItemMapper.getInstruction<RegisterRangeInstruction>(
                constructorIndex,
            )
        val nativeNodeMediaIdRegister = constructor.startRegister + 1
        val nativeNodeTitleRegister = constructor.startRegister + 2

        mutableAndroidAutoMediaItemMapper.addInstructions(
            constructorIndex,
            """
                invoke-static/range { v$nativeNodeMediaIdRegister .. v$nativeNodeTitleRegister }, $EXTENSION_CLASS->rememberNativePlaylistsMediaId(Ljava/lang/String;Ljava/lang/CharSequence;)V
            """,
        )
    }
}

private fun BytecodePatchContext.resolveRuntimeHooks(
    classesByType: Map<String, ClassDef>,
    allMethods: Sequence<Method>,
): ResolvedRuntimeHooks {
    val renderer = resolveRenderer(classesByType, allMethods)
    val browse = resolveBrowse(classesByType)
    val delivery = resolveDelivery()
    val startup = resolveStartup(
        delivery.controllerType,
        browse.builderFactoryMethod.definingClass,
        classesByType,
        allMethods,
    )

    check(renderer.endpoints.fields.size == 2)
    check(delivery.mediaIdFieldPath.isNotEmpty())
    val runtimeValues = mapOf(
        "RESPONSIVE_RENDERER_CLASS_NAME" to
            renderer.responsiveRendererType.toRuntimeClassName(),
        "PLAYLIST_ENDPOINT_CLASS_NAME" to renderer.playlistEndpointType.toRuntimeClassName(),
        "RESPONSIVE_RENDERER_ENDPOINT_FIELD_NAMES" to
            renderer.endpoints.fields.joinToString(",") { it.name },
        "ENDPOINT_MEDIA_ID_METHOD" to renderer.endpoints.mediaIdMethod.toRuntimeMethod().encode(),
        "BROWSE_ENDPOINT_CLASS_NAME" to
            browse.endpointIdField.definingClass.toRuntimeClassName(),
        "BROWSE_ID_FIELD_NAME" to browse.endpointIdField.name,
        "BROWSE_ID_SETTER_METHOD" to browse.idSetterMethod.toRuntimeMethod().encode(),
        "LOAD_RESULT_MEDIA_ID_FIELD_PATH" to
            delivery.mediaIdFieldPath.joinToString(",") { it.name },
        "BROWSE_BUILDER_FACTORY_METHOD" to
            browse.builderFactoryMethod.toRuntimeMethod().encode(),
        "BROWSE_REQUEST_METHOD" to browse.requestMethod.toRuntimeMethod().encode(),
        "CLIENT_DATA_SETTER_METHOD" to browse.clientDataSetterMethod.toRuntimeMethod().encode(),
        "RESULT_DELIVERY_METHOD" to delivery.resultDeliveryMethod.toRuntimeMethod().encode(),
        "RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME" to renderer.artworkField.name,
        "PLAYLIST_THUMBNAIL_RENDERER_CLASS_NAME" to
            renderer.playlistThumbnailRendererType.toRuntimeClassName(),
        "RESPONSIVE_RENDERER_TITLE_FIELD_NAME" to renderer.titleField.name,
        "RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME" to renderer.subtitleField.name,
        "EXTENSION_MAP_CLASS_NAME" to
            renderer.extensionMapAccessors.field.definingClass.toRuntimeClassName(),
        "EXTENSION_MAP_FIELD_NAME" to renderer.extensionMapAccessors.field.name,
        "EXTENSION_MAP_ITERATOR_METHOD" to
            renderer.extensionMapAccessors.iteratorMethod.toRuntimeMethod().encode(),
    )

    return ResolvedRuntimeHooks(
        runtimeValues = runtimeValues,
        startup = startup,
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

    // These field names are obfuscated. In every supported version, artwork has its own
    // message type, while title and subtitle use the same text type.
    val artworkField = responsiveRendererField(RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME)
    val titleField = responsiveRendererField(RESPONSIVE_RENDERER_TITLE_FIELD_NAME)
    val subtitleField = responsiveRendererField(RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME)
    check(artworkField.type != titleField.type && titleField.type == subtitleField.type) {
        "Unexpected responsive renderer artwork, title, or subtitle fields"
    }

    // Each playlist row contains two endpoints of the same type: one opens the playlist
    // and one starts playback. YTM's media-ID method uniquely identifies that pair.
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

    // Browse and playback endpoints are stored in protobuf's internal extension storage.
    // Resolve its field and iterator here so the extension can read those endpoints later.
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

private fun resolveStartup(
    androidAutoControllerType: String,
    browseServiceType: String,
    classesByType: Map<String, ClassDef>,
    allMethods: Sequence<Method>,
): StartupResolution {
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
        instructions.indices.asSequence().mapNotNull { index ->
            instructions.browseProviderAt(index, browseServiceType)
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

    return StartupResolution(
        androidAutoProviderMethod,
        browseProviderPath,
        browseProviderGetter,
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

private fun BytecodePatchContext.injectRuntimeValues(values: Map<String, String>) {
    val fields = mutableClassDefBy(EXTENSION_RUNTIME_VALUES_CLASS).fields.associateBy { it.name }
    check(fields.keys == values.keys) {
        "Runtime value fields do not match the extension"
    }
    fields.forEach { (name, field) ->
        field.initialValue = MutableStringEncodedValue(
            ImmutableStringEncodedValue(values.getValue(name)),
        )
    }
}

private fun BytecodePatchContext.injectRuntimeHooks(runtimeHooks: ResolvedRuntimeHooks) {
    injectRuntimeValues(runtimeHooks.runtimeValues)

    val startup = runtimeHooks.startup
    val mutableAndroidAutoProviderMethod =
        mutableClassDefBy(startup.androidAutoProviderMethod.definingClass).methods.single { method ->
            method.matches(startup.androidAutoProviderMethod)
        }
    val controllerConstructorIndex = mutableAndroidAutoProviderMethod
        .indexOfFirstInstructionOrThrow {
            getReference<MethodReference>()?.let { reference ->
                reference.definingClass == runtimeHooks.androidAutoControllerType &&
                    reference.name == "<init>"
            } == true
        }
    val controllerRegister = mutableAndroidAutoProviderMethod
        .getInstruction<Instruction>(controllerConstructorIndex)
        .receiverRegister() ?: error("Could not resolve the Android Auto controller register")
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
        startup.browseProviderPath.forEach { field ->
            appendLine(
                "iget-object v$providerRegister, v$providerRegister, " +
                    "${field.definingClass}->${field.name}:${field.type}",
            )
            appendLine("if-eqz v$providerRegister, :skip_playlist_initialization")
        }
        appendLine(
            "invoke-interface/range { v$providerRegister .. v$providerRegister }, " +
                "${startup.browseProviderGetter.definingClass}->" +
                "${startup.browseProviderGetter.name}()" +
                startup.browseProviderGetter.returnType,
        )
        appendLine("move-result-object v$providerRegister")
        append("if-eqz v$providerRegister, :skip_playlist_initialization")
    }

    // YTM 9.15/9.29/9.30/9.31: on a cold start, Android Auto can create its controller before the
    // phone UI initializes Browse. Read the same Browse provider while that controller is created.
    mutableAndroidAutoProviderMethod.addInstructionsWithLabels(
        controllerReturnIndex,
        """
            $browseProviderTraversalInstructions
            invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->initialize(Ljava/lang/Object;)V
        """,
        ExternalLabel(
            "skip_playlist_initialization",
            mutableAndroidAutoProviderMethod.getInstruction<Instruction>(controllerReturnIndex),
        ),
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
