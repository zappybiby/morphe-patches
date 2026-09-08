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
import app.morphe.util.p0Register
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_BASE =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch"
private const val EXTENSION_CLASS = "$EXTENSION_BASE;"
private const val BROWSE_SERVICE_INTERFACE = "$EXTENSION_BASE\$BrowseService;"
private const val BROWSE_RESPONSE_INTERFACE = "$EXTENSION_BASE\$BrowseResponse;"
private const val BROWSE_TAB_INTERFACE = "$EXTENSION_BASE\$BrowseTab;"
private const val SECTION_LIST_INTERFACE = "$EXTENSION_BASE\$SectionList;"
private const val GRID_INTERFACE = "$EXTENSION_BASE\$Grid;"
private const val SONG_LIST_INTERFACE = "$EXTENSION_BASE\$SongList;"
private const val ANDROID_AUTO_REQUEST_INTERFACE = "$EXTENSION_BASE\$AndroidAutoRequest;"
private const val PLAYLIST_OR_SONG_INTERFACE = "$EXTENSION_BASE\$PlaylistOrSong;"
private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

// Metadata fields in YTM's playlist/song message (161429595).
private const val ARTWORK_CONTAINER_FIELD_NAME = "c"
private const val TITLE_FIELD_NAME = "g"
private const val SUBTITLE_FIELD_NAME = "h"
private const val PLAYLIST_BROWSE_ID_PREFIX = "VL"
private const val PLAYLIST_HEADER_FIELD_NAME = "q"

@Suppress("unused")
val restoreAndroidAutoPlaylistsPatch = bytecodePatch(
    name = "Restore playlists in Android Auto",
    description = "Restores YouTube Music playlists in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // The Java extension loads the Library and playlist Play actions using these YTM methods.
        hookPlaylistsTitleMediaIds()
        patchBrowseService()
        patchBrowseResponses()
        patchPlaylistItems()
        patchAndroidAutoPlaylists()
    }
}

private fun BytecodePatchContext.hookPlaylistsTitleMediaIds() {
    val buildAndroidAutoMediaItemMethod = BuildAndroidAutoMediaItemFingerprint.method

    // 9.15.51 builds Playlists through the FLAG_BROWSABLE branch, not the final constructor call.
    buildAndroidAutoMediaItemMethod
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                buildAndroidAutoMediaItemMethod.getInstruction<RegisterRangeInstruction>(
                    index,
                )
            // invoke-range starts at the receiver; media ID and title are its first two arguments.
            val mediaDescriptionMediaIdRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET
            val titleRegister =
                instruction.startRegister + MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET

            buildAndroidAutoMediaItemMethod.addInstructions(
                index,
                """
                    invoke-static/range { v$mediaDescriptionMediaIdRegister .. v$titleRegister }, $EXTENSION_CLASS->rememberPlaylistsTitleMatch(Ljava/lang/String;Ljava/lang/CharSequence;)V
                """,
            )
        }
}

private fun BytecodePatchContext.patchAndroidAutoPlaylists() {
    val sendEmptyAndroidAutoMediaItemsMethod = SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod
    addAndroidAutoRequestInterface(sendEmptyAndroidAutoMediaItemsMethod)
    hookAndroidAutoRequest(
        sendEmptyAndroidAutoMediaItemsMethod.definingClass,
        sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString(),
    )
}

private fun BytecodePatchContext.addAndroidAutoRequestInterface(
    sendEmptyAndroidAutoMediaItemsMethod: Method,
) {
    val androidAutoRequestType = sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    val androidAutoRequestFields = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .toList()
    // The invalid-ID handler exposes both the requested ID and the method used to send a result.
    val requestedMediaIdHolderField = androidAutoRequestFields.single { field ->
        field.definingClass == androidAutoRequestType
    }
    val requestedMediaIdField = androidAutoRequestFields.single { field ->
        field.definingClass == requestedMediaIdHolderField.type &&
            field.type == "Ljava/lang/String;"
    }

    val deliverAndroidAutoMediaItemsMethod = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.definingClass == androidAutoRequestType && reference.returnType == "V" &&
                parameters.size in 1..2 &&
                parameters.firstOrNull() == "Ljava/util/List;" &&
                parameters.drop(1).all { it.startsWith("L") || it.startsWith("[") }
        }

    androidAutoRequestClass.interfaces.add(ANDROID_AUTO_REQUEST_INTERFACE)
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            ANDROID_AUTO_REQUEST_INTERFACE,
            "patch_getRequestedMediaId",
        ),
        registerCount = 1,
        instructions = """
            iget-object p0, p0, $requestedMediaIdHolderField
            iget-object p0, p0, $requestedMediaIdField
            return-object p0
        """,
    )
    // The second argument is optional browsing metadata. YTM also passes null when sending a bare list.
    val usesTwoArgumentDeliveryMethod = deliverAndroidAutoMediaItemsMethod.parameterTypes.size == 2
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            ANDROID_AUTO_REQUEST_INTERFACE,
            "patch_sendPlaylists",
        ),
        registerCount = if (usesTwoArgumentDeliveryMethod) 3 else 2,
        instructions = if (usesTwoArgumentDeliveryMethod) {
            """
                const/4 v0, 0x0
                invoke-virtual { p0, p1, v0 }, $deliverAndroidAutoMediaItemsMethod
                return-void
            """
        } else {
            """
                invoke-virtual { p0, p1 }, $deliverAndroidAutoMediaItemsMethod
                return-void
            """
        },
    )
}

private fun BytecodePatchContext.hookAndroidAutoRequest(
    androidAutoRequestHandlerType: String,
    androidAutoRequestType: String,
) {
    val handleAndroidAutoRequestMethod = mutableClassDefBy(
        androidAutoRequestHandlerType,
    ).methods.single { method ->
        method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(androidAutoRequestType)
    }
    val handledRegister = handleAndroidAutoRequestMethod.findFreeRegister(0)

    handleAndroidAutoRequestMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handlePlaylistsRequest($ANDROID_AUTO_REQUEST_INTERFACE)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", handleAndroidAutoRequestMethod.getInstruction<Instruction>(0)),
    )
}

private fun BytecodePatchContext.patchBrowseService() {
    val getGridItemsMethod = GridItemsFingerprint.originalMethod
    val getGridContinuationActionsMethod = gridContinuationActionsFingerprint(
        getGridItemsMethod,
    ).originalMethod
    val createBrowseRequestMethod = findBrowseRequestFactory()
    val browseRequestType = createBrowseRequestMethod.returnType
    val browseServiceType = createBrowseRequestMethod.definingClass
    val createPaginationRequestMethod = findContinuationRequestFactory(
        browseServiceType, browseRequestType, getGridContinuationActionsMethod,
    )
    val browseRequestSenderFingerprint = sendBrowseRequestFingerprint(
        browseServiceType,
        browseRequestType,
    )
    val sendBrowseRequestMethod = browseRequestSenderFingerprint.originalMethod
    val requestBrowseIdField = browseRequestSenderFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val clickTrackingParamsSetterMethod = findClickTrackingSetter(browseRequestType)
    val setRequestBrowseIdMethod = setRequestBrowseIdFingerprint(
        requestBrowseIdField,
    ).originalMethod
    val browseServiceClass = mutableClassDefBy(browseServiceType)
    browseServiceClass.interfaces.add(BROWSE_SERVICE_INTERFACE)
    browseServiceClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_SERVICE_INTERFACE,
            "patch_browse",
        ),
        registerCount = 5,
        instructions = """
            invoke-virtual { p0 }, $createBrowseRequestMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $setRequestBrowseIdMethod
            # YTM rejects null clickTrackingParams, so pass an empty byte array.
            const/4 v1, 0x0
            new-array v1, v1, [B
            invoke-virtual { v0, v1 }, $clickTrackingParamsSetterMethod
            invoke-virtual { p0, v0, p2 }, $sendBrowseRequestMethod
            move-result-object v0
            return-object v0
        """,
    )
    val continuationActionType = createPaginationRequestMethod
        .parameterTypes.single().toString()
    browseServiceClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_SERVICE_INTERFACE,
            "patch_continueBrowse",
        ),
        registerCount = 3,
        instructions = """
            check-cast p1, $continuationActionType
            invoke-virtual { p0, p1 }, $createPaginationRequestMethod
            move-result-object p1
            invoke-virtual { p0, p1, p2 }, $sendBrowseRequestMethod
            move-result-object p1
            return-object p1
        """,
    )

    captureBrowseService(browseServiceType)
}

private fun BytecodePatchContext.findBrowseRequestFactory(): MethodReference {
    val fromEndpoint = BrowseRequestFromEndpointFingerprint.originalMethod
    return fromEndpoint.instructions.asSequence()
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.parameterTypes.isEmpty() && it.returnType == fromEndpoint.returnType }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the Browse request factory")
}

private fun BytecodePatchContext.findContinuationRequestFactory(
    serviceType: String,
    requestType: String,
    getContinuations: Method,
): Method {
    // The grid reader unwraps NEXT and RELOAD into the command type this factory accepts.
    val commandTypes = getContinuations.instructions.asSequence()
        .mapNotNull { it.getReference<MethodReference>()?.returnType }
        .toSet()
    return classDefBy(serviceType).methods.singleOrNull {
        it.returnType == requestType &&
            it.parameterTypes.singleOrNull()?.toString() in commandTypes
    } ?: throw PatchException("Could not resolve the Library continuation request factory")
}

private fun BytecodePatchContext.findClickTrackingSetter(requestType: String): Method =
    generateSequence(classDefBy(requestType)) { classDef ->
        classDef.superclass?.let { classDefByOrNull(it) }
    }.flatMap { it.methods.asSequence() }
        // Newer YTM versions also expose a public byte[] overload; the inherited setter is stable.
        .firstOrNull {
            AccessFlags.PROTECTED.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("[B")
        } ?: throw PatchException("Could not resolve the click tracking parameter setter")

private fun BytecodePatchContext.captureBrowseService(browseServiceType: String) {
    val browseServiceProviderCandidates =
        browseServiceProviderFingerprint(browseServiceType)
            .matchAll()
            .map { match ->
                val (providerFieldMatch, providerGetMatch) = match.instructionMatches
                val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
                val providerGetMethod = providerGetMatch.instruction.getReference<MethodReference>()!!
                providerField to providerGetMethod
            }
            .distinctBy { (field, _) -> field }
    // The provider belongs to the generated component read during MusicBrowserService.onCreate.
    val (onCreateMatch, providerField, providerGetMethod) = browseServiceProviderCandidates
        .mapNotNull { (providerField, providerGetMethod) ->
            musicBrowserServiceSuperclassOnCreateFingerprint(
                MUSIC_BROWSER_SERVICE_CLASS,
                providerField.definingClass,
            ).matchOrNull()?.let { match ->
                Triple(match, providerField, providerGetMethod)
            }
        }
        .singleOrNull()
        ?: throw PatchException(
            "Could not resolve MusicBrowserService's BS_GET_BROWSE_DATA provider",
        )
    val onCreateMethod = onCreateMatch.originalMethod
    val mutableOnCreateMethod = mutableClassDefBy(
        onCreateMethod.definingClass,
    ).findMutableMethodOf(onCreateMethod)
    val generatedComponentReadIndex = onCreateMatch.instructionMatches.single { match ->
        val field = match.instruction.getReference<FieldReference>()
        match.instruction.opcode == Opcode.IGET_OBJECT &&
            field?.type == providerField.definingClass
    }.index
    val generatedComponentRegister = mutableOnCreateMethod
        .getInstruction<TwoRegisterInstruction>(generatedComponentReadIndex)
        .registerA
    val browseServiceRegister = mutableOnCreateMethod.findFreeRegister(
        generatedComponentReadIndex + 1,
        mutableOnCreateMethod.p0Register,
    )
    mutableOnCreateMethod.addInstructions(
        generatedComponentReadIndex + 1,
        """
            iget-object v$browseServiceRegister, v$generatedComponentRegister, $providerField
            invoke-interface/range { v$browseServiceRegister .. v$browseServiceRegister }, $providerGetMethod
            move-result-object v$browseServiceRegister
            check-cast v$browseServiceRegister, $BROWSE_SERVICE_INTERFACE
            invoke-static/range { v$browseServiceRegister .. v$browseServiceRegister }, $EXTENSION_CLASS->setBrowseService($BROWSE_SERVICE_INTERFACE)V
        """,
    )
}

/** Connects YTM's tab, section, and grid containers to the Java Library reader. */
private fun BytecodePatchContext.patchBrowseResponses() {
    val getTabsMethod = BrowseResponseTabsFingerprint.originalMethod
    addBrowseResponseInterface(getTabsMethod)
    addBrowseContentInterfaces(getTabsMethod)
    addLibraryGridInterface()
    addPlaylistSongsInterface()
}

private fun BytecodePatchContext.addBrowseContentInterfaces(getTabsMethod: Method) {
    val tabMapperMatch = BrowseResponseTabsFingerprint.instructionMatches.single { match ->
        match.instruction.opcode == Opcode.NEW_INSTANCE
    }
    val tabMapperType = tabMapperMatch
        .instruction
        .getReference<TypeReference>()!!
        .type
    val tabWrapperMatch = createBrowseTabFingerprint(
        tabMapperType,
    ).instructionMatches.single { match ->
        match.instruction.opcode == Opcode.NEW_INSTANCE
    }
    val tabWrapperType = tabWrapperMatch
        .instruction
        .getReference<TypeReference>()!!
        .type
    val getSectionListMethod = getSectionListFingerprint(tabWrapperType).originalMethod
    val getSectionContentsMethod = sectionListContentsFingerprint(
        getSectionListMethod.returnType,
        getTabsMethod.returnType,
    ).originalMethod
    addBrowseTabInterface(getSectionListMethod)
    addSectionListInterface(getSectionContentsMethod)
}

private fun BytecodePatchContext.addLibraryGridInterface() {
    val getItemsMethod = GridItemsFingerprint.originalMethod
    val getContinuationsMethod = gridContinuationActionsFingerprint(getItemsMethod).originalMethod
    makePublic(getItemsMethod, getContinuationsMethod)
    addGridInterface(getItemsMethod, getContinuationsMethod)
}

private fun BytecodePatchContext.addPlaylistSongsInterface() {
    val getSongsMethod = openedPlaylistSongsFingerprint(playlistOrSongType()).originalMethod
    makePublic(getSongsMethod)
    addSongListInterface(getSongsMethod)
}

private fun BytecodePatchContext.makePublic(vararg methods: Method) {
    // The new interface methods call these readers from other YTM classes.
    methods.forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }
}

private fun BytecodePatchContext.addPaginatedLibraryGridDecoder(
    decodePaginatedLibraryGridMethod: Method,
): Method {
    val clonedDecoderMethod = decodePaginatedLibraryGridMethod.cloneMutable(
        name = "patch_decodePaginatedLibraryGrid",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The Library grid decoder never reads p0 (this). Keep that slot in the static copy
        // so p1 still contains the response.
        parameters = listOf(
            ImmutableMethodParameter(decodePaginatedLibraryGridMethod.definingClass, null, null),
        ) + decodePaginatedLibraryGridMethod.parameters,
    )
    mutableClassDefBy(decodePaginatedLibraryGridMethod.definingClass).methods.add(
        clonedDecoderMethod,
    )
    return clonedDecoderMethod
}

private fun BytecodePatchContext.addBrowseResponseInterface(getTabsMethod: Method) {
    val decodePaginatedLibraryGridMethod = LibraryPaginationDecoderFingerprint.originalMethod
    val getPaginationResponseMethod = classDefBy(
        getTabsMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == decodePaginatedLibraryGridMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the Library pagination response method")
    val paginatedLibraryGridDecoderMethod = addPaginatedLibraryGridDecoder(
        decodePaginatedLibraryGridMethod,
    )
    val browseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    browseResponseClass.interfaces.add(BROWSE_RESPONSE_INTERFACE)
    addOpenedPlaylistPlayableMediaIdGetter(
        browseResponseClass,
        getTabsMethod,
    )
    browseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_RESPONSE_INTERFACE,
            "patch_getTabs",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getTabsMethod
            move-result-object p0
            return-object p0
        """,
    )
    browseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_RESPONSE_INTERFACE,
            "patch_getContinuationGrid",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $getPaginationResponseMethod
            move-result-object p0
            # The cloned decoder ignores its first argument and reads the response from its second.
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $paginatedLibraryGridDecoderMethod
            move-result-object p0
            check-cast p0, $GRID_INTERFACE
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addOpenedPlaylistPlayableMediaIdGetter(
    browseResponseClass: MutableClass,
    getTabsMethod: Method,
) {
    val encodeMediaIdMethod = EncodeMediaIdFingerprint.originalMethod
    val playActionType = encodeMediaIdMethod.parameterTypes.single().toString()
    val playButtonExtensionInitializer = playButtonRendererFingerprint(
        playActionType,
    ).originalMethod
    val buttonRendererType = playButtonExtensionInitializer.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val playlistHeaderType = playButtonExtensionInitializer.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .first { field -> field.definingClass == field.type }
        .type
    val playButtonExtensionField = playButtonExtensionInitializer.instructions
        .first { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .getReference<FieldReference>()!!
    val decodePlayButtonMethod = decodeButtonRendererFingerprint(
        playlistHeaderType,
        buttonRendererType,
        playButtonExtensionField,
    ).originalMethod
    val playActionField = buttonRendererActionCopyFingerprint(
        buttonRendererType,
        playActionType,
    ).matchAll()
        .map { match ->
            val (playButtonActionReadMatch, _) = match.instructionMatches
            playButtonActionReadMatch.instruction.getReference<FieldReference>()!!
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the opened-playlist header Play action")

    val browseResponseProtoField = getTabsMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field ->
            field.definingClass == getTabsMethod.definingClass &&
                field.type != getTabsMethod.returnType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the Browse response message field")
    // Opened-playlist field q contains the ButtonRenderer used by its Play button.
    val playlistHeaderContentField =
        classDefBy(browseResponseProtoField.type).fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) &&
                field.name == PLAYLIST_HEADER_FIELD_NAME &&
                field.type == playlistHeaderType
        } ?: throw PatchException("Could not resolve the playlist header content field")

    browseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_RESPONSE_INTERFACE,
            "patch_getPlayMediaId",
        ),
        registerCount = 2,
        instructions = """
            iget-object p0, p0, $browseResponseProtoField
            iget-object p0, p0, $playlistHeaderContentField
            # true selects ButtonRenderer protobuf field 65153809.
            const/4 v0, 0x1
            invoke-static { v0, p0 }, $decodePlayButtonMethod
            move-result-object p0
            if-eqz p0, :no_playable_media_id
            iget-object p0, p0, $playActionField
            if-eqz p0, :no_playable_media_id
            invoke-static { p0 }, $encodeMediaIdMethod
            move-result-object p0
            return-object p0
            :no_playable_media_id
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addBrowseTabInterface(
    getSectionListMethod: Method,
) {
    val browseTabClass = mutableClassDefBy(getSectionListMethod.definingClass)
    browseTabClass.interfaces.add(BROWSE_TAB_INTERFACE)
    browseTabClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            BROWSE_TAB_INTERFACE,
            "patch_getSectionList",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionListMethod
            move-result-object p0
            check-cast p0, $SECTION_LIST_INTERFACE
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addSectionListInterface(
    getSectionContentsMethod: Method,
) {
    val sectionListClass = mutableClassDefBy(getSectionContentsMethod.definingClass)
    sectionListClass.interfaces.add(SECTION_LIST_INTERFACE)
    sectionListClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            SECTION_LIST_INTERFACE,
            "patch_getContents",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionContentsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addGridInterface(
    getItemsMethod: Method,
    getContinuationActionsMethod: Method,
) {
    val gridType = getItemsMethod.parameterTypes.single().toString()
    val gridClass = mutableClassDefBy(gridType)
    gridClass.interfaces.add(GRID_INTERFACE)
    gridClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            GRID_INTERFACE,
            "patch_getItems",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
    gridClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            GRID_INTERFACE,
            "patch_getContinuationActions",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getContinuationActionsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addSongListInterface(
    getSongsMethod: Method,
) {
    val openedPlaylistSongsType = getSongsMethod.parameterTypes.first().toString()
    val openedPlaylistSongsClass = mutableClassDefBy(openedPlaylistSongsType)
    openedPlaylistSongsClass.interfaces.add(SONG_LIST_INTERFACE)
    openedPlaylistSongsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            SONG_LIST_INTERFACE,
            "patch_getSongs",
        ),
        registerCount = 2,
        instructions = """
            const/4 v0, 0x0
            # false returns the opened playlist's songs from protobuf field 161429595.
            invoke-static { p0, v0 }, $getSongsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.patchPlaylistItems() {
    val playlistOrSongType = playlistOrSongType()
    val browseEndpointBrowseIdField = BrowseRequestFromEndpointFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    val encodeMediaIdMethod = EncodeMediaIdFingerprint.originalMethod
    val playlistOrSongFields = classDefBy(playlistOrSongType).fields.toList()

    fun playlistOrSongField(name: String) = playlistOrSongFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkContainerField = playlistOrSongField(ARTWORK_CONTAINER_FIELD_NAME)
    val titleField = playlistOrSongField(TITLE_FIELD_NAME)
    val subtitleField = playlistOrSongField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected playlist or song metadata fields")
    }
    val formatTextMethod = formatTextFingerprint(titleField.type).originalMethod

    val playlistOrSongActionType = encodeMediaIdMethod.parameterTypes.single().toString()
    // YTM does not keep playlist and song actions in separate fields.
    val actionFieldI = playlistOrSongField("i")
    val actionFieldK = playlistOrSongField("k")
    if (actionFieldI.type != playlistOrSongActionType ||
        actionFieldK.type != playlistOrSongActionType
    ) {
        throw PatchException(
            "Playlist-or-song fields i and k do not have the expected action type",
        )
    }

    val actionToBrowseEndpointMethod = browseEndpointFromActionFingerprint(
        playlistOrSongActionType,
        browseEndpointBrowseIdField.definingClass,
    ).originalMethod

    val playlistOrSongClass = mutableClassDefBy(playlistOrSongType)
    playlistOrSongClass.interfaces.add(PLAYLIST_OR_SONG_INTERFACE)
    playlistOrSongClass.addPlaylistBrowseIdGetter(
        extensionInterfaceMethod(PLAYLIST_OR_SONG_INTERFACE, "patch_getBrowseId"),
        actionFieldI,
        actionFieldK,
        actionToBrowseEndpointMethod,
        browseEndpointBrowseIdField,
    )
    playlistOrSongClass.addPlayableMediaIdGetter(
        extensionInterfaceMethod(PLAYLIST_OR_SONG_INTERFACE, "patch_getPlayMediaId"),
        actionFieldI,
        actionFieldK,
        encodeMediaIdMethod,
    )
    playlistOrSongClass.addTextGetter(
        extensionInterfaceMethod(PLAYLIST_OR_SONG_INTERFACE, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    playlistOrSongClass.addTextGetter(
        extensionInterfaceMethod(PLAYLIST_OR_SONG_INTERFACE, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    addArtworkUriGetter(playlistOrSongClass, artworkContainerField)
}

private fun MutableClass.addPlaylistBrowseIdGetter(
    interfaceMethod: Method,
    actionFieldI: FieldReference,
    actionFieldK: FieldReference,
    actionToBrowseEndpointMethod: Method,
    browseEndpointBrowseIdField: FieldReference,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            # Accept a VL Browse ID from either field, but reject conflicting IDs.
            # BrowseEndpoint uses protobuf's empty String when its ID is absent.
            const/4 v0, 0x0
            iget-object v1, p0, $actionFieldI
            if-eqz v1, :try_for_browse_id
            invoke-static { v1 }, $actionToBrowseEndpointMethod
            move-result-object v1
            iget-object v1, v1, $browseEndpointBrowseIdField
            const-string v2, "$PLAYLIST_BROWSE_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :try_for_browse_id
            move-object v0, v1

            :try_for_browse_id
            iget-object v1, p0, $actionFieldK
            if-eqz v1, :return_browse_id
            invoke-static { v1 }, $actionToBrowseEndpointMethod
            move-result-object v1
            iget-object v1, v1, $browseEndpointBrowseIdField
            const-string v2, "$PLAYLIST_BROWSE_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :return_browse_id
            if-eqz v0, :use_browse_id
            invoke-virtual { v0, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :return_browse_id
            const/4 v0, 0x0
            return-object v0

            :use_browse_id
            move-object v0, v1
            :return_browse_id
            return-object v0
        """,
    )
}

private fun MutableClass.addPlayableMediaIdGetter(
    interfaceMethod: Method,
    actionFieldI: FieldReference,
    actionFieldK: FieldReference,
    encodeMediaIdMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 2,
        instructions = """
            # YTM's row-action selector reads field i and only uses k when i is absent.
            iget-object v0, p0, $actionFieldI
            if-eqz v0, :try_for_playable_id
            invoke-static { v0 }, $encodeMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            goto :return_playable_media_id

            :try_for_playable_id
            iget-object v0, p0, $actionFieldK
            if-nez v0, :create_playable_media_id
            # Android still treats v0 as a command object here, even though it is null.
            # Resetting it avoids a String return-type verification error on YTM 9.15.51.
            const/4 v0, 0x0
            goto :return_playable_media_id

            :create_playable_media_id
            invoke-static { v0 }, $encodeMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            :return_playable_media_id
            return-object v0
        """,
    )
}

private fun MutableClass.addTextGetter(
    interfaceMethod: Method,
    textField: FieldReference,
    formatTextMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $textField
            # null keeps YTM's normal formatting without adding a TTS span.
            const/4 v1, 0x0
            invoke-static { v0, v1 }, $formatTextMethod
            move-result-object v0
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.addArtworkUriGetter(
    playlistOrSongClass: MutableClass,
    artworkContainerField: FieldReference,
) {
    // Use the same thumbnail-to-Uri conversion as YTM's Android Auto media descriptions.
    val artworkPayloadType = PlaylistOrSongThumbnailFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val artworkPayloadFields = classDefBy(artworkPayloadType).fields
        .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
        .toList()
    val decodeArtworkPayloadMethod = decodeThumbnailFingerprint(
        artworkContainerField.type,
    ).originalMethod
    val androidAutoMediaDescriptionMethod = androidAutoMediaDescriptionFingerprint(
        artworkPayloadFields.map(FieldReference::getType).toSet(),
    ).originalMethod
    val androidAutoArtworkFieldTypes = androidAutoMediaDescriptionMethod.instructions
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
        .toSet()
    val createArtworkUriMethod = androidAutoMediaDescriptionMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { method ->
            method.returnType == "Landroid/net/Uri;" && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().toString() in androidAutoArtworkFieldTypes
        }
        .singleOrNull { method ->
            artworkPayloadFields.any { field ->
                field.type == method.parameterTypes.single().toString()
            }
        }
        ?: throw PatchException("Could not resolve YTM's Android Auto artwork Uri method")
    val thumbnailField = artworkPayloadFields.singleOrNull { field ->
        field.type == createArtworkUriMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the thumbnail details field")
    playlistOrSongClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(PLAYLIST_OR_SONG_INTERFACE, "patch_getArtworkUri"),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $artworkContainerField
            invoke-static { v0 }, $decodeArtworkPayloadMethod
            move-result-object v0
            if-eqz v0, :no_artwork
            check-cast v0, ${thumbnailField.definingClass}
            iget-object v0, v0, $thumbnailField
            invoke-static { v0 }, $createArtworkUriMethod
            move-result-object v0
            return-object v0
            :no_artwork
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

private fun BytecodePatchContext.playlistOrSongType() = PlaylistOrSongFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type

private fun BytecodePatchContext.extensionInterfaceMethod(
    interfaceType: String,
    name: String,
) = classDefBy(interfaceType).methods.singleOrNull { method -> method.name == name }
    ?: throw PatchException("Could not resolve $name in $interfaceType")

private fun MutableClass.addInterfaceMethod(
    interfaceMethod: Method,
    registerCount: Int,
    instructions: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            interfaceMethod.name,
            interfaceMethod.parameters.map { parameter ->
                ImmutableMethodParameter(parameter.type, null, null)
            },
            interfaceMethod.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registerCount),
        ).toMutable().apply {
            addInstructions(0, instructions)
        },
    )
}
