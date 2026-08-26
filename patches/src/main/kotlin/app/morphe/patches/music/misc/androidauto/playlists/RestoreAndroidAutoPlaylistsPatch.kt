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

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch;"
private const val EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PhoneBrowseRequests;"
private const val EXTENSION_BROWSE_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$BrowseResponse;"
private const val EXTENSION_BROWSE_TAB_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$BrowseTab;"
private const val EXTENSION_SECTION_LIST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$SectionList;"
private const val EXTENSION_GRID_RENDERER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$GridRenderer;"
private const val EXTENSION_OPENED_PLAYLIST_SONGS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$OpenedPlaylistSongs;"
private const val EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$AndroidAutoPlaylistsRequest;"
private const val EXTENSION_PLAYLIST_OR_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch$PlaylistOrTrack;"
private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

// Protobuf field 161429595 stores its artwork container, title, and subtitle in c, g, and h.
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
        hookPlaylistsTitleMediaIds()
        patchPhoneBrowseRequests()
        patchPhoneBrowseResponses()
        patchPlaylistOrTrack()
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

private fun BytecodePatchContext.capturePhoneBrowseRequests(phoneBrowseRequestsType: String) {
    val phoneBrowseRequestsProviderCandidates =
        phoneBrowseRequestsProviderFingerprint(phoneBrowseRequestsType)
            .matchAll()
            .map { match ->
                val (providerFieldMatch, providerGetMatch) = match.instructionMatches
                val providerField = providerFieldMatch.instruction.getReference<FieldReference>()!!
                val providerGetMethod = providerGetMatch.instruction.getReference<MethodReference>()!!
                providerField to providerGetMethod
            }
            .distinctBy { (field, _) -> field }
    // The provider belongs to the generated component read during MusicBrowserService.onCreate.
    val (onCreateMatch, providerField, providerGetMethod) = phoneBrowseRequestsProviderCandidates
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
    val phoneBrowseRequestsRegister = mutableOnCreateMethod.findFreeRegister(
        generatedComponentReadIndex + 1,
        mutableOnCreateMethod.p0Register,
    )
    mutableOnCreateMethod.addInstructions(
        generatedComponentReadIndex + 1,
        """
            iget-object v$phoneBrowseRequestsRegister, v$generatedComponentRegister, $providerField
            invoke-interface/range { v$phoneBrowseRequestsRegister .. v$phoneBrowseRequestsRegister }, $providerGetMethod
            move-result-object v$phoneBrowseRequestsRegister
            check-cast v$phoneBrowseRequestsRegister, $EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE
            invoke-static/range { v$phoneBrowseRequestsRegister .. v$phoneBrowseRequestsRegister }, $EXTENSION_CLASS->setPhoneBrowseRequests($EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE)V
        """,
    )
}

private fun BytecodePatchContext.patchPhoneBrowseResponses() {
    val getTabsMethod = BrowseResponseTabsFingerprint.originalMethod
    val getGridRowsMethod = GridRendererRowsFingerprint.originalMethod
    val getGridContinuationActionsMethod = gridContinuationActionsFingerprint(
        getGridRowsMethod,
    ).originalMethod
    val playlistOrTrackType = PlaylistOrTrackFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val createPlayableMediaIdMethod = CreatePlayableMediaIdFingerprint.originalMethod
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
    val getOpenedPlaylistSongsMethod = openedPlaylistSongsFingerprint(
        playlistOrTrackType,
    ).originalMethod
    val decodePaginatedLibraryGridMethod = LibraryPaginationDecoderFingerprint.originalMethod
    val getPaginationResponseMethod = classDefBy(
        getTabsMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == decodePaginatedLibraryGridMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the Library pagination response method")
    // These methods need public visibility because injected interface methods call them
    // from other classes.
    listOf(
        getGridRowsMethod,
        getOpenedPlaylistSongsMethod,
        getGridContinuationActionsMethod,
    ).forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }

    val paginatedLibraryGridDecoderMethod = addPaginatedLibraryGridDecoder(
        decodePaginatedLibraryGridMethod,
    )
    addBrowseResponseInterface(
        getTabsMethod,
        getPaginationResponseMethod,
        paginatedLibraryGridDecoderMethod,
        createPlayableMediaIdMethod,
    )
    addBrowseTabInterface(getSectionListMethod)
    addSectionListInterface(getSectionContentsMethod)
    addGridRendererInterface(getGridRowsMethod, getGridContinuationActionsMethod)
    addOpenedPlaylistSongsInterface(getOpenedPlaylistSongsMethod)
}

private fun BytecodePatchContext.addBrowseResponseInterface(
    getTabsMethod: Method,
    getPaginationResponseMethod: Method,
    paginatedLibraryGridDecoderMethod: Method,
    createPlayableMediaIdMethod: Method,
) {
    val browseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    browseResponseClass.interfaces.add(EXTENSION_BROWSE_RESPONSE_INTERFACE)
    addOpenedPlaylistPlayableMediaIdGetter(
        browseResponseClass,
        getTabsMethod,
        createPlayableMediaIdMethod,
    )
    browseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_BROWSE_RESPONSE_INTERFACE,
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
            EXTENSION_BROWSE_RESPONSE_INTERFACE,
            "patch_getMorePlaylists",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $getPaginationResponseMethod
            move-result-object p0
            # The cloned decoder ignores its first argument and reads the response from its second.
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $paginatedLibraryGridDecoderMethod
            move-result-object p0
            check-cast p0, $EXTENSION_GRID_RENDERER_INTERFACE
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addBrowseTabInterface(
    getSectionListMethod: Method,
) {
    val browseTabClass = mutableClassDefBy(getSectionListMethod.definingClass)
    browseTabClass.interfaces.add(EXTENSION_BROWSE_TAB_INTERFACE)
    browseTabClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_BROWSE_TAB_INTERFACE,
            "patch_getSectionList",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getSectionListMethod
            move-result-object p0
            check-cast p0, $EXTENSION_SECTION_LIST_INTERFACE
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addSectionListInterface(
    getSectionContentsMethod: Method,
) {
    val sectionListClass = mutableClassDefBy(getSectionContentsMethod.definingClass)
    sectionListClass.interfaces.add(EXTENSION_SECTION_LIST_INTERFACE)
    sectionListClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_SECTION_LIST_INTERFACE,
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

private fun BytecodePatchContext.addGridRendererInterface(
    getRowsMethod: Method,
    getContinuationActionsMethod: Method,
) {
    val gridRendererType = getRowsMethod.parameterTypes.single().toString()
    val gridRendererClass = mutableClassDefBy(gridRendererType)
    gridRendererClass.interfaces.add(EXTENSION_GRID_RENDERER_INTERFACE)
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
            "patch_getRows",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getRowsMethod
            move-result-object p0
            return-object p0
        """,
    )
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
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

private fun BytecodePatchContext.addOpenedPlaylistSongsInterface(
    getSongsMethod: Method,
) {
    val openedPlaylistSongsType = getSongsMethod.parameterTypes.first().toString()
    val openedPlaylistSongsClass = mutableClassDefBy(openedPlaylistSongsType)
    openedPlaylistSongsClass.interfaces.add(EXTENSION_OPENED_PLAYLIST_SONGS_INTERFACE)
    openedPlaylistSongsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_OPENED_PLAYLIST_SONGS_INTERFACE,
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

private fun BytecodePatchContext.addOpenedPlaylistPlayableMediaIdGetter(
    browseResponseClass: MutableClass,
    getTabsMethod: Method,
    createPlayableMediaIdMethod: Method,
) {
    val playActionType = createPlayableMediaIdMethod.parameterTypes.single().toString()
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
            EXTENSION_BROWSE_RESPONSE_INTERFACE,
            "patch_getPlayableMediaId",
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
            invoke-static { p0 }, $createPlayableMediaIdMethod
            move-result-object p0
            return-object p0
            :no_playable_media_id
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.addPaginatedLibraryGridDecoder(
    decodePaginatedLibraryGridMethod: Method,
): Method {
    val clonedDecoderMethod = decodePaginatedLibraryGridMethod.cloneMutable(
        name = "patch_decodePaginatedLibraryGrid",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The decoder reads the response from p1. Keep an unused first parameter so the copied
        // bytecode retains the same register layout.
        parameters = listOf(
            ImmutableMethodParameter(decodePaginatedLibraryGridMethod.definingClass, null, null),
        ) + decodePaginatedLibraryGridMethod.parameters,
    )
    mutableClassDefBy(decodePaginatedLibraryGridMethod.definingClass).methods.add(
        clonedDecoderMethod,
    )
    return clonedDecoderMethod
}

private fun BytecodePatchContext.patchPlaylistOrTrack() {
    val playlistOrTrackType = PlaylistOrTrackFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val browseEndpointBrowseIdField = BrowseRequestFromEndpointFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    val createPlayableMediaIdMethod = CreatePlayableMediaIdFingerprint.originalMethod
    val playlistOrTrackFields = classDefBy(playlistOrTrackType).fields.toList()

    fun playlistOrTrackField(name: String) = playlistOrTrackFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkContainerField = playlistOrTrackField(ARTWORK_CONTAINER_FIELD_NAME)
    val titleField = playlistOrTrackField(TITLE_FIELD_NAME)
    val subtitleField = playlistOrTrackField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected playlist or track metadata fields")
    }
    val artworkPayloadType = PlaylistOrTrackThumbnailFingerprint.instructionMatches
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
    val formatTextMethod = formatTextFingerprint(titleField.type).originalMethod

    val playlistOrTrackActionType = createPlayableMediaIdMethod.parameterTypes.single().toString()
    // Fields i and k both have YTM's tap-action type; neither is specific to playlists or songs.
    val actionFieldI = playlistOrTrackField("i")
    val actionFieldK = playlistOrTrackField("k")
    if (actionFieldI.type != playlistOrTrackActionType ||
        actionFieldK.type != playlistOrTrackActionType
    ) {
        throw PatchException(
            "Playlist-or-track fields i and k do not have the expected action type",
        )
    }

    val actionToBrowseEndpointMethod = browseEndpointFromActionFingerprint(
        playlistOrTrackActionType,
        browseEndpointBrowseIdField.definingClass,
    ).originalMethod

    val playlistOrTrackClass = mutableClassDefBy(playlistOrTrackType)
    playlistOrTrackClass.interfaces.add(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE)
    playlistOrTrackClass.addPlaylistBrowseIdGetter(
        extensionInterfaceMethod(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE, "patch_getPlaylistBrowseId"),
        actionFieldI,
        actionFieldK,
        actionToBrowseEndpointMethod,
        browseEndpointBrowseIdField,
    )
    playlistOrTrackClass.addPlayableMediaIdGetter(
        extensionInterfaceMethod(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE, "patch_getPlayableMediaId"),
        actionFieldI,
        actionFieldK,
        createPlayableMediaIdMethod,
    )
    playlistOrTrackClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    playlistOrTrackClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    playlistOrTrackClass.addArtworkUriGetter(
        extensionInterfaceMethod(EXTENSION_PLAYLIST_OR_TRACK_INTERFACE, "patch_getArtworkUri"),
        artworkContainerField,
        decodeArtworkPayloadMethod,
        thumbnailField,
        createArtworkUriMethod,
    )
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
    createPlayableMediaIdMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 2,
        instructions = """
            # YTM's row-action selector reads field i and only uses k when i is absent.
            iget-object v0, p0, $actionFieldI
            if-eqz v0, :try_for_playable_id
            invoke-static { v0 }, $createPlayableMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            goto :return_playable_media_id

            :try_for_playable_id
            iget-object v0, p0, $actionFieldK
            if-nez v0, :create_playable_media_id
            # ART on 9.15.51 otherwise merges this action-typed null with String results as Object.
            const/4 v0, 0x0
            goto :return_playable_media_id

            :create_playable_media_id
            invoke-static { v0 }, $createPlayableMediaIdMethod
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

private fun MutableClass.addArtworkUriGetter(
    interfaceMethod: Method,
    artworkContainerField: FieldReference,
    decodeArtworkPayloadMethod: Method,
    thumbnailField: FieldReference,
    createArtworkUriMethod: MethodReference,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
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

private fun BytecodePatchContext.patchPhoneBrowseRequests() {
    val getGridRowsMethod = GridRendererRowsFingerprint.originalMethod
    val getGridContinuationActionsMethod = gridContinuationActionsFingerprint(
        getGridRowsMethod,
    ).originalMethod
    val browseRequestFromEndpointMethod = BrowseRequestFromEndpointFingerprint.originalMethod
    val browseRequestType = browseRequestFromEndpointMethod.returnType
    val createBrowseRequestMethod = browseRequestFromEndpointMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { reference ->
            reference.parameterTypes.isEmpty() &&
                reference.returnType == browseRequestType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the method that creates a Browse request")
    val phoneBrowseRequestsType = createBrowseRequestMethod.definingClass
    // NEXT and RELOAD share the type accepted by the Library pagination request method.
    val continuationReaderReturnTypes =
        getGridContinuationActionsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val createPaginationRequestMethod = classDefBy(
        phoneBrowseRequestsType,
    ).methods.singleOrNull { method ->
        method.returnType == browseRequestType &&
            method.parameterTypes.singleOrNull()?.toString() in continuationReaderReturnTypes
    }
        ?: throw PatchException("Could not resolve the Library pagination request method")
    val browseRequestSenderFingerprint = sendBrowseRequestFingerprint(
        phoneBrowseRequestsType,
        browseRequestType,
    )
    val sendBrowseRequestMethod = browseRequestSenderFingerprint.originalMethod
    val requestBrowseIdField = browseRequestSenderFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val browseRequestMethods = generateSequence(
        classDefBy(browseRequestType),
    ) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clickTrackingParamsSetterMethod = browseRequestMethods
        // 9.32.51 and 9.33.52 add a public byte[] overload; the protected setter still matches.
        .firstOrNull { method ->
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
        ?: throw PatchException("Could not resolve the click tracking parameter setter")
    val setRequestBrowseIdMethod = setRequestBrowseIdFingerprint(
        requestBrowseIdField,
    ).originalMethod
    val phoneBrowseRequestsClass = mutableClassDefBy(phoneBrowseRequestsType)
    phoneBrowseRequestsClass.interfaces.add(EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE)
    phoneBrowseRequestsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE,
            "patch_requestBrowse",
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
    phoneBrowseRequestsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE,
            "patch_requestMorePlaylists",
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

    capturePhoneBrowseRequests(phoneBrowseRequestsType)
}

private fun BytecodePatchContext.patchAndroidAutoPlaylists() {
    val sendEmptyAndroidAutoMediaItemsMethod = SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod
    addAndroidAutoPlaylistsRequestInterface(sendEmptyAndroidAutoMediaItemsMethod)
    hookAndroidAutoPlaylistsRequest(
        sendEmptyAndroidAutoMediaItemsMethod.definingClass,
        sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString(),
    )
}

private fun BytecodePatchContext.addAndroidAutoPlaylistsRequestInterface(
    sendEmptyAndroidAutoMediaItemsMethod: Method,
) {
    val androidAutoRequestType = sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    val androidAutoRequestFields = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .toList()
    // YTM reads this nested String when deciding that Android Auto's requested media ID is invalid.
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

    androidAutoRequestClass.interfaces.add(EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE)
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE,
            "patch_getRequestedMediaId",
        ),
        registerCount = 1,
        instructions = """
            iget-object p0, p0, $requestedMediaIdHolderField
            iget-object p0, p0, $requestedMediaIdField
            return-object p0
        """,
    )
    // On YTM 9.15.51, b(List) forwards to c(List, null).
    val usesTwoArgumentDeliveryMethod = deliverAndroidAutoMediaItemsMethod.parameterTypes.size == 2
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE,
            "patch_deliverAndroidAutoPlaylists",
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

private fun BytecodePatchContext.hookAndroidAutoPlaylistsRequest(
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
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleAndroidAutoPlaylists($EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", handleAndroidAutoRequestMethod.getInstruction<Instruction>(0)),
    )
}
