/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.support

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
    "Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch;"
private const val EXTENSION_PHONE_BROWSE_REQUESTS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PhoneBrowseRequests;"
private const val EXTENSION_BROWSE_RESPONSE_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$BrowseResponse;"
private const val EXTENSION_BROWSE_TAB_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$BrowseTab;"
private const val EXTENSION_SECTION_LIST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$SectionList;"
private const val EXTENSION_GRID_RENDERER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$GridRenderer;"
private const val EXTENSION_OPENED_PLAYLIST_ROWS_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$OpenedPlaylistRows;"
private const val EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$AndroidAutoPlaylistsRequest;"
private const val EXTENSION_PLAYBACK_CALLBACK_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaybackCallback;"
private const val EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$PlaybackStateSession;"
private const val EXTENSION_SHARED_BROWSE_ROW_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/SupportAndroidAutoPatch$SharedBrowseRow;"
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
private const val PLAYLIST_HEADER_FIELD_NAME = "q"
@Suppress("unused")
val supportAndroidAutoPatch = bytecodePatch(
    name = "Restore playlists and podcasts in Android Auto",
    description = "Restores YouTube Music playlists and podcasts in Android Auto.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookPlaylistsTitleMediaIds()
        patchPhoneBrowseRequests()
        patchPhoneBrowseResponses()
        patchSharedBrowseRow()
        patchAndroidAutoPlaylists()
        patchAndroidAutoPodcastItems()
        installPlaybackCallbackBridges()
    }
}

// Identify the Playlists folder in Android Auto's Library

// Identifies the Playlists folder by its translated title because its ID varies.
private fun BytecodePatchContext.hookPlaylistsTitleMediaIds() {
    val buildAndroidAutoMediaItemMethod = BuildAndroidAutoMediaItemFingerprint.method

    // Hook every construction path: 9.15.51 builds Playlists in the FLAG_BROWSABLE branch.
    buildAndroidAutoMediaItemMethod
        // Include the FLAG_BROWSABLE constructor call: 9.15.51 creates its Playlists folder there.
        .findInstructionIndicesReversedOrThrow(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL)
        .forEach { index ->
            val instruction =
                buildAndroidAutoMediaItemMethod.getInstruction<RegisterRangeInstruction>(
                    index,
                )
            // Skip the receiver register; the next two registers hold the media ID and title.
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
    // The Library's next-page action identifies the method for requesting more playlists.
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
        // Some builds also have a public byte[] overload; use the protected request setter.
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
            "patch_requestLibraryContinuation",
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
    val getTabsMethod = BrowseResponseTabsFingerprint.originalMethod
    val getGridRowsMethod = GridRendererRowsFingerprint.originalMethod
    val getGridContinuationActionsMethod = gridContinuationActionsFingerprint(
        getGridRowsMethod,
    ).originalMethod
    val sharedBrowseRowType = SharedBrowseRowFingerprint
        .instructionMatches
        .single { match -> match.instruction.opcode == Opcode.CONST_CLASS }
        .instruction
        .getReference<TypeReference>()!!
        .type
    val encodeActionMediaIdMethod = EncodeActionMediaIdFingerprint.originalMethod
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
    val getOpenedPlaylistRowsMethod = openedPlaylistRowsFingerprint(
        sharedBrowseRowType,
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
        getGridRowsMethod,
        getOpenedPlaylistRowsMethod,
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
        getLibraryPaginationResponseProtoMethod,
        paginatedLibraryGridDecoderMethod,
        encodeActionMediaIdMethod,
    )
    addBrowseTabInterface(getSectionListMethod)
    addSectionListInterface(getSectionContentsMethod)
    addGridRendererInterface(getGridRowsMethod, getGridContinuationActionsMethod)
    addOpenedPlaylistRowsInterface(getOpenedPlaylistRowsMethod)
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

private fun BytecodePatchContext.addBrowseResponseInterface(
    getTabsMethod: Method,
    getLibraryPaginationResponseProtoMethod: Method,
    paginatedLibraryGridDecoderMethod: Method,
    encodeActionMediaIdMethod: Method,
) {
    val browseResponseClass = mutableClassDefBy(getTabsMethod.definingClass)
    browseResponseClass.interfaces.add(EXTENSION_BROWSE_RESPONSE_INTERFACE)
    addOpenedPlaylistHeaderPlayMediaIdGetter(
        browseResponseClass,
        getTabsMethod,
        encodeActionMediaIdMethod,
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

private fun BytecodePatchContext.addOpenedPlaylistHeaderPlayMediaIdGetter(
    browseResponseClass: MutableClass,
    getTabsMethod: Method,
    encodeActionMediaIdMethod: Method,
) {
    val playActionType = encodeActionMediaIdMethod.parameterTypes.single().toString()
    val playButtonProtoExtensionInitializer = playButtonRendererFingerprint(
        playActionType,
    ).originalMethod
    val buttonRendererType = playButtonProtoExtensionInitializer.instructions
        .first { instruction -> instruction.opcode == Opcode.CONST_CLASS }
        .getReference<TypeReference>()!!
        .type
    val playlistHeaderType = playButtonProtoExtensionInitializer.instructions
        .asSequence()
        .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .first { field -> field.definingClass == field.type }
        .type
    val playButtonExtensionField = playButtonProtoExtensionInitializer.instructions
        .first { instruction -> instruction.opcode == Opcode.SPUT_OBJECT }
        .getReference<FieldReference>()!!
    val decodePlayButtonMethod = decodeButtonRendererFingerprint(
        playlistHeaderType,
        buttonRendererType,
        playButtonExtensionField,
    ).originalMethod
    // The playlist's Play button uses the same ButtonRenderer action field as the live-chat button.
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
    // The playlist header uses the fixed obfuscated field q in the Browse response.
    val playlistHeaderContentField =
        classDefBy(browseResponseProtoField.type).fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) &&
                field.name == PLAYLIST_HEADER_FIELD_NAME &&
                field.type == playlistHeaderType
        } ?: throw PatchException("Could not resolve the playlist header content field")

    browseResponseClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_BROWSE_RESPONSE_INTERFACE,
            "patch_getHeaderPlayMediaId",
        ),
        registerCount = 2,
        instructions = """
            iget-object p0, p0, $browseResponseProtoField
            iget-object p0, p0, $playlistHeaderContentField
            # False returns null without decoding the header's ButtonRenderer.
            const/4 v0, 0x1
            invoke-static { v0, p0 }, $decodePlayButtonMethod
            move-result-object p0
            if-eqz p0, :no_header_play_media_id
            iget-object p0, p0, $playActionField
            if-eqz p0, :no_header_play_media_id
            invoke-static { p0 }, $encodeActionMediaIdMethod
            move-result-object p0
            return-object p0
            :no_header_play_media_id
            const/4 p0, 0x0
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

private fun BytecodePatchContext.addOpenedPlaylistRowsInterface(
    getRowsMethod: Method,
) {
    val openedPlaylistRowsType = getRowsMethod.parameterTypes.first().toString()
    val openedPlaylistRowsClass = mutableClassDefBy(openedPlaylistRowsType)
    openedPlaylistRowsClass.interfaces.add(EXTENSION_OPENED_PLAYLIST_ROWS_INTERFACE)
    openedPlaylistRowsClass.addInterfaceMethod(
        interfaceMethod = extensionInterfaceMethod(
            EXTENSION_OPENED_PLAYLIST_ROWS_INTERFACE,
            "patch_getRawRows",
        ),
        registerCount = 2,
        instructions = """
            const/4 v0, 0x0
            # Read playlist rows directly; the UI objects returned for true do not implement SharedBrowseRow.
            invoke-static { p0, v0 }, $getRowsMethod
            move-result-object p0
            return-object p0
        """,
    )
}

private fun BytecodePatchContext.patchSharedBrowseRow() {
    val sharedBrowseRowType = SharedBrowseRowFingerprint
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
    val encodeActionMediaIdMethod = EncodeActionMediaIdFingerprint.originalMethod
    val watchEndpointInitializer = WatchEndpointExtensionFingerprint.originalMethod
    val watchEndpointExtensionField = watchEndpointInitializer.instructions
        .mapNotNull { instruction ->
            if (instruction.opcode == Opcode.SPUT_OBJECT) {
                instruction.getReference<FieldReference>()
            } else {
                null
            }
        }
        .singleOrNull { field -> field.definingClass == watchEndpointInitializer.definingClass }
        ?: throw PatchException("Could not resolve the WatchEndpoint extension field")
    val watchEndpointType = watchEndpointInitializer.instructions
        .mapNotNull { instruction ->
            if (instruction.opcode == Opcode.CONST_CLASS) {
                instruction.getReference<TypeReference>()?.type
            } else {
                null
            }
        }
        .singleOrNull()
        ?: throw PatchException("Could not resolve the WatchEndpoint message type")
    // WatchEndpoint's video ID uses the obfuscated field name d.
    val watchEndpointVideoIdField = classDefBy(watchEndpointType).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.name == "d" && field.type == "Ljava/lang/String;"
    } ?: throw PatchException("Could not resolve WatchEndpoint.videoId")
    val sharedBrowseRowFields = classDefBy(sharedBrowseRowType).fields.toList()

    fun sharedBrowseRowField(name: String) = sharedBrowseRowFields
        .single { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == name
        }

    val artworkContainerField = sharedBrowseRowField(ARTWORK_CONTAINER_FIELD_NAME)
    val titleField = sharedBrowseRowField(TITLE_FIELD_NAME)
    val subtitleField = sharedBrowseRowField(SUBTITLE_FIELD_NAME)
    if (artworkContainerField.type == titleField.type || titleField.type != subtitleField.type) {
        throw PatchException("Unexpected shared Browse row metadata fields")
    }
    val artworkPayloadType = SharedBrowseRowThumbnailFingerprint.instructionMatches
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
    // Use the same artwork URI format as YTM's Android Auto items.
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

    val sharedBrowseRowActionType = encodeActionMediaIdMethod.parameterTypes.single().toString()
    val actionFieldI = sharedBrowseRowField("i")
    val actionFieldK = sharedBrowseRowField("k")
    if (actionFieldI.type != sharedBrowseRowActionType ||
        actionFieldK.type != sharedBrowseRowActionType
    ) {
        throw PatchException(
            "Shared Browse row fields i and k do not have the expected action type",
        )
    }

    val actionToBrowseEndpointMethod = browseEndpointFromActionFingerprint(
        sharedBrowseRowActionType,
        browseEndpointBrowseIdField.definingClass,
    ).originalMethod
    val actionSuperclass = classDefBy(sharedBrowseRowActionType).superclass
        ?: throw PatchException("Could not resolve the action superclass")
    // j stores the action's protobuf extensions; d is the key for its WatchEndpoint extension.
    val extensionSetField = classDefBy(actionSuperclass).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == "j"
    } ?: throw PatchException("Could not resolve the action extension set")
    val extensionKeyField = classDefBy(watchEndpointExtensionField.type).fields
        .singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.name == "d"
        } ?: throw PatchException("Could not resolve the WatchEndpoint extension key")
    val hasWatchEndpointMethod = classDefBy(extensionSetField.type).methods.singleOrNull { method ->
        method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(extensionKeyField.type)
    } ?: throw PatchException("Could not resolve the WatchEndpoint presence method")
    val getWatchEndpointMethod = classDefBy(extensionSetField.type).methods.singleOrNull { method ->
        method.returnType == "Ljava/lang/Object;" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(extensionKeyField.type)
    } ?: throw PatchException("Could not resolve the WatchEndpoint value method")

    val sharedBrowseRowClass = mutableClassDefBy(sharedBrowseRowType)
    sharedBrowseRowClass.interfaces.add(EXTENSION_SHARED_BROWSE_ROW_INTERFACE)
    sharedBrowseRowClass.addPlaylistBrowseIdGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_getPlaylistBrowseId"),
        actionFieldI,
        actionFieldK,
        actionToBrowseEndpointMethod,
        browseEndpointBrowseIdField,
    )
    sharedBrowseRowClass.addActionMediaIdGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_getActionMediaId"),
        actionFieldI,
        actionFieldK,
        encodeActionMediaIdMethod,
    )
    sharedBrowseRowClass.addPlayableVideoIdGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_hasPlayableVideoId"),
        actionFieldI,
        actionFieldK,
        extensionSetField,
        watchEndpointExtensionField,
        extensionKeyField,
        hasWatchEndpointMethod,
        getWatchEndpointMethod,
        watchEndpointType,
        watchEndpointVideoIdField,
    )
    sharedBrowseRowClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_getTitle"),
        titleField,
        formatTextMethod,
    )
    sharedBrowseRowClass.addTextGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_getSubtitle"),
        subtitleField,
        formatTextMethod,
    )
    sharedBrowseRowClass.addArtworkUriGetter(
        extensionInterfaceMethod(EXTENSION_SHARED_BROWSE_ROW_INTERFACE, "patch_getArtworkUri"),
        artworkContainerField,
        decodeArtworkPayloadMethod,
        thumbnailField,
        createArtworkUriMethod,
    )
}

// Reads the playlist ID used to exclude artists and podcasts from Android Auto's Playlists folder.
private fun MutableClass.addPlaylistBrowseIdGetter(
    interfaceMethod: Method,
    actionFieldI: FieldReference,
    actionFieldK: FieldReference,
    actionToBrowseEndpointMethod: Method,
    browseEndpointBrowseIdField: FieldReference,
) {
    // YTM throws if the command does not open a page. collectPlaylistsFromGrid catches this and skips the item.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
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

private fun MutableClass.addActionMediaIdGetter(
    interfaceMethod: Method,
    actionFieldI: FieldReference,
    actionFieldK: FieldReference,
    encodeActionMediaIdMethod: Method,
) {
    // Use k only when i is null. The song check must use the same action as this getter.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 2,
        instructions = """
            iget-object v0, p0, $actionFieldI
            if-eqz v0, :try_for_action_media_id
            invoke-static { v0 }, $encodeActionMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            goto :return_action_media_id

            :try_for_action_media_id
            iget-object v0, p0, $actionFieldK
            if-nez v0, :encode_action_media_id
            # Clear the action type from v0 to avoid a String return-type verification error on 9.15.51.
            const/4 v0, 0x0
            goto :return_action_media_id

            :encode_action_media_id
            invoke-static { v0 }, $encodeActionMediaIdMethod
            move-result-object v0
            check-cast v0, Ljava/lang/String;
            :return_action_media_id
            return-object v0
        """,
    )
}

private fun MutableClass.addPlayableVideoIdGetter(
    interfaceMethod: Method,
    actionFieldI: FieldReference,
    actionFieldK: FieldReference,
    extensionSetField: FieldReference,
    watchEndpointExtensionField: FieldReference,
    extensionKeyField: FieldReference,
    hasWatchEndpointMethod: Method,
    getWatchEndpointMethod: Method,
    watchEndpointType: String,
    watchEndpointVideoIdField: FieldReference,
) {
    // Check the action that addActionMediaIdGetter will send to YTM.
    addInterfaceMethod(
        interfaceMethod = interfaceMethod,
        registerCount = 4,
        instructions = """
            iget-object v0, p0, $actionFieldI
            if-nez v0, :have_action
            iget-object v0, p0, $actionFieldK
            :have_action
            if-eqz v0, :no_video_id
            iget-object v0, v0, $extensionSetField
            sget-object v1, $watchEndpointExtensionField
            iget-object v1, v1, $extensionKeyField
            invoke-virtual { v0, v1 }, $hasWatchEndpointMethod
            move-result v2
            if-eqz v2, :no_video_id
            invoke-virtual { v0, v1 }, $getWatchEndpointMethod
            move-result-object v0
            check-cast v0, $watchEndpointType
            iget-object v0, v0, $watchEndpointVideoIdField
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
            # Omit the optional text-to-speech annotation.
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

// Intercept requests for the Playlists folder and return its playlists to Android Auto

// Intercepts requests for the Playlists folder, which YTM would otherwise leave empty.
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
    // YTM passes null for the optional second argument when returning an empty list.
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
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->handleAndroidAutoPlaylists($EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", handleAndroidAutoRequestMethod.getInstruction<Instruction>(0)),
    )
}

private fun BytecodePatchContext.patchAndroidAutoPodcastItems() {
    val androidAutoRequestType =
        SendEmptyAndroidAutoMediaItemsFingerprint.originalMethod.parameterTypes.first().toString()
    val deliverAndroidAutoMediaItemsMethod = mutableClassDefBy(androidAutoRequestType).methods.single { method ->
        method.returnType == "V" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes.first().toString() == "Ljava/util/List;" &&
            method.parameterTypes.last().toString().startsWith("L")
    }

    deliverAndroidAutoMediaItemsMethod.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->restoreAndroidAutoPodcastItems(${EXTENSION_ANDROID_AUTO_PLAYLISTS_REQUEST_INTERFACE}Ljava/util/List;)Ljava/util/List;
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
    val delegateClass = classDefBy(delegateField.type)
    // Use the callback's Handler so playlist playback runs on YTM's playback thread.
    val handlerField = delegateClass.fields.singleOrNull { field ->
        runCatching { classDefBy(field.type).superclass == "Landroid/os/Handler;" }
            .getOrDefault(false)
    } ?: throw PatchException("Could not find media session callback Handler")
    val ownerReferenceField = delegateClass.fields.singleOrNull { field ->
        field.type == "Ljava/lang/ref/WeakReference;"
    } ?: throw PatchException("Could not find media session callback owner")
    val playbackStateSetter = MediaSessionCompatPlaybackStateSetterFingerprint.method
    val sessionClass = mutableClassDefBy(playbackStateSetter.definingClass)
    val cachedStateField = playbackStateSetter.instructions.asSequence()
        .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
        .singleOrNull { field ->
            field.type == "Landroid/support/v4/media/session/PlaybackStateCompat;"
        } ?: throw PatchException("Could not find cached compat playback state")
    // 9.15 stores playback state on the session itself; later versions use an inner object.
    val ownerField = if (cachedStateField.definingClass == sessionClass.type) {
        null
    } else {
        playbackStateSetter.instructions.asSequence()
            .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .distinct()
            .singleOrNull { field ->
                field.definingClass == sessionClass.type && field.type == "Ljava/lang/Object;"
            } ?: throw PatchException("Could not find compat playback session owner")
    }
    sessionClass.interfaces.add(EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE)
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(
            EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE,
            "patch_getPlaybackOwner",
        ),
        registerCount = 2,
        instructions = if (ownerField == null) {
            "return-object p0"
        } else {
            """
                iget-object v0, p0, $ownerField
                return-object v0
            """
        },
    )
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(
            EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE,
            "patch_getPlaybackState",
        ),
        registerCount = 2,
        instructions = if (ownerField == null) {
            """
                iget-object v0, p0, $cachedStateField
                return-object v0
            """
        } else {
            """
                iget-object v0, p0, $ownerField
                check-cast v0, ${cachedStateField.definingClass}
                iget-object v0, v0, $cachedStateField
                return-object v0
            """
        },
    )
    sessionClass.addInterfaceMethod(
        extensionInterfaceMethod(
            EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE,
            "patch_setPlaybackState",
        ),
        registerCount = 2,
        instructions = """
            invoke-virtual { p0, p1 }, $playbackStateSetter
            return-void
        """,
    )
    if (ownerField != null) {
        // The callback only holds the inner object. Remember its session so the extension can
        // use YTM's playback-state setter to show the empty-playlist message.
        val setCallbackMethod = sessionClass.methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    delegateField.type, "Landroid/os/Handler;",
                )
        } ?: throw PatchException("Could not find compat media session callback setup")
        setCallbackMethod.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerPlaybackSession($EXTENSION_PLAYBACK_STATE_SESSION_INTERFACE)V",
        )
    }
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
            iget-object v0, v0, $ownerReferenceField
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
    // YTM cannot decode this patch's media IDs; resolve them before its playback handler runs.
    val handledRegister = playFromMediaIdMethod.findFreeRegister(0)
    playFromMediaIdMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->handlePlayFromMediaId(Landroid/media/session/MediaSession${'$'}Callback;Ljava/lang/String;Landroid/os/Bundle;)Z
            move-result v$handledRegister
            if-eqz v$handledRegister, :resume
            return-void
        """,
        ExternalLabel("resume", playFromMediaIdMethod.getInstruction<Instruction>(0)),
    )
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
