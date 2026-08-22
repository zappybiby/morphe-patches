/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.cloneMutable
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.p0Register
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val ANDROID_AUTO_PLAYLIST_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$AndroidAutoPlaylistAccess;"

// move-result can only write to registers 0-255.
private const val EIGHT_BIT_REGISTER_LIMIT = 256
// iget-object can only address registers 0-15.
private const val FOUR_BIT_REGISTER_LIMIT = 16
// The new MediaDescription object is followed by its media ID and title in the next two registers.
private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

private const val RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "c"
private const val RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "g"
private const val RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "h"
private const val JAVA_OBJECT_CLASS = "Ljava/lang/Object;"
private const val JAVA_STRING_CLASS = "Ljava/lang/String;"
private const val JAVA_CHAR_SEQUENCE_CLASS = "Ljava/lang/CharSequence;"
private const val JAVA_ITERABLE_CLASS = "Ljava/lang/Iterable;"
private const val JAVA_LIST_CLASS = "Ljava/util/List;"
private const val JAVA_EXECUTOR_CLASS = "Ljava/util/concurrent/Executor;"
private const val LISTENABLE_FUTURE_CLASS =
    "Lcom/google/common/util/concurrent/ListenableFuture;"

private data class RendererResolution(
    val responsiveRendererType: String,
    val endpointFields: List<FieldReference>,
    val playlistMediaIdMethod: Method,
    val browseEndpointDecoderMethod: Method,
    val browseEndpointIdField: FieldReference,
    val artworkField: FieldReference,
    val artworkUrlsMethod: Method,
    val renderTextMethod: Method,
    val titleField: FieldReference,
    val subtitleField: FieldReference,
)

private data class BrowseResolution(
    val builderFactoryMethod: MethodReference,
    val continuationBuilderMethod: Method,
    val requestMethod: Method,
    val clientDataSetterMethod: Method,
    val idSetterMethod: Method,
    val endpointIdField: FieldReference,
)

private data class BrowseResponseResolution(
    val contentsMethod: Method,
    val contentSectionMethod: Method,
    val sectionItemMethods: List<Method>,
    val playlistRenderersMethod: Method,
    val continuationDecoderMethod: Method,
    val continuationResponseDecoderMethod: Method,
    val continuationResponsePayloadMethod: Method,
)

private data class DeliveryResolution(
    val controllerType: String,
    val loadResultType: String,
    val mediaIdFieldPath: List<FieldReference>,
    val resultDeliveryMethod: MethodReference,
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

private data class ResolvedRuntimeHooks(
    val renderer: RendererResolution,
    val browse: BrowseResolution,
    val browseResponse: BrowseResponseResolution,
    val delivery: DeliveryResolution,
    val startup: StartupResolution,
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
    val browseServiceCast = getOrNull(browseServiceCastIndex) as? OneRegisterInstruction
        ?: return null
    if (browseServiceCast.opcode != Opcode.CHECK_CAST ||
        browseServiceCast.getReference<TypeReference>()?.type != browseServiceType
    ) return null

    val moveResult = getOrNull(browseServiceCastIndex - 1) as? OneRegisterInstruction
        ?: return null
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT ||
        moveResult.registerA != browseServiceCast.registerA
    ) return null

    val providerGetterCall = getOrNull(browseServiceCastIndex - 2) ?: return null
    if (providerGetterCall.opcode != Opcode.INVOKE_INTERFACE &&
        providerGetterCall.opcode != Opcode.INVOKE_INTERFACE_RANGE
    ) return null
    val getter = providerGetterCall.getReference<MethodReference>() ?: return null
    if (getter.parameterTypes.isNotEmpty() || getter.returnType != JAVA_OBJECT_CLASS) return null

    val providerRead = getOrNull(browseServiceCastIndex - 3) as? TwoRegisterInstruction
        ?: return null
    if (providerRead.opcode != Opcode.IGET_OBJECT ||
        providerRead.registerA != providerGetterCall.receiverRegister()
    ) return null
    val field = providerRead.getReference<FieldReference>() ?: return null
    return field.takeIf { it.type == getter.definingClass }?.let { BrowseProvider(it, getter) }
}

private fun BytecodePatchContext.findBrowseProviderPaths(
    startType: String,
    providers: List<BrowseProvider>,
): List<Pair<List<FieldReference>, MethodReference>> {
    val providersByField = providers.associateBy { provider -> provider.field }
    val pending = ArrayDeque<List<FieldReference>>()
    pending.add(emptyList())
    val results = mutableListOf<List<FieldReference>>()
    var matchDepth: Int? = null

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (matchDepth?.let { current.size >= it } == true) continue
        val currentType = current.lastOrNull()?.type ?: startType
        classDefByOrNull(currentType)?.fields
            ?.filter { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) &&
                    (classDefByOrNull(field.type) != null || providersByField.containsKey(field))
            }
            ?.forEach { field ->
                val path = current + field
                if (providersByField.containsKey(field)) {
                    results += path
                    matchDepth = path.size
                } else if (field.type != startType &&
                    current.none { previous -> previous.definingClass == field.type }
                ) {
                    pending.add(path)
                }
            }
    }

    return results.distinct().map { path -> path to providersByField.getValue(path.last()).getter }
}

@Suppress("unused")
val restoreAndroidAutoPlaylistsPatch = bytecodePatch(
    name = "Restore playlists in Android Auto",
    description = "Restores YouTube Music playlists in Android Auto.",
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        injectNativePlaylistsNodeCapture()
        injectRuntimeHooks(resolveRuntimeHooks())
    }
}

private fun BytecodePatchContext.injectNativePlaylistsNodeCapture() {
    val androidAutoMediaItemMapper =
        AndroidAutoMediaItemMapperFingerprint.method

    androidAutoMediaItemMapper
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { constructorIndex ->
            val constructor =
                androidAutoMediaItemMapper.getInstruction<RegisterRangeInstruction>(
                    constructorIndex,
                )
            val nativeNodeMediaIdRegister =
                constructor.startRegister + MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET
            val nativeNodeTitleRegister =
                constructor.startRegister + MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET

            androidAutoMediaItemMapper.addInstructions(
                constructorIndex,
                """
                    invoke-static/range { v$nativeNodeMediaIdRegister .. v$nativeNodeTitleRegister }, $EXTENSION_CLASS->rememberNativePlaylistsMediaId(Ljava/lang/String;Ljava/lang/CharSequence;)V
                """,
            )
        }
}

private fun BytecodePatchContext.resolveRuntimeHooks(): ResolvedRuntimeHooks {
    val browseResponse = resolveBrowseResponse()
    val browse = resolveBrowse(browseResponse.continuationDecoderMethod)
    val renderer = resolveRenderer(browse.endpointIdField)
    val delivery = resolveDelivery()
    val startup = resolveStartup(
        delivery.controllerType,
        browse.builderFactoryMethod.definingClass,
    )

    return ResolvedRuntimeHooks(
        renderer = renderer,
        browse = browse,
        browseResponse = browseResponse,
        delivery = delivery,
        startup = startup,
    )
}

private fun BytecodePatchContext.resolveStartup(
    androidAutoControllerType: String,
    browseServiceType: String,
): StartupResolution {
    val androidAutoProviderMethod =
        androidAutoControllerProviderFingerprint(androidAutoControllerType).originalMethod
    val browseProviders = browseServiceProviderAccessFingerprint(browseServiceType)
        .matchAll()
        .flatMap { match ->
            val instructions = match.originalMethod.instructions.toList()
            instructions.indices.mapNotNull { index ->
                instructions.browseProviderAt(index, browseServiceType)
            }
        }
        .distinctBy { provider -> provider.field }

    val (browseProviderPath, browseProviderGetter) = findBrowseProviderPaths(
        androidAutoProviderMethod.definingClass,
        browseProviders,
    ).single()

    return StartupResolution(
        androidAutoProviderMethod,
        browseProviderPath,
        browseProviderGetter,
    )
}

private fun BytecodePatchContext.resolveBrowseResponse(): BrowseResponseResolution {
    // Use YTM's own methods to turn the Browse response into sections and playlist rows.
    val responseContentsMethod = BrowseResponseContentsFingerprint.originalMethod
    val contentMapperType = BrowseResponseContentsFingerprint.instructionMatches.last()
        .instruction
        .getReference<TypeReference>()!!
        .type
    val contentMapperFingerprint = browseContentMapperFingerprint(contentMapperType)
    val contentType = contentMapperFingerprint.instructionMatches.first()
        .instruction
        .getReference<TypeReference>()!!
        .type
    val contentSectionMethod = browseContentSectionFingerprint(contentType).originalMethod
    val sectionItemMethods = browseSectionItemsFingerprint(
        contentSectionMethod.returnType,
        responseContentsMethod.returnType,
    ).matchAll(2..2)
        .map { match -> match.originalMethod }
    val playlistRenderersMethod = PlaylistRendererDecoderFingerprint.originalMethod
    val playlistContainerType = playlistRenderersMethod.parameterTypes.single().toString()
    val continuationDecoderMethod = classDefBy(playlistRenderersMethod.definingClass).methods
        .single { method ->
            method != playlistRenderersMethod &&
                AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == JAVA_LIST_CLASS &&
                method.parameterTypes.map(CharSequence::toString) == listOf(playlistContainerType)
        }
    val continuationResponseDecoderMethod =
        PlaylistContinuationResponseDecoderFingerprint.originalMethod
    val continuationResponsePayloadType = continuationResponseDecoderMethod
        .parameterTypes.single().toString()
    val continuationResponsePayloadMethod = classDefBy(
        responseContentsMethod.definingClass,
    ).methods.single { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == continuationResponsePayloadType
    }

    return BrowseResponseResolution(
        responseContentsMethod,
        contentSectionMethod,
        sectionItemMethods,
        playlistRenderersMethod,
        continuationDecoderMethod,
        continuationResponseDecoderMethod,
        continuationResponsePayloadMethod,
    )
}

private fun BytecodePatchContext.resolveRenderer(
    browseEndpointIdField: FieldReference,
): RendererResolution {
    val responsiveRendererType = MusicResponsiveRendererExtensionFingerprint
        .instructionMatches
        .first()
        .instruction
        .getReference<TypeReference>()
        ?.type
        ?: throw PatchException("Could not resolve YTM's responsive renderer class")
    val responsiveRendererFields = classDefBy(responsiveRendererType).fields.toList()

    fun responsiveRendererField(name: String) = responsiveRendererFields
        .first { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    // Playlist rows use c for artwork and g/h for title and subtitle.
    val artworkField = responsiveRendererField(RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME)
    val titleField = responsiveRendererField(RESPONSIVE_RENDERER_TITLE_FIELD_NAME)
    val subtitleField = responsiveRendererField(RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME)
    if (artworkField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected responsive renderer artwork, title, or subtitle fields")
    }
    val artworkUrlsMethod = artworkUrlsFingerprint(artworkField.type).originalMethod
    val renderTextMethod = renderTextFingerprint(titleField.type).originalMethod

    val playlistMediaIdMethod = PlaylistPlaybackMediaIdFingerprint.originalMethod
    val endpointContainerType = PlaylistPlaybackMediaIdFingerprint.instructionMatches
        .first()
        .instruction
        .getReference<MethodReference>()
        ?.returnType
        ?: throw PatchException("Could not resolve the playlist action type")
    val endpointFields = responsiveRendererFields
        .filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == endpointContainerType
        }
    if (endpointFields.size != 2) {
        throw PatchException("Could not resolve the two responsive renderer action fields")
    }

    val browseEndpointDecoderMethod = browseEndpointDecoderFingerprint(
        endpointContainerType,
        browseEndpointIdField.definingClass,
    ).originalMethod

    return RendererResolution(
        responsiveRendererType = responsiveRendererType,
        endpointFields = endpointFields,
        playlistMediaIdMethod = playlistMediaIdMethod,
        browseEndpointDecoderMethod = browseEndpointDecoderMethod,
        browseEndpointIdField = browseEndpointIdField,
        artworkField = artworkField,
        artworkUrlsMethod = artworkUrlsMethod,
        renderTextMethod = renderTextMethod,
        titleField = titleField,
        subtitleField = subtitleField,
    )
}

private fun BytecodePatchContext.resolveBrowse(
    continuationDecoderMethod: Method,
): BrowseResolution {
    // Resolve the obfuscated Browse service from the methods that build and send its requests.
    val requestBuilderMethod = BrowseRequestBuilderFingerprint.originalMethod
    val builderType = requestBuilderMethod.returnType
    val builderFactoryMethod = requestBuilderMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .first { reference ->
            reference.parameterTypes.isEmpty() && reference.returnType == builderType
        }
    val serviceType = builderFactoryMethod.definingClass
    val continuationTypes = continuationDecoderMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val continuationBuilderMethod = classDefBy(serviceType).methods.single { method ->
        method.returnType == builderType &&
            method.parameterTypes.singleOrNull()?.toString() in continuationTypes
    }
    val requestFingerprint = authenticatedBrowseRequestFingerprint(serviceType, builderType)
    val requestMethod = requestFingerprint.originalMethod
    val browseBuilderIdField = requestFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val builderMethods = generateSequence(classDefBy(builderType)) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clientDataSetterMethod = builderMethods
        // YTM 9.32/9.33: use the protected setter because these versions also expose a public wrapper.
        .first { method ->
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
    val idSetterMethod = browseIdSetterFingerprint(browseBuilderIdField).originalMethod
    val endpointIdField = BrowseRequestBuilderFingerprint.instructionMatches
        .first()
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the Browse endpoint ID field")

    return BrowseResolution(
        builderFactoryMethod = builderFactoryMethod,
        continuationBuilderMethod = continuationBuilderMethod,
        requestMethod = requestMethod,
        clientDataSetterMethod = clientDataSetterMethod,
        idSetterMethod = idSetterMethod,
        endpointIdField = endpointIdField,
    )
}

private fun BytecodePatchContext.resolveDelivery(): DeliveryResolution {
    val mediaIdValidationMethod = AndroidAutoMediaIdValidationFingerprint.originalMethod
    val controllerType = mediaIdValidationMethod.definingClass
    val loadResultType = mediaIdValidationMethod.parameterTypes.first().toString()
    // The validation method reads the media ID through two nested fields in its result callback.
    val mediaIdFieldPath = mediaIdValidationMethod.instructions.asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .windowed(2)
        .first { fields ->
            fields[0].definingClass == loadResultType &&
                fields[1].definingClass == fields[0].type &&
                fields[1].type == JAVA_STRING_CLASS
        }

    // YTM may use a one-argument wrapper or call the two-argument delivery method directly.
    val resultDeliveryMethod = mediaIdValidationMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .first { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.definingClass == loadResultType && reference.returnType == "V" &&
                parameters.size in 1..2 &&
                parameters.firstOrNull() == "Ljava/util/List;" &&
                parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
        }

    return DeliveryResolution(
        controllerType = controllerType,
        loadResultType = loadResultType,
        mediaIdFieldPath = mediaIdFieldPath,
        resultDeliveryMethod = resultDeliveryMethod,
    )
}

private fun MutableClass.addAccessMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    registerCount: Int,
    instructions: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { parameter -> ImmutableMethodParameter(parameter, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registerCount),
        ).toMutable().apply {
            addInstructions(0, instructions)
        },
    )
}

private fun MutableClass.addObjectMethodAccess(
    name: String,
    method: MethodReference,
    returnType: String,
) = addAccessMethod(
    name,
    listOf(JAVA_OBJECT_CLASS),
    returnType,
    2,
    """
        check-cast p1, ${method.definingClass}
        invoke-virtual { p1 }, $method
        move-result-object p1
        return-object p1
    """,
)

private fun MutableClass.addObjectFieldAccess(
    name: String,
    ownerType: String,
    field: FieldReference,
    returnType: String = JAVA_OBJECT_CLASS,
) = addAccessMethod(
    name,
    listOf(JAVA_OBJECT_CLASS),
    returnType,
    2,
    """
        check-cast p1, $ownerType
        iget-object p1, p1, $field
        return-object p1
    """,
)

private fun BytecodePatchContext.injectRuntimeAccess(runtimeHooks: ResolvedRuntimeHooks) {
    val renderer = runtimeHooks.renderer
    val browse = runtimeHooks.browse
    val response = runtimeHooks.browseResponse
    val delivery = runtimeHooks.delivery

    // Make YTM's response decoders public so the injected access methods can call them.
    mutableClassDefBy(response.playlistRenderersMethod.definingClass).apply {
        listOf(response.playlistRenderersMethod, response.continuationDecoderMethod)
            .forEach { method ->
                findMutableMethodOf(method).apply {
                    accessFlags = accessFlags.toPublicAccessFlags()
                }
            }
    }

    // The native decoder does not read its receiver. Keep the same register layout by
    // copying it as a static method with an unused first parameter.
    val continuationResponseDecoder = response.continuationResponseDecoderMethod.let { method ->
        method.cloneMutable(
            name = "patch_decodeContinuationResponse",
            accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            parameters = listOf(
                ImmutableMethodParameter(method.definingClass, null, null),
            ) + method.parameters,
        ).also { copiedMethod ->
            mutableClassDefBy(method.definingClass).methods.add(copiedMethod)
        }
    }

    mutableClassDefBy(browse.builderFactoryMethod.definingClass).apply {
        interfaces.add(ANDROID_AUTO_PLAYLIST_ACCESS_INTERFACE)
        addAccessMethod(
            "patch_requestBrowse",
            listOf(JAVA_STRING_CLASS, JAVA_EXECUTOR_CLASS),
            LISTENABLE_FUTURE_CLASS,
            5,
            """
                invoke-virtual { p0 }, ${browse.builderFactoryMethod}
                move-result-object v0
                invoke-virtual { v0, p1 }, ${browse.idSetterMethod}
                const/4 v1, 0x0
                new-array v1, v1, [B
                invoke-virtual { v0, v1 }, ${browse.clientDataSetterMethod}
                invoke-virtual { p0, v0, p2 }, ${browse.requestMethod}
                move-result-object v0
                return-object v0
            """,
        )
        val continuationType = browse.continuationBuilderMethod
            .parameterTypes.single().toString()
        addAccessMethod(
            "patch_requestContinuation",
            listOf(JAVA_OBJECT_CLASS, JAVA_EXECUTOR_CLASS),
            LISTENABLE_FUTURE_CLASS,
            3,
            """
                check-cast p1, $continuationType
                invoke-virtual { p0, p1 }, ${browse.continuationBuilderMethod}
                move-result-object p1
                invoke-virtual { p0, p1, p2 }, ${browse.requestMethod}
                move-result-object p1
                return-object p1
            """,
        )
        addAccessMethod(
            "patch_playlistMediaId",
            listOf(JAVA_STRING_CLASS),
            JAVA_STRING_CLASS,
            2,
            """
                invoke-static { p1 }, ${renderer.playlistMediaIdMethod}
                move-result-object p1
                return-object p1
            """,
        )
        addObjectMethodAccess("patch_getContents", response.contentsMethod, JAVA_ITERABLE_CLASS)
        addAccessMethod(
            "patch_getContinuationContent",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_OBJECT_CLASS,
            3,
            """
                check-cast p1, ${response.continuationResponsePayloadMethod.definingClass}
                invoke-virtual { p1 }, ${response.continuationResponsePayloadMethod}
                move-result-object p1
                const/4 v0, 0x0
                invoke-static { v0, p1 }, $continuationResponseDecoder
                move-result-object p1
                return-object p1
            """,
        )
        addObjectMethodAccess("patch_getSection", response.contentSectionMethod, JAVA_OBJECT_CLASS)

        val sectionItemInstructions = buildString {
            appendLine("check-cast p1, ${response.sectionItemMethods.first().definingClass}")
            appendLine("const/16 v0, ${response.sectionItemMethods.size}")
            appendLine("new-array v0, v0, [Ljava/lang/Iterable;")
            response.sectionItemMethods.forEachIndexed { index, method ->
                appendLine("invoke-virtual { p1 }, $method")
                appendLine("move-result-object v1")
                appendLine("const/16 v2, $index")
                appendLine("aput-object v1, v0, v2")
            }
            append("return-object v0")
        }
        addAccessMethod(
            "patch_getItemGroups",
            listOf(JAVA_OBJECT_CLASS),
            "[Ljava/lang/Iterable;",
            5,
            sectionItemInstructions,
        )
        val playlistContainerType =
            response.playlistRenderersMethod.parameterTypes.single().toString()
        addAccessMethod(
            "patch_getRenderers",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_ITERABLE_CLASS,
            3,
            """
                instance-of v0, p1, $playlistContainerType
                if-eqz v0, :not_playlist_container
                check-cast p1, $playlistContainerType
                invoke-static { p1 }, ${response.playlistRenderersMethod}
                move-result-object p1
                return-object p1
                :not_playlist_container
                const/4 p1, 0x0
                return-object p1
            """,
        )
        addAccessMethod(
            "patch_getContinuations",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_ITERABLE_CLASS,
            3,
            """
                instance-of v0, p1, $playlistContainerType
                if-eqz v0, :no_continuations
                check-cast p1, $playlistContainerType
                invoke-static { p1 }, ${response.continuationDecoderMethod}
                move-result-object p1
                return-object p1
                :no_continuations
                const/4 p1, 0x0
                return-object p1
            """,
        )
        addAccessMethod(
            "patch_isResponsiveRenderer",
            listOf(JAVA_OBJECT_CLASS),
            "Z",
            2,
            """
                instance-of p1, p1, ${renderer.responsiveRendererType}
                return p1
            """,
        )
        val accessors = listOf(
            "patch_getFirstEndpoint" to renderer.endpointFields[0],
            "patch_getSecondEndpoint" to renderer.endpointFields[1],
        )
        accessors.forEach { (methodName, field) ->
            addObjectFieldAccess(
                methodName,
                renderer.responsiveRendererType,
                field,
            )
        }
        listOf(
            "patch_getTitle" to renderer.titleField,
            "patch_getSubtitle" to renderer.subtitleField,
        ).forEach { (methodName, field) ->
            // The optional second argument is text-to-speech metadata, which playlist titles do not need.
            addAccessMethod(
                methodName,
                listOf(JAVA_OBJECT_CLASS),
                JAVA_CHAR_SEQUENCE_CLASS,
                3,
                """
                    check-cast p1, ${renderer.responsiveRendererType}
                    iget-object p1, p1, $field
                    const/4 v0, 0x0
                    invoke-static { p1, v0 }, ${renderer.renderTextMethod}
                    move-result-object p1
                    return-object p1
                """,
            )
        }
        addAccessMethod(
            "patch_getArtworkUrls",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_ITERABLE_CLASS,
            2,
            """
                check-cast p1, ${renderer.responsiveRendererType}
                iget-object p1, p1, ${renderer.artworkField}
                if-eqz p1, :no_artwork
                invoke-static { p1 }, ${renderer.artworkUrlsMethod}
                move-result-object p1
                return-object p1
                :no_artwork
                const/4 p1, 0x0
                return-object p1
            """,
        )
        addAccessMethod(
            "patch_getBrowseId",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_STRING_CLASS,
            2,
            """
                check-cast p1, ${renderer.endpointFields.first().type}
                invoke-static { p1 }, ${renderer.browseEndpointDecoderMethod}
                move-result-object p1
                iget-object p1, p1, ${renderer.browseEndpointIdField}
                return-object p1
            """,
        )
        val mediaIdInstructions = buildString {
            appendLine("check-cast p1, ${delivery.loadResultType}")
            delivery.mediaIdFieldPath.forEach { field ->
                appendLine("iget-object p1, p1, $field")
            }
            append("return-object p1")
        }
        addAccessMethod(
            "patch_getMediaId",
            listOf(JAVA_OBJECT_CLASS),
            JAVA_STRING_CLASS,
            2,
            mediaIdInstructions,
        )
        val extraDeliveryParameter = delivery.resultDeliveryMethod.parameterTypes.size == 2
        addAccessMethod(
            "patch_sendResult",
            listOf(JAVA_OBJECT_CLASS, JAVA_LIST_CLASS),
            "V",
            if (extraDeliveryParameter) 4 else 3,
            if (extraDeliveryParameter) {
                """
                    const/4 v0, 0x0
                    check-cast p1, ${delivery.loadResultType}
                    invoke-virtual { p1, p2, v0 }, ${delivery.resultDeliveryMethod}
                    return-void
                """
            } else {
                """
                    check-cast p1, ${delivery.loadResultType}
                    invoke-virtual { p1, p2 }, ${delivery.resultDeliveryMethod}
                    return-void
                """
            },
        )
    }
}

private fun BytecodePatchContext.injectRuntimeHooks(runtimeHooks: ResolvedRuntimeHooks) {
    injectRuntimeAccess(runtimeHooks)

    val startup = runtimeHooks.startup
    val androidAutoProviderMethod = mutableClassDefBy(
        startup.androidAutoProviderMethod.definingClass,
    ).findMutableMethodOf(startup.androidAutoProviderMethod)
    val controllerConstructorIndex = androidAutoProviderMethod.indexOfFirstInstructionOrThrow {
        getReference<MethodReference>()?.let { reference ->
            reference.definingClass == runtimeHooks.delivery.controllerType &&
                reference.name == "<init>"
        } == true
    }
    val controllerRegister = androidAutoProviderMethod
        .getInstruction<Instruction>(controllerConstructorIndex)
        .receiverRegister()
        ?: throw PatchException("Could not resolve the Android Auto controller register")
    val controllerReturnIndex = androidAutoProviderMethod.indexOfFirstInstructionOrThrow(
        controllerConstructorIndex,
    ) {
        opcode == Opcode.RETURN_OBJECT &&
            (this as? OneRegisterInstruction)?.registerA == controllerRegister
    }
    val providerRegister = androidAutoProviderMethod.findFreeRegister(
        controllerReturnIndex,
        androidAutoProviderMethod.p0Register,
    )
    if (providerRegister >= FOUR_BIT_REGISTER_LIMIT) {
        throw PatchException("Android Auto provider has no free 4-bit register")
    }

    val browseProviderTraversal = buildString {
        appendLine("move-object/from16 v$providerRegister, p0")
        startup.browseProviderPath.forEach { field ->
            appendLine("iget-object v$providerRegister, v$providerRegister, $field")
            appendLine("if-eqz v$providerRegister, :skip_playlist_initialization")
        }
        appendLine(
            "invoke-interface/range { v$providerRegister .. v$providerRegister }, " +
                startup.browseProviderGetter,
        )
        appendLine("move-result-object v$providerRegister")
        appendLine("if-eqz v$providerRegister, :skip_playlist_initialization")
        append("check-cast v$providerRegister, $ANDROID_AUTO_PLAYLIST_ACCESS_INTERFACE")
    }

    // Android Auto creates its controller without otherwise requesting the authenticated Browse
    // service. Read that service from the same Dagger provider while the controller is created.
    androidAutoProviderMethod.addInstructionsWithLabels(
        controllerReturnIndex,
        """
            $browseProviderTraversal
            invoke-static/range { v$providerRegister .. v$providerRegister }, $EXTENSION_CLASS->initialize($ANDROID_AUTO_PLAYLIST_ACCESS_INTERFACE)V
        """,
        ExternalLabel(
            "skip_playlist_initialization",
            androidAutoProviderMethod.getInstruction<Instruction>(controllerReturnIndex),
        ),
    )

    val androidAutoLoadChildrenMethod = androidAutoLoadChildrenFingerprint(
        runtimeHooks.delivery.controllerType,
        runtimeHooks.delivery.loadResultType,
    ).method
    val playlistHandledRegister = androidAutoLoadChildrenMethod.findFreeRegister(0)
    if (playlistHandledRegister >= EIGHT_BIT_REGISTER_LIMIT) {
        throw PatchException("Android Auto load-children method has no free 8-bit register")
    }

    // p1 is YTM's result callback; return early when the extension will send the playlist list.
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
