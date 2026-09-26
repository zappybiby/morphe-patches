/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch;"
private const val EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseRequests;"
private const val EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseResponse;"
private const val EXTENSION_PHONE_BROWSE_TAB_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseTab;"
private const val EXTENSION_SECTION_LIST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$SectionList;"
private const val EXTENSION_GRID_RENDERER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$GridRenderer;"
private const val EXTENSION_PLAYLIST_CONTENTS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaylistContents;"
private const val EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoBrowseRequest;"
private const val EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoFolderReload;"
private const val EXTENSION_PLAYBACK_CALLBACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaybackCallback;"
private const val EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaybackStateSession;"
private const val EXTENSION_PHONE_BROWSE_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseItem;"
private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

// The constructor call's first register holds the new MediaDescriptionCompat object, followed by its ID and title.
// Offsets 1 and 2 locate those two arguments relative to the call's first register.
private const val MEDIA_DESCRIPTION_MEDIA_ID_REGISTER_OFFSET = 1
private const val MEDIA_DESCRIPTION_TITLE_REGISTER_OFFSET = 2

// For both playlists and songs, YTM stores artwork in c, the title in g, and the subtitle in h.
// These field names match all four supported versions.
private const val ARTWORK_CONTAINER_FIELD_NAME = "c"
private const val TITLE_FIELD_NAME = "g"
private const val SUBTITLE_FIELD_NAME = "h"

private const val PLAYLIST_BROWSE_ID_PREFIX = "VL"
private const val PLAY_BUTTON_CONTAINER_FIELD_NAME = "q"

/**
 * Open the Playlists folder in Android Auto's Library to see the list of playlists.
 *
 * Playlist folder loading:
 * - Intercept Android Auto requests: Java handleAndroidAutoPlaylists(); Kotlin [patchAndroidAutoPlaylists].
 * - Request and load playlists: Java requestLibraryPage() and collectPlaylistsFromGrid();
 *   Kotlin [installPhoneBrowseRequestBridges] and [patchPhoneBrowseResponses].
 * - Pagination: Java requestLibraryPage(), appendPaginatedLibraryPlaylists(), firstPaginationCommand();
 *   Kotlin [addLibraryPaginationRequestMethod] and [addPaginatedLibraryGridDecoder].
 * - Return playlists to Android Auto: Java deliverAndroidAutoPlaylists();
 *   Kotlin [addAndroidAutoBrowseRequestInterface].
 *
 * - Playlist titles and artwork: Java addLibraryPlaylist() and artworkUriOrNull();
 *   Kotlin [patchPhoneBrowseItem], [addTextGetter], and [addArtworkUriGetter].
 * - Play a selected playlist: Java handlePlayFromMediaId() and PlaylistPlaybackRequest;
 *   Kotlin [addPlaylistPlayButtonMediaIdGetter], [installPlaybackCallbackBridges],
 *   and [addCommandMediaIdGetter] for Liked Music.
 * - Check playlist contents: Java findFirstPlayableSong() and showNoPlayableSongsNotice();
 *   Kotlin [addVideoIdCheck] and [addPlaybackSessionAccess].
 * - Podcasts: Java handleAndroidAutoBrowseResult(); Kotlin [patchAndroidAutoPodcastItems].
 * - Refresh after playlist edits: Java watchPlaylistEdit(); Kotlin [installAndroidAutoFolderRefresh].
 */
@Suppress("unused")
val supportAndroidAutoPatch = bytecodePatch(
    name = "Restore playlists and podcasts in Android Auto",
    description = "Restores YouTube Music playlists and podcasts in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookPlaylistsTitleMediaIds()
        installPhoneBrowseRequestBridges()
        patchPhoneBrowseResponses()
        patchPhoneBrowseItem()
        patchAndroidAutoPlaylists()
        installAndroidAutoFolderRefresh()
        patchAndroidAutoPodcastItems()
        installPlaybackCallbackBridges()
    }
}

// Identify the Playlists folder in Android Auto's Library

// Identifies the Playlists folder by its translated title because its ID varies.
private fun BytecodePatchContext.hookPlaylistsTitleMediaIds() {
    val buildAndroidAutoMediaItemMethod = BuildAndroidAutoMediaItemFingerprint.method

    buildAndroidAutoMediaItemMethod
        // Include the FLAG_BROWSABLE constructor call: 9.15.51 creates its Playlists folder there.
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                buildAndroidAutoMediaItemMethod.getInstruction<RegisterRangeInstruction>(
                    index,
                )
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

// Request the Library and selected playlists

// Adds methods to request the Library and playlists through YTM.
private fun BytecodePatchContext.installPhoneBrowseRequestBridges() {
    val phoneBrowseRequestFromEndpointMethod = PhoneBrowseRequestFromEndpointFingerprint.originalMethod
    val phoneBrowseRequestType = phoneBrowseRequestFromEndpointMethod.returnType
    val createPhoneBrowseRequestMethod = phoneBrowseRequestFromEndpointMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { reference ->
            reference.parameterTypes.isEmpty() &&
                reference.returnType == phoneBrowseRequestType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the method that creates a phone Browse request")
    // YTM creates and sends Library requests from the same class.
    val phoneBrowseRequestsType = createPhoneBrowseRequestMethod.definingClass
    val phoneBrowseRequestSenderFingerprint = sendPhoneBrowseRequestFingerprint(
        phoneBrowseRequestsType,
        phoneBrowseRequestType,
    )
    val sendPhoneBrowseRequestMethod = phoneBrowseRequestSenderFingerprint.originalMethod
    val requestBrowseIdField = phoneBrowseRequestSenderFingerprint.instructionMatches.single()
        .instruction
        .getReference<FieldReference>()!!

    val phoneBrowseRequestsClass = mutableClassDefBy(phoneBrowseRequestsType)
    phoneBrowseRequestsClass.interfaces.add(EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE)
    addPhoneBrowseRequestMethod(
        phoneBrowseRequestsClass,
        createPhoneBrowseRequestMethod,
        sendPhoneBrowseRequestMethod,
        requestBrowseIdField,
    )
    addLibraryPaginationRequestMethod(
        phoneBrowseRequestsClass,
        phoneBrowseRequestType,
        sendPhoneBrowseRequestMethod,
    )
    capturePhoneBrowseRequestsOnServiceCreate(phoneBrowseRequestsType)
}

// Requests the Library or a playlist using the page ID that YTM calls a Browse ID.
private fun BytecodePatchContext.addPhoneBrowseRequestMethod(
    phoneBrowseRequestsClass: MutableClass,
    createPhoneBrowseRequestMethod: MethodReference,
    sendPhoneBrowseRequestMethod: Method,
    requestBrowseIdField: FieldReference,
) {
    val phoneBrowseRequestType = createPhoneBrowseRequestMethod.returnType
    val requestHierarchyMethods = generateSequence(
        classDefBy(phoneBrowseRequestType),
    ) { classDef ->
        classDef.superclass?.let { superclass -> classDefByOrNull(superclass) }
    }.flatMap { classDef -> classDef.methods.asSequence() }
    val clickTrackingParamsSetterMethod = requestHierarchyMethods
        // The inherited protected setter writes clickTrackingParams; exclude public byte[] overloads.
        .singleOrNull { method ->
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("[B")
        }
        ?: throw PatchException("Could not uniquely resolve the click tracking parameter setter")
    val setRequestBrowseIdMethod = setRequestBrowseIdFingerprint(
        requestBrowseIdField,
    ).originalMethod
    phoneBrowseRequestsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE,
            "patch_requestBrowse",
        ),
        registerCount = 5,
        instructions = """
            invoke-virtual { p0 }, $createPhoneBrowseRequestMethod
            move-result-object v0
            invoke-virtual { v0, p1 }, $setRequestBrowseIdMethod
            # YTM rejects null clickTrackingParams, so pass an empty byte array.
            const/4 v1, 0x0
            new-array v1, v1, [B
            invoke-virtual { v0, v1 }, $clickTrackingParamsSetterMethod
            invoke-virtual { p0, v0, p2 }, $sendPhoneBrowseRequestMethod
            move-result-object v0
            return-object v0
        """,
    )
}

// Library pagination requests

// Sends a pagination request using the command returned by the previous Library page.
private fun BytecodePatchContext.addLibraryPaginationRequestMethod(
    phoneBrowseRequestsClass: MutableClass,
    phoneBrowseRequestType: String,
    sendPhoneBrowseRequestMethod: Method,
) {
    val getGridItemsMethod = GridRendererItemsFingerprint.originalMethod
    val getGridPaginationCommandsMethod = gridPaginationCommandsFingerprint(
        getGridItemsMethod,
    ).originalMethod
    // The pagination request accepts the command type created by getGridPaginationCommandsMethod.
    val paginationReaderReturnTypes =
        getGridPaginationCommandsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .map { reference -> reference.returnType }
        .toSet()
    val createPaginationRequestMethod = classDefBy(phoneBrowseRequestsClass.type).methods.singleOrNull { method ->
        method.returnType == phoneBrowseRequestType &&
            method.parameterTypes.singleOrNull()?.toString() in paginationReaderReturnTypes
    } ?: throw PatchException("Could not resolve the Library pagination request method")
    val paginationCommandType = createPaginationRequestMethod
        .parameterTypes.single().toString()
    phoneBrowseRequestsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE,
            "patch_requestLibraryPagination",
        ),
        registerCount = 3,
        instructions = """
            check-cast p1, $paginationCommandType
            invoke-virtual { p0, p1 }, $createPaginationRequestMethod
            move-result-object p1
            invoke-virtual { p0, p1, p2 }, $sendPhoneBrowseRequestMethod
            move-result-object p1
            return-object p1
        """,
    )
}

// Obtain YTM's object for sending Library and playlist requests

// Saves the object that sends Library and playlist requests when MusicBrowserService starts.
private fun BytecodePatchContext.capturePhoneBrowseRequestsOnServiceCreate(phoneBrowseRequestsType: String) {
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
    // Several providers create this request sender. Select the one used when MusicBrowserService starts.
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

// Read Library and playlist responses

// Makes YTM's methods for reading Library items and playlist songs available to the patch.
private fun BytecodePatchContext.patchPhoneBrowseResponses() {
    val getTabsMethod = PhoneBrowseResponseTabsFingerprint.originalMethod
    val getGridItemsMethod = GridRendererItemsFingerprint.originalMethod
    val getGridPaginationCommandsMethod = gridPaginationCommandsFingerprint(
        getGridItemsMethod,
    ).originalMethod
    val phoneBrowseItemType = PhoneBrowseItemFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val encodeCommandMediaIdMethod = EncodeCommandMediaIdFingerprint.originalMethod
    val tabMapperMatch = PhoneBrowseResponseTabsFingerprint.instructionMatches.single { match ->
        match.instruction.opcode == Opcode.NEW_INSTANCE
    }
    val tabMapperType = tabMapperMatch
        .instruction
        .getReference<TypeReference>()!!
        .type
    val tabWrapperMatch = createPhoneBrowseTabFingerprint(
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
    val getPlaylistItemsMethod = playlistItemsFingerprint(
        phoneBrowseItemType,
    ).originalMethod
    val decodePaginatedLibraryGridMethod = LibraryPaginationDecoderFingerprint.originalMethod
    val getLibraryPaginationResponseProtoMethod = classDefBy(
        getTabsMethod.definingClass,
    ).methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == decodePaginatedLibraryGridMethod.parameterTypes.single().toString()
    } ?: throw PatchException("Could not resolve the Library pagination response method")
    // These private methods must be public so the patched response objects can call them.
    listOf(
        getGridItemsMethod,
        getPlaylistItemsMethod,
        getGridPaginationCommandsMethod,
    ).forEach { method ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).apply {
            accessFlags = accessFlags.toPublicAccessFlags()
        }
    }

    val paginatedLibraryGridDecoderMethod = addPaginatedLibraryGridDecoder(
        decodePaginatedLibraryGridMethod,
    )
    addPhoneBrowseResponseInterface(
        getTabsMethod,
        getLibraryPaginationResponseProtoMethod,
        paginatedLibraryGridDecoderMethod,
        encodeCommandMediaIdMethod,
    )
    addPhoneBrowseTabInterface(getSectionListMethod)
    addSectionListInterface(getSectionContentsMethod)
    addGridRendererInterface(getGridItemsMethod, getGridPaginationCommandsMethod)
    addPlaylistContentsInterface(getPlaylistItemsMethod)
}

// Library pagination responses

// Copies YTM's method for reading Library items from pagination responses.
private fun BytecodePatchContext.addPaginatedLibraryGridDecoder(
    decodePaginatedLibraryGridMethod: Method,
): Method {
    // This method does not use its Library adapter instance. A static copy can read the response
    // without creating the adapter that manages the phone's Library list.
    val clonedDecoderMethod = decodePaginatedLibraryGridMethod.cloneMutable(
        name = "patch_decodePaginatedLibraryGrid",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        // The original method uses p0 for "this" and p1 for the response.
        // Keep an unused first argument so the copied code still finds the response in p1.
        parameters = listOf(
            ImmutableMethodParameter(decodePaginatedLibraryGridMethod.definingClass, null, null),
        ) + decodePaginatedLibraryGridMethod.parameters,
    )
    mutableClassDefBy(decodePaginatedLibraryGridMethod.definingClass).methods.add(
        clonedDecoderMethod,
    )
    return clonedDecoderMethod
}

// Read returned Library and playlist data

// Adds getters for Library items, playlist songs, and the playlist's Play button.
private fun BytecodePatchContext.addPhoneBrowseResponseInterface(
    getTabsMethod: Method,
    getLibraryPaginationResponseProtoMethod: Method,
    paginatedLibraryGridDecoderMethod: Method,
    encodeCommandMediaIdMethod: Method,
) {
    val phoneBrowseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    phoneBrowseResponseClass.interfaces.add(EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE)
    addPlaylistPlayButtonMediaIdGetter(
        phoneBrowseResponseClass,
        getTabsMethod,
        encodeCommandMediaIdMethod,
    )
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getTabs",
        ),
        registerCount = 1,
        instructions = """
            invoke-virtual { p0 }, $getTabsMethod
            move-result-object p0
            return-object p0
        """,
    )
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getPaginatedLibraryGrid",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0 }, $getLibraryPaginationResponseProtoMethod
            move-result-object p0
            const/4 v0, 0x0
            invoke-static { v0, p0 }, $paginatedLibraryGridDecoderMethod
            move-result-object p0
            check-cast p0, $EXTENSION_GRID_RENDERER_INTERFACE
            return-object p0
        """,
    )
}

// Play a selected playlist: the Play button above the song list

// YTM types and methods used to read the playlist's Play button and what it does when pressed.
private data class PlaylistPlayButton(
    val containerType: String,
    val decodeMethod: Method,
    val commandField: FieldReference,
)

// Identifies how to read the playlist's Play button and its playback command.
private fun BytecodePatchContext.findPlaylistPlayButton(commandType: String): PlaylistPlayButton {
    val extensionMatch = playButtonRendererFingerprint(commandType)
    val initializer = extensionMatch.originalMethod
    val extensionIdIndex = extensionMatch.instructionMatches.single().index
    // This initializer registers two protobuf fields. Only inspect the instructions that register the Play button.
    val registrationStart = initializer.instructions.take(extensionIdIndex)
        .indexOfLast { instruction -> instruction.opcode == Opcode.SPUT_OBJECT } + 1
    val registrationEnd = initializer.indexOfFirstInstructionOrThrow(extensionIdIndex, Opcode.SPUT_OBJECT)
    val registrationInstructions = initializer.instructions
        .drop(registrationStart).take(registrationEnd - registrationStart + 1)

    val buttonRendererType = registrationInstructions
        .singleOrNull { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        ?.getReference<TypeReference>()?.type
        ?: throw PatchException("Could not uniquely resolve the Play button's message type")
    val createExtensionInstruction = registrationInstructions.singleOrNull { instruction ->
        instruction.getReference<MethodReference>()?.name == "newSingularGeneratedExtension"
    } as? RegisterRangeInstruction
        ?: throw PatchException("Could not resolve the Play button's extension registration call")
    // newSingularGeneratedExtension takes the data type containing the Play button as its first argument.
    val containerMessageRegister = createExtensionInstruction.startRegister
    val playButtonContainerType = registrationInstructions.singleOrNull { instruction ->
        instruction.opcode == Opcode.SGET_OBJECT &&
            (instruction as OneRegisterInstruction).registerA == containerMessageRegister
    }?.getReference<FieldReference>()?.type
        ?: throw PatchException("Could not uniquely resolve the playlist data containing the Play button")
    val playButtonExtensionField = initializer.getInstruction<Instruction>(registrationEnd)
        .getReference<FieldReference>()!!

    val decodePlayButtonMethod = decodeButtonRendererFingerprint(
        playButtonContainerType,
        buttonRendererType,
        playButtonExtensionField,
    ).originalMethod

    // The playlist Play button and live chat button store their commands in the same field of YTM's button data.
    // Use the live chat button's code to identify that field.
    val copiedPlayCommandFields = buttonRendererCommandCopyFingerprint(
        buttonRendererType,
        commandType,
    ).matchAll()
        .map { match ->
            // IGET_OBJECT reads the button's command; IPUT_OBJECT copies it to the object handling the press.
            val playButtonCommandReadMatch = match.instructionMatches.single { instructionMatch ->
                instructionMatch.instruction.opcode == Opcode.IGET_OBJECT
            }
            playButtonCommandReadMatch.instruction.getReference<FieldReference>()!!
        }
    // All matching methods must read the same field before it can be used for playlist playback.
    val playCommandField = copiedPlayCommandFields
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the command stored in the playlist Play button")

    return PlaylistPlayButton(
        containerType = playButtonContainerType,
        decodeMethod = decodePlayButtonMethod,
        commandField = playCommandField,
    )
}

// Adds a getter for the playlist Play button's command, encoded as an Android Auto media ID.
private fun BytecodePatchContext.addPlaylistPlayButtonMediaIdGetter(
    phoneBrowseResponseClass: MutableClass,
    getTabsMethod: Method,
    encodeCommandMediaIdMethod: Method,
) {
    val commandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val playButton = findPlaylistPlayButton(commandType)
    val phoneBrowseResponseProtoField = getTabsMethod.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .filter { field ->
            field.definingClass == getTabsMethod.definingClass &&
                field.type != getTabsMethod.returnType
        }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Could not resolve the phone Browse response message field")
    // Field q in the returned playlist page contains the Play button data.
    val playButtonContainerField =
        classDefBy(phoneBrowseResponseProtoField.type).fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) &&
                field.name == PLAY_BUTTON_CONTAINER_FIELD_NAME &&
                field.type == playButton.containerType
        } ?: throw PatchException("Could not resolve the playlist field containing the Play button")

    val decodePlayButton = 0x1
    phoneBrowseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_RESPONSE_INTERFACE,
            "patch_getPlaylistPlayButtonMediaId",
        ),
        registerCount = 7,
        instructions = """
            iget-object v0, p0, $phoneBrowseResponseProtoField
            iget-object v1, v0, $playButtonContainerField
            # Enable decoding of the playlist Play button; false returns null.
            const/4 v2, $decodePlayButton
            invoke-static { v2, v1 }, ${playButton.decodeMethod}
            move-result-object v3
            if-eqz v3, :no_playlist_play_button_media_id
            iget-object v4, v3, ${playButton.commandField}
            if-eqz v4, :no_playlist_play_button_media_id
            invoke-static { v4 }, $encodeCommandMediaIdMethod
            move-result-object v5
            return-object v5
            :no_playlist_play_button_media_id
            const/4 v5, 0x0
            return-object v5
        """,
    )
}

// Read Library items and playlist songs

// Reads the Library lists or playlist song list stored in YTM's tab data, even without a visible tab bar.
private fun BytecodePatchContext.addPhoneBrowseTabInterface(
    getSectionListMethod: Method,
) {
    val phoneBrowseTabClass = mutableClassDefBy(getSectionListMethod.definingClass)
    phoneBrowseTabClass.interfaces.add(EXTENSION_PHONE_BROWSE_TAB_INTERFACE)
    phoneBrowseTabClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_TAB_INTERFACE,
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

// Returns the Library lists or playlist song lists stored in the page's sections.
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

// Adds getters for Library items and pagination commands.
private fun BytecodePatchContext.addGridRendererInterface(
    getItemsMethod: Method,
    getPaginationCommandsMethod: Method,
) {
    val gridRendererType = getItemsMethod.parameterTypes.single().toString()
    val gridRendererClass = mutableClassDefBy(gridRendererType)
    gridRendererClass.interfaces.add(EXTENSION_GRID_RENDERER_INTERFACE)
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
            "patch_getItems",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
    gridRendererClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_GRID_RENDERER_INTERFACE,
            "patch_getPaginationCommands",
        ),
        registerCount = 1,
        instructions = """
            invoke-static { p0 }, $getPaginationCommandsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

// Reads playlist songs and the "Add a song" button without creating the phone's UI objects.
private fun BytecodePatchContext.addPlaylistContentsInterface(
    getItemsMethod: Method,
) {
    val playlistContentsType = getItemsMethod.parameterTypes.first().toString()
    val playlistContentsClass = mutableClassDefBy(playlistContentsType)
    playlistContentsClass.interfaces.add(EXTENSION_PLAYLIST_CONTENTS_INTERFACE)
    val createUiObjects = 0x0
    playlistContentsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PLAYLIST_CONTENTS_INTERFACE,
            "patch_getItems",
        ),
        registerCount = 2,
        instructions = """
            # Pass false to read songs and the "Add a song" button; true creates the phone's UI objects.
            const/4 v0, $createUiObjects
            invoke-static { p0, v0 }, $getItemsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

// Playlist titles and artwork

// YTM uses one item type for playlists, songs, and Add a song. Add getters for its text, artwork, and commands.
private fun BytecodePatchContext.patchPhoneBrowseItem() {
    val phoneBrowseItemType = PhoneBrowseItemFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val phoneBrowseItemFields = classDefBy(phoneBrowseItemType).fields.toList()

    fun phoneBrowseItemField(name: String) = phoneBrowseItemFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkContainerField = phoneBrowseItemField(ARTWORK_CONTAINER_FIELD_NAME)
    val titleField = phoneBrowseItemField(TITLE_FIELD_NAME)
    val subtitleField = phoneBrowseItemField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected phone Browse item metadata fields")
    }

    val browseEndpointBrowseIdField = PhoneBrowseRequestFromEndpointFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.IGET_OBJECT }
        .instruction
        .getReference<FieldReference>()
        ?: throw PatchException("Could not resolve the BrowseEndpoint browse ID field")
    val encodeCommandMediaIdMethod = EncodeCommandMediaIdFingerprint.originalMethod
    val itemCommandType = encodeCommandMediaIdMethod.parameterTypes.single().toString()
    val commandFieldI = phoneBrowseItemField("i")
    val commandFieldK = phoneBrowseItemField("k")
    if (commandFieldI.type != itemCommandType ||
        commandFieldK.type != itemCommandType
    ) {
        throw PatchException(
            "Phone Browse item fields i and k do not have the expected command type",
        )
    }

    val commandToBrowseEndpointMethod = browseEndpointFromCommandFingerprint(
        itemCommandType,
        browseEndpointBrowseIdField.definingClass,
    ).originalMethod

    val formatTextMethod = formatTextFingerprint(titleField.type).originalMethod

    val phoneBrowseItemClass = mutableClassDefBy(phoneBrowseItemType)
    phoneBrowseItemClass.interfaces.add(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE)
    // Check fields i and k for playlist IDs. Skip the item if they identify different playlists.
    phoneBrowseItemClass.addPlaylistBrowseIdGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getPlaylistBrowseId"),
        commandFieldI,
        commandFieldK,
        commandToBrowseEndpointMethod,
        browseEndpointBrowseIdField,
    )
    // Playback and song detection must use the same command: i, or k only when i is null.
    val readItemCommand = """
        iget-object v0, p0, $commandFieldI
        if-nez v0, :have_command
        iget-object v0, p0, $commandFieldK
        :have_command
    """
    phoneBrowseItemClass.addCommandMediaIdGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getCommandMediaId"),
        readItemCommand,
        encodeCommandMediaIdMethod,
    )
    phoneBrowseItemClass.addVideoIdCheck(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_hasPlayableVideoId"),
        readItemCommand,
        findWatchEndpointAccess(itemCommandType),
    )
    phoneBrowseItemClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    phoneBrowseItemClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_PHONE_BROWSE_ITEM_INTERFACE, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    addArtworkUriGetter(phoneBrowseItemClass, artworkContainerField)
}

// Reads the playlist ID used to exclude artists and podcasts from Android Auto's Playlists folder.
private fun MutableClass.addPlaylistBrowseIdGetter(
    interfaceMethod: Method,
    commandFieldI: FieldReference,
    commandFieldK: FieldReference,
    commandToBrowseEndpointMethod: Method,
    browseEndpointBrowseIdField: FieldReference,
) {
    // YTM throws if the command does not open a page. collectPlaylistsFromGrid catches this and skips the item.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            # YTM uses an empty string for a missing page ID; startsWith is safe without a null check.
            const/4 v0, 0x0
            iget-object v1, p0, $commandFieldI
            if-eqz v1, :try_for_browse_id
            invoke-static { v1 }, $commandToBrowseEndpointMethod
            move-result-object v1
            iget-object v1, v1, $browseEndpointBrowseIdField
            const-string v2, "$PLAYLIST_BROWSE_ID_PREFIX"
            invoke-virtual { v1, v2 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
            move-result v2
            if-eqz v2, :try_for_browse_id
            move-object v0, v1

            :try_for_browse_id
            iget-object v1, p0, $commandFieldK
            if-eqz v1, :return_browse_id
            invoke-static { v1 }, $commandToBrowseEndpointMethod
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

// Read the playback command attached to a song

// Encodes an item's command in the format YTM accepts from Android Auto.
private fun MutableClass.addCommandMediaIdGetter(
    interfaceMethod: Method,
    readItemCommand: String,
    encodeCommandMediaIdMethod: Method,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 2,
        instructions = """
            $readItemCommand
            if-nez v0, :encode_command_media_id
            # Load null again so Android's bytecode verifier accepts the String return on 9.15.51.
            const/4 v0, 0x0
            goto :return_command_media_id

            :encode_command_media_id
            invoke-static { v0 }, $encodeCommandMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            :return_command_media_id
            return-object v0
        """,
    )
}

// Check playlist contents

// WatchEndpoint identifies the song or video to play. YTM uses video IDs for songs too.
// This class groups the YTM fields and methods the patch needs to read that ID.
private data class WatchEndpointAccess(
    val extensionField: FieldReference,
    val messageType: String,
    val videoIdField: FieldReference,
    val extensionSetField: FieldReference,
    val extensionKeyField: FieldReference,
    val hasExtensionMethod: Method,
    val getExtensionMethod: Method,
)

// Identifies where YTM stores the song ID in a playback command and how to read it.
private fun BytecodePatchContext.findWatchEndpointAccess(commandType: String): WatchEndpointAccess {
    val watchEndpointInitializer = WatchEndpointExtensionFingerprint.originalMethod
    val watchEndpointExtensionField = watchEndpointInitializer.instructions
        .filter { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .singleOrNull { field -> field.definingClass == watchEndpointInitializer.definingClass }
        ?: throw PatchException("Could not resolve the WatchEndpoint extension field")
    val watchEndpointType = watchEndpointInitializer.instructions
        .filter { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .mapNotNull { instruction -> instruction.getReference<TypeReference>()?.type }
        .singleOrNull()
        ?: throw PatchException("Could not resolve the WatchEndpoint message type")
    // WatchEndpoint's video ID uses the obfuscated field name d.
    val watchEndpointVideoIdField = classDefBy(watchEndpointType).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.name == "d" && field.type == "Ljava/lang/String;"
    } ?: throw PatchException("Could not resolve WatchEndpoint.videoId")

    val commandSuperclass = classDefBy(commandType).superclass
        ?: throw PatchException("Could not resolve the command superclass")
    // The command's j field holds optional data such as WatchEndpoint.
    val extensionSetField = classDefBy(commandSuperclass).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == "j"
    } ?: throw PatchException("Could not resolve the command extension set")
    // Field d in the WatchEndpoint registration holds the key used to read WatchEndpoint from j.
    val extensionKeyField = classDefBy(watchEndpointExtensionField.type).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == "d"
    } ?: throw PatchException("Could not resolve the WatchEndpoint extension key")

    val extensionSetClass = classDefBy(extensionSetField.type)
    val hasWatchEndpointMethod = extensionSetClass.methods.singleOrNull { method ->
        method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(extensionKeyField.type)
    } ?: throw PatchException("Could not resolve the WatchEndpoint presence method")
    val getWatchEndpointMethod = extensionSetClass.methods.singleOrNull { method ->
        method.returnType == "Ljava/lang/Object;" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(extensionKeyField.type)
    } ?: throw PatchException("Could not resolve the WatchEndpoint value method")

    return WatchEndpointAccess(
        extensionField = watchEndpointExtensionField,
        messageType = watchEndpointType,
        videoIdField = watchEndpointVideoIdField,
        extensionSetField = extensionSetField,
        extensionKeyField = extensionKeyField,
        hasExtensionMethod = hasWatchEndpointMethod,
        getExtensionMethod = getWatchEndpointMethod,
    )
}

// Checks for a song ID so the Add a song button is not mistaken for a playable song.
private fun MutableClass.addVideoIdCheck(
    interfaceMethod: Method,
    readItemCommand: String,
    watchEndpoint: WatchEndpointAccess,
) {
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            $readItemCommand
            if-eqz v0, :no_video_id
            iget-object v0, v0, ${watchEndpoint.extensionSetField}
            sget-object v1, ${watchEndpoint.extensionField}
            iget-object v1, v1, ${watchEndpoint.extensionKeyField}
            invoke-virtual { v0, v1 }, ${watchEndpoint.hasExtensionMethod}
            move-result v2
            if-eqz v2, :no_video_id
            invoke-virtual { v0, v1 }, ${watchEndpoint.getExtensionMethod}
            move-result-object v0
            check-cast v0, ${watchEndpoint.messageType}
            iget-object v0, v0, ${watchEndpoint.videoIdField}
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v0
            if-nez v0, :no_video_id
            const/4 v0, 0x1
            return v0
            :no_video_id
            const/4 v0, 0x0
            return v0
        """,
    )
}

// Read playlist titles and artwork

// Uses YTM's text formatter to read an item's title or subtitle.
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
            # No separate pronunciation text is needed for the title or subtitle.
            const/4 v1, 0x0
            invoke-static { v0, v1 }, $formatTextMethod
            move-result-object v0
            return-object v0
        """,
    )
}

// Reads a playlist's artwork and converts it to the image URI Android Auto expects.
private fun BytecodePatchContext.addArtworkUriGetter(
    itemClass: MutableClass,
    artworkContainerField: FieldReference,
) {
    val artworkPayloadType = PhoneBrowseItemThumbnailFingerprint.instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val artworkPayloadFields = classDefBy(artworkPayloadType).fields
        .filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
        .toList()
    val artworkPayloadFieldTypes = artworkPayloadFields.map(FieldReference::getType).toSet()
    val decodeArtworkPayloadMethod = decodeThumbnailFingerprint(
        artworkContainerField.type,
    ).originalMethod
    // Use the same artwork URI format as YTM's Android Auto items.
    val androidAutoMediaDescriptionMethod = androidAutoMediaDescriptionFingerprint(
        artworkPayloadFieldTypes,
    ).originalMethod
    val mediaDescriptionReadFieldTypes = androidAutoMediaDescriptionMethod.instructions
        .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
        .toSet()
    val createArtworkUriMethod = androidAutoMediaDescriptionMethod.instructions
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .filter { method ->
            method.returnType == "Landroid/net/Uri;" && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().toString() in mediaDescriptionReadFieldTypes
        }
        .singleOrNull { method ->
            method.parameterTypes.single().toString() in artworkPayloadFieldTypes
        }
        ?: throw PatchException("Could not resolve YTM's Android Auto artwork Uri method")
    val thumbnailDetailsType = createArtworkUriMethod.parameterTypes.single().toString()
    val thumbnailDetailsField = artworkPayloadFields.singleOrNull { field ->
        field.type == thumbnailDetailsType
    } ?: throw PatchException("Could not resolve the thumbnail details field")

    itemClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PHONE_BROWSE_ITEM_INTERFACE,
            "patch_getArtworkUri",
        ),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $artworkContainerField
            invoke-static { v0 }, $decodeArtworkPayloadMethod
            move-result-object v0
            if-eqz v0, :no_artwork
            check-cast v0, ${thumbnailDetailsField.definingClass}
            iget-object v0, v0, $thumbnailDetailsField
            invoke-static { v0 }, $createArtworkUriMethod
            move-result-object v0
            return-object v0
            :no_artwork
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

// Intercept requests for the Playlists folder and return its playlists to Android Auto

// Intercepts requests for the Playlists folder, which YTM would otherwise leave empty.
private fun BytecodePatchContext.patchAndroidAutoPlaylists() {
    val sendEmptyAndroidAutoMediaItemsMethod = SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod
    addAndroidAutoBrowseRequestInterface(sendEmptyAndroidAutoMediaItemsMethod)
    hookAndroidAutoPlaylistsRequest(
        sendEmptyAndroidAutoMediaItemsMethod.definingClass,
        sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString(),
    )
}

// Adds methods to read the requested Android Auto media ID and return media items.
private fun BytecodePatchContext.addAndroidAutoBrowseRequestInterface(
    sendEmptyAndroidAutoMediaItemsMethod: Method,
) {
    val androidAutoRequestType = sendEmptyAndroidAutoMediaItemsMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    val androidAutoRequestFields = sendEmptyAndroidAutoMediaItemsMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .toList()
    // YTM's "Invalid media id" log reads the requested media ID through these two fields.
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

    androidAutoRequestClass.interfaces.add(EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE)
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_getRequestedMediaId",
        ),
        registerCount = 1,
        instructions = """
            iget-object p0, p0, $requestedMediaIdHolderField
            iget-object p0, p0, $requestedMediaIdField
            return-object p0
        """,
    )
    // YTM passes null for the optional second argument when returning an empty list.
    val usesTwoArgumentDeliveryMethod = deliverAndroidAutoMediaItemsMethod.parameterTypes.size == 2
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_deliverAndroidAutoItems",
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

// Sends requests for the Playlists folder to the patch; YTM handles requests for other folders.
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
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleAndroidAutoPlaylists($EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", handleAndroidAutoRequestMethod.getInstruction<Instruction>(0)),
    )
}

// Refresh Playlists after edits and Podcasts after Home loads

// Adds folder refreshes for Podcasts loading and completed playlist edits.
private fun BytecodePatchContext.installAndroidAutoFolderRefresh() {
    // Android Auto requests list updates through MediaBrowserServiceCompat.
    // MediaBrowserService.notifyChildrenChanged does not reach that connection, so refresh through the compat service.
    val serviceSuperclass = classDefBy(MUSIC_BROWSER_SERVICE_CLASS).superclass
        ?: throw PatchException("Could not resolve MusicBrowserService's superclass")
    val baseServiceType = classDefBy(serviceSuperclass).superclass
        ?: throw PatchException("Could not resolve the browser service base class above $serviceSuperclass")
    val reloadMethod = mediaBrowserReloadFingerprint(baseServiceType).originalMethod

    addAndroidAutoRequestConnectionGetter(reloadMethod)
    addAndroidAutoFolderReload(baseServiceType, reloadMethod)
    hookPlaylistEditCompletion()
}

// Identifies the requesting Android Auto connection so older results cannot replace newer ones.
private fun BytecodePatchContext.addAndroidAutoRequestConnectionGetter(reloadMethod: Method) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    // YTM passes the Android Auto connection to the object that returns the list.
    val folderResultConstructor = reloadMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
        .distinct()
        .single { method ->
            method.name == "<init>" && connectionType in method.parameterTypes
        }
    val folderResultClass = mutableClassDefBy(folderResultConstructor.definingClass)
    val resultConnectionField = folderResultClass.fields.single { field ->
        field.type == connectionType
    }

    val androidAutoRequestType =
        SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod.parameterTypes.first().toString()
    val androidAutoRequestClass = mutableClassDefBy(androidAutoRequestType)
    // The request can hold other result types; check for the result object that stores the Android Auto connection.
    val resultBaseType = folderResultClass.superclass
        ?: throw PatchException("Could not resolve the Android Auto folder result base class")
    val requestResultField = androidAutoRequestClass.fields.single { field ->
        field.type == resultBaseType
    }

    folderResultClass.accessFlags = folderResultClass.accessFlags.toPublicAccessFlags()
    resultConnectionField.accessFlags = resultConnectionField.accessFlags.toPublicAccessFlags()
    androidAutoRequestClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE,
            "patch_getBrowserConnection",
        ),
        registerCount = 3,
        instructions = """
            iget-object v0, p0, $requestResultField
            instance-of v1, v0, ${folderResultClass.type}
            if-eqz v1, :unknown_connection
            check-cast v0, ${folderResultClass.type}
            iget-object v0, v0, $resultConnectionField
            return-object v0
            :unknown_connection
            # Without the connection, the patch cannot tell whether two requests update the same Android Auto folder.
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

// Saves the requesting connection and lets Java refresh Playlists or Podcasts on that connection.
private fun BytecodePatchContext.addAndroidAutoFolderReload(
    baseServiceType: String,
    reloadMethod: Method,
) {
    val connectionType = reloadMethod.parameterTypes[1].toString()
    val baseServiceClass = mutableClassDefBy(baseServiceType)
    baseServiceClass.interfaces.add(EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE)
    baseServiceClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE,
            "patch_reloadFolder",
        ),
        registerCount = 4,
        instructions = """
            check-cast p2, $connectionType
            const/4 v0, 0x0
            invoke-virtual { p0, p1, p2, v0 }, $reloadMethod
            return-void
        """,
    )
    val rememberSubscriptionMethod = "$EXTENSION_CLASS->rememberAndroidAutoSubscription(" +
        EXTENSION_ANDROID_AUTO_FOLDER_RELOAD_INTERFACE +
        "Ljava/lang/String;Ljava/lang/Object;)V"
    baseServiceClass.findMutableMethodOf(reloadMethod).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $rememberSubscriptionMethod
        """,
    )
}

// Watches whether a phone playlist edit succeeds before refreshing Android Auto's Playlists folder.
private fun BytecodePatchContext.hookPlaylistEditCompletion() {
    val editRequestType = EditPlaylistRequestFingerprint.originalMethod.definingClass
    val sendEditMethod = playlistEditFutureFingerprint(editRequestType).originalMethod
    val mutableSendEditMethod = mutableClassDefBy(sendEditMethod.definingClass)
        .findMutableMethodOf(sendEditMethod)
    // The returned future reports whether the playlist edit succeeded.
    val returnIndex = mutableSendEditMethod.instructions.withIndex()
        .singleOrNull { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        ?.index
        ?: throw PatchException("Could not find the playlist edit result")
    val editFutureRegister = mutableSendEditMethod
        .getInstruction<OneRegisterInstruction>(returnIndex).registerA
    mutableSendEditMethod.addInstructions(
        returnIndex,
        """
            invoke-static/range { v$editFutureRegister .. v$editFutureRegister }, $EXTENSION_CLASS->watchPlaylistEdit(Lcom/google/common/util/concurrent/ListenableFuture;)V
        """,
    )
}

// Podcasts

// Adds the Podcasts tab using the podcast folders returned by Android Auto Home.
private fun BytecodePatchContext.patchAndroidAutoPodcastItems() {
    val androidAutoRequestType =
        SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod.parameterTypes.first().toString()
    val deliverAndroidAutoMediaItemsMethod = mutableClassDefBy(androidAutoRequestType).methods.single { method ->
        method.returnType == "V" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes.first().toString() == "Ljava/util/List;" &&
            method.parameterTypes.last().toString().startsWith("L")
    }

    val handleAndroidAutoBrowseResultMethod = "$EXTENSION_CLASS->handleAndroidAutoBrowseResult(" +
        EXTENSION_ANDROID_AUTO_BROWSE_REQUEST_INTERFACE +
        "Ljava/util/List;)Ljava/util/List;"
    deliverAndroidAutoMediaItemsMethod.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $handleAndroidAutoBrowseResultMethod
            move-result-object p1
        """,
    )
}

// Play a selected playlist: callbacks

// Adds hooks for playlist selections and Pause/Stop, plus access to YTM's playback status.
private fun BytecodePatchContext.installPlaybackCallbackBridges() {
    val playFromMediaIdMethod = AndroidAutoPlayFromMediaIdFingerprint.method
    val callbackClass = mutableClassDefBy(playFromMediaIdMethod.definingClass)
    val delegateField = playFromMediaIdMethod.instructions.asSequence()
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .distinct()
        .single { field -> field.definingClass == callbackClass.type }

    addPlaybackSessionAccess(delegateField.type)
    addPlaybackCallbackAccess(callbackClass, delegateField)
    hookPlaylistPlayback(playFromMediaIdMethod)
    hookPlaylistPlaybackCancellation(callbackClass)
}

// Adds access to YTM's playback status so the patch can show a message when a playlist is empty.
private fun BytecodePatchContext.addPlaybackSessionAccess(delegateType: String) {
    val playbackStateSetter = MediaSessionCompatPlaybackStateSetterFingerprint.method
    val sessionClass = mutableClassDefBy(playbackStateSetter.definingClass)
    val cachedStateField = playbackStateSetter.instructions.asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .singleOrNull { field ->
            field.type == "Landroid/support/v4/media/session/PlaybackStateCompat;"
        } ?: throw PatchException("Could not find cached compat playback state")
    val playbackOwnerInstructions: String
    val playbackStateInstructions: String
    // 9.15 stores playback state on the session; 9.35-9.37 use a separate object owned by the session.
    if (cachedStateField.definingClass == sessionClass.type) {
        playbackOwnerInstructions = "return-object p0"
        playbackStateInstructions = """
            iget-object v0, p0, $cachedStateField
            return-object v0
        """
    } else {
        val playbackStateOwnerField =
            playbackStateSetter.instructions.asSequence()
                .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
                .distinct()
                .singleOrNull { field ->
                    field.definingClass == sessionClass.type && field.type == "Ljava/lang/Object;"
                } ?: throw PatchException("Could not find compat playback session owner")
        playbackOwnerInstructions = """
            iget-object v0, p0, $playbackStateOwnerField
            return-object v0
        """
        playbackStateInstructions = """
            iget-object v0, p0, $playbackStateOwnerField
            check-cast v0, ${cachedStateField.definingClass}
            iget-object v0, v0, $cachedStateField
            return-object v0
        """

        // The object receiving playback commands cannot update playback status itself.
        // Save its media session so the patch can use the session's setter to show the empty playlist message.
        val setCallbackMethod = sessionClass.methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    delegateType, "Landroid/os/Handler;",
                )
        } ?: throw PatchException("Could not find compat media session callback setup")
        setCallbackMethod.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerPlaybackSession($EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE)V",
        )
    }

    sessionClass.interfaces.add(EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE)
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE, "patch_getPlaybackOwner"),
        registerCount = 2,
        instructions = playbackOwnerInstructions,
    )
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE, "patch_getPlaybackState"),
        registerCount = 2,
        instructions = playbackStateInstructions,
    )
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE, "patch_setPlaybackState"),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0, p1 }, $playbackStateSetter
            return-void
        """,
    )
}

// Adds access to the Handler that runs YTM's playback commands and the media session that stores playback status.
private fun BytecodePatchContext.addPlaybackCallbackAccess(
    callbackClass: MutableClass,
    delegateField: FieldReference,
) {
    val delegateClass = classDefBy(delegateField.type)
    // Use the callback's Handler so playlist playback runs on YTM's playback thread.
    val handlerField = delegateClass.fields.singleOrNull { field ->
        runCatching { classDefBy(field.type).superclass == "Landroid/os/Handler;" }
            .getOrDefault(false)
    } ?: throw PatchException("Could not find media session callback Handler")
    val callbackOwnerReferenceField = delegateClass.fields.singleOrNull { field ->
        field.type == "Ljava/lang/ref/WeakReference;"
    } ?: throw PatchException("Could not find media session callback owner")
    callbackClass.interfaces.add(EXTENSION_PLAYBACK_CALLBACK_INTERFACE)
    callbackClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PLAYBACK_CALLBACK_INTERFACE,
            "patch_getCallbackHandler",
        ),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $delegateField
            if-eqz v0, :no_handler
            iget-object v0, v0, $handlerField
            return-object v0
            :no_handler
            const/4 v0, 0x0
            return-object v0
        """,
    )
    callbackClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_PLAYBACK_CALLBACK_INTERFACE,
            "patch_getPlaybackStateSession",
        ),
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $delegateField
            if-eqz v0, :no_session
            iget-object v0, v0, $callbackOwnerReferenceField
            if-eqz v0, :no_session
            invoke-virtual { v0 }, Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;
            move-result-object v0
            invoke-static { v0 }, $EXTENSION_CLASS->resolvePlaybackSession(Ljava/lang/Object;)$EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE
            move-result-object v0
            return-object v0
            :no_session
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

// Sends playlist selections to the patch first; YTM handles IDs that the patch does not recognize.
private fun hookPlaylistPlayback(playFromMediaIdMethod: MutableMethod) {
    val handlePlaylistSelectionMethod = "$EXTENSION_CLASS->handlePlayFromMediaId(" +
        "Landroid/media/session/MediaSession${'$'}Callback;" +
        "Ljava/lang/String;Landroid/os/Bundle;)Z"
    // YTM cannot decode this patch's media IDs; resolve them before its playback handler runs.
    val handledRegister = playFromMediaIdMethod.findFreeRegister(0)
    playFromMediaIdMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p2 }, $handlePlaylistSelectionMethod
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", playFromMediaIdMethod.getInstruction<Instruction>(0)),
    )
}

// Cancels playlist selections still loading when Pause or Stop is pressed.
private fun hookPlaylistPlaybackCancellation(callbackClass: MutableClass) {
    // onPause/onStop are Android callback names and are not obfuscated.
    for (name in listOf("onPause", "onStop")) {
        val transportMethod = callbackClass.methods.single { method ->
            method.name == name && method.parameterTypes.isEmpty() && method.returnType == "V"
        }
        transportMethod.addInstructions(
            0,
            "invoke-static {}, $EXTENSION_CLASS->cancelPendingPlaylistPlayback()V",
        )
    }
}

// Add the methods declared in the Java interfaces to YTM classes

// Looks up the method declaration in the patch's Java interface.
private fun BytecodePatchContext.extensionInterfaceMethod(
    interfaceType: String,
    name: String,
) = classDefBy(interfaceType).methods.singleOrNull { method -> method.name == name }
    ?: throw PatchException("Could not resolve $name in $interfaceType")

// Adds the declared Java method to a YTM class with the supplied bytecode.
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
