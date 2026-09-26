/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Locates YTM's existing methods and data types for the Android Auto patches.
 * [bypassCertificateChecksPatch] and [supportAndroidAutoPatch] use these matches
 * to add hooks or call YTM's methods.
 *
 * Certificate checks: [CheckCertificateFingerprint], [IsGoogleSignedFingerprint].
 * Library and playlist requests: [PhoneBrowseRequestFromEndpointFingerprint], [PhoneBrowseResponseTabsFingerprint].
 * Library pagination: [gridPaginationCommandsFingerprint], [LibraryPaginationDecoderFingerprint].
 * Titles and artwork: [formatTextFingerprint], [androidAutoMediaDescriptionFingerprint].
 * Playlist playback: [AndroidAutoPlayFromMediaIdFingerprint], [decodeButtonRendererFingerprint].
 * Playlist edits: [EditPlaylistRequestFingerprint], [playlistEditFutureFingerprint].
 */

private const val PHONE_BROWSE_TABS_PROTO_FIELD = 58_173_949L
private const val TAB_RENDERER_PROTO_FIELD = 58_174_010L
private const val TAB_CONTENT_PRESENT_FLAG = 1L
private const val SECTION_LIST_CONTENTS_FIELD_NAME = "f"
private const val PHONE_BROWSE_ITEM_PROTO_FIELD = 161_429_595L
private const val GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG = 0x40000L
private const val NEXT_COMMAND_PRESENT_FLAG = 0x1L
private const val RELOAD_COMMAND_PRESENT_FLAG = 0x2L
private const val PLAY_BUTTON_PROTO_FIELD = 65_153_809L
private const val THUMBNAIL_PROTO_FIELD = 164_480_666L
private const val WATCH_ENDPOINT_PROTO_FIELD = 48_687_757L

// Bypass certificate checks

internal object CheckCertificateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L"),
    strings = listOf(
        "X509",
        "isPartnerSHAFingerprint"
    )
)

/**
 * Anchors [IsGoogleSignedFingerprint] to the class that contains the remote
 * Google-certificates fetch logic.
 */
internal object GoogleCertificatesRemoteFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Failed to get Google certificates from remote")
)

/**
 * [GoogleSignatureVerifier.c(String)][defpackage.tcn.c] — the boolean entry-point
 * that [AllowlistManager.g][defpackage.kxo.g] calls to decide whether the caller
 * is Google-signed.  Scoped to [GoogleCertificatesRemoteFingerprint] so the
 * patcher never picks up an unrelated `(String)→boolean` method.
 */
internal object IsGoogleSignedFingerprint : Fingerprint(
    classFingerprint = GoogleCertificatesRemoteFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;")
)

// Identify the Playlists folder and how YTM returns its contents to Android Auto

/**
 * Matches the constructor that stores an Android Auto item's ID, title, and artwork.
 * [BuildAndroidAutoMediaItemFingerprint] uses it to find where YTM creates the Playlists folder;
 * [androidAutoMediaDescriptionFingerprint] uses it to find YTM's artwork conversion.
 */
internal val MEDIA_DESCRIPTION_CONSTRUCTOR_CALL = methodCall(
    definingClass = "Landroid/support/v4/media/MediaDescriptionCompat;",
    name = "<init>",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Landroid/graphics/Bitmap;",
        "Landroid/net/Uri;",
        "Landroid/os/Bundle;",
        "Landroid/net/Uri;",
    ),
    returnType = "V",
)

/** Creates Android Auto media items, including the Playlists folder. */
internal object BuildAndroidAutoMediaItemFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        // Three constructor calls cover media items that can be opened, played, or both.
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

/** YTM's handler for invalid Android Auto media IDs. */
internal object SendEmptyAndroidAutoMediaItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: "),
)

// Refresh after playlist edits

/** YTM's request for adding or removing playlist songs. */
internal object EditPlaylistRequestFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    strings = listOf("browse/edit_playlist"),
)

/** Sends a playlist edit and returns a future reporting completion. */
internal fun playlistEditFutureFingerprint(requestType: String) = Fingerprint(
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(requestType, "Ljava/util/concurrent/Executor;"),
)

/** Refreshes the requested Android Auto list through its existing connection. */
internal fun mediaBrowserReloadFingerprint(baseServiceType: String) = Fingerprint(
    definingClass = baseServiceType,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    strings = listOf("onLoadChildren must call detach() or sendResult() before returning for package="),
)

// Play a selected playlist: callbacks

/** Receives an Android Auto selection to start playback. */
internal object AndroidAutoPlayFromMediaIdFingerprint : Fingerprint(
    name = "onPlayFromMediaId",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Landroid/os/Bundle;"),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/media/session/MediaSession\$Callback;"
    },
)

/** Updates YTM's playback status and sends it to Android Auto. */
internal object MediaSessionCompatPlaybackStateSetterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/support/v4/media/session/PlaybackStateCompat;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setPlaybackState",
            parameters = listOf("Landroid/media/session/PlaybackState;"),
        ),
    ),
)

// Request Library and playlist pages through YTM

/** Initializes MusicBrowserService with the objects it needs to load Library and playlist data. */
internal fun musicBrowserServiceSuperclassOnCreateFingerprint(
    musicBrowserServiceType: String,
    generatedComponentType: String,
) = Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            name = "generatedComponent",
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = generatedComponentType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = musicBrowserServiceType,
        ),
    ),
)

/** Obtains YTM's object for sending Library and playlist requests. */
internal fun phoneBrowseRequestsProviderFingerprint(
    phoneBrowseRequestsType: String,
) = Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;",
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE),
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(phoneBrowseRequestsType, location = MatchAfterImmediately()),
    ),
)

/** Creates a request for the phone's Library, Home, or a playlist. */
internal object PhoneBrowseRequestFromEndpointFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
        ),
        // FEmusic_home identifies the phone's Home page; YTM compares it with the ID read above.
        string("FEmusic_home", location = MatchAfterImmediately()),
    ),
    // Exclude the other method that compares the phone Home page ID and returns Object.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" },
)

/** Sends a Library or playlist request and returns a future for the response. */
internal fun sendPhoneBrowseRequestFingerprint(
    phoneBrowseRequestsType: String,
    phoneBrowseRequestType: String,
) = Fingerprint(
    definingClass = phoneBrowseRequestsType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(phoneBrowseRequestType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = phoneBrowseRequestType,
            type = "Ljava/lang/String;",
        ),
    ),
)

/** Sets the Library or playlist page ID on a YTM request. */
internal fun setRequestBrowseIdFingerprint(requestBrowseIdField: FieldReference) = Fingerprint(
    definingClass = requestBrowseIdField.definingClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = requestBrowseIdField.definingClass,
            name = requestBrowseIdField.name,
            type = requestBrowseIdField.type,
        ),
    ),
)

// Read the contents returned by Library and playlist requests
// TabRenderer contains sections; each can hold a Library grid (GridRenderer) or playlist contents.

/** Reads the tab data in YTM's Library or playlist response, even without a visible tab bar. */
internal object PhoneBrowseResponseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(PHONE_BROWSE_TABS_PROTO_FIELD),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
)

/** Creates the YTM object used to read Library items or playlist songs from tab data. */
internal fun createPhoneBrowseTabFingerprint(tabMapperType: String) = Fingerprint(
    definingClass = tabMapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("L"),
        literal(
            TAB_RENDERER_PROTO_FIELD,
            location = MatchAfterWithin(3),
        ),
    ),
)

/** Reads the section list that holds a tab's Library items or playlist songs. */
internal fun getSectionListFingerprint(tabWrapperType: String) = Fingerprint(
    definingClass = tabWrapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        // YTM checks this bit before reading the lists stored in the tab.
        literal(TAB_CONTENT_PRESENT_FLAG),
    ),
)

/** Returns the Library lists or playlist song lists stored in the page's sections. */
internal fun sectionListContentsFingerprint(
    sectionListType: String,
    sectionContentsType: String,
) = Fingerprint(
    definingClass = sectionListType,
    returnType = sectionContentsType,
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            // Field f contains the Library grid or playlist song list.
            name = SECTION_LIST_CONTENTS_FIELD_NAME,
        ),
    ),
)

// Read individual Library and playlist items

/** YTM's shared item type for playlists, songs, and the Add a song button. */
internal object PhoneBrowseItemFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            PHONE_BROWSE_ITEM_PROTO_FIELD,
            location = MatchAfterWithin(2),
        ),
    ),
)

/** Identifies the class that updates the phone's Library list and reads pagination responses. */
internal object HandleMusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

/** Reads Library items, including playlists, artists, and podcasts. */
internal object GridRendererItemsFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(literal(GRID_PHONE_BROWSE_ITEM_PRESENT_FLAG)),
)

// Library pagination commands

/** Reads Library pagination commands. */
internal fun gridPaginationCommandsFingerprint(getGridItemsMethod: Method) = Fingerprint(
    definingClass = getGridItemsMethod.definingClass,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = getGridItemsMethod.parameterTypes.map(CharSequence::toString),
    filters = listOf(
        // NEXT (0x1) requests the next Library page; RELOAD (0x2) refreshes the list.
        literal(NEXT_COMMAND_PRESENT_FLAG),
        literal(RELOAD_COMMAND_PRESENT_FLAG),
    ),
    custom = { method, _ -> method != getGridItemsMethod },
)

// Read a selected playlist's songs

/** Reads a playlist's songs and Add a song button. */
internal fun playlistItemsFingerprint(phoneBrowseItemType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "Z"),
    filters = listOf(checkCast(phoneBrowseItemType)),
)

// Library pagination responses

/** Reads the Library items returned by pagination. */
internal object LibraryPaginationDecoderFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC,
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
)

// Playlist titles and artwork

/** YTM's artwork data for playlists and songs. */
internal object PhoneBrowseItemThumbnailFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            THUMBNAIL_PROTO_FIELD,
            location = MatchAfterWithin(2),
        ),
    ),
)

/** Decodes the artwork data stored on a playlist or song. */
internal fun decodeThumbnailFingerprint(thumbnailFieldType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf(thumbnailFieldType),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
            name = "getGeneratedRegistry",
            parameters = emptyList(),
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
        ),
    ),
)

/** Creates an Android Auto item and converts its artwork to an image URI. */
internal fun androidAutoMediaDescriptionFingerprint(
    thumbnailFieldTypes: Set<String>,
) = Fingerprint(
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions
        if (method.parameterTypes.size != 1 || instructions == null) {
            false
        } else {
            val readFieldTypes = instructions
                .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
                .toSet()
            instructions
                .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                .filter { reference -> reference.returnType == "Landroid/net/Uri;" }
                .mapNotNull { reference ->
                    reference.parameterTypes.singleOrNull()?.toString()
                }
                .any { parameterType ->
                    parameterType in thumbnailFieldTypes && parameterType in readFieldTypes
                }
        }
    },
)

/** Converts playlist and song titles or subtitles into display text. */
internal fun formatTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

// Read the playlist ID from an item's command

/** Reads the BrowseEndpoint, which identifies the YTM page a command opens. */
internal fun browseEndpointFromCommandFingerprint(
    commandType: String,
    browseEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = browseEndpointType,
    parameters = listOf(commandType),
)

// Encode item commands for Android Auto

/** Encodes an item's command as a media ID accepted by Android Auto. */
internal object EncodeCommandMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    custom = { method, _ ->
        val commandType = method.parameterTypes.single().toString()
        if (commandType == "Ljava/lang/String;") {
            false
        } else {
            // YTM stores the command in another object, then converts that object to a media ID string.
            val commandWrapperType = method.instructions
                .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
                .filter { field -> field.type == commandType }
                .distinct()
                .singleOrNull()
                ?.definingClass
            commandWrapperType != null && method.instructions
                .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                .any { reference ->
                    reference.parameterTypes.map(CharSequence::toString) ==
                        listOf(commandWrapperType) &&
                        reference.returnType == "Ljava/lang/String;"
                }
        }
    },
)

// Check playlist contents

/** WatchEndpoint: YTM's data identifying the song or video to play. */
internal object WatchEndpointExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(WATCH_ENDPOINT_PROTO_FIELD)),
)

// Read the command from the Play button above the playlist's songs

/** ButtonRenderer: YTM's data for buttons, including the Play button above a playlist's songs. */
internal fun playButtonRendererFingerprint(commandType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAY_BUTTON_PROTO_FIELD)),
    // FeedbackEndpoint uses the same field number for a command. The Play button has a different data type.
    custom = { method, _ ->
        val playButtonMessageType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        playButtonMessageType != null && playButtonMessageType != commandType
    },
)

/** Reads the Play button from the playlist data containing it. */
internal fun decodeButtonRendererFingerprint(
    playButtonContainerType: String,
    buttonRendererType: String,
    playButtonExtensionField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = buttonRendererType,
    parameters = listOf("Z", playButtonContainerType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = playButtonExtensionField.definingClass,
            name = playButtonExtensionField.name,
            type = playButtonExtensionField.type,
        ),
    ),
)

/**
 * The playlist Play button and live chat button store their commands in the same field of YTM's button data.
 * Use the live chat button's code to identify that field.
 */
internal fun buttonRendererCommandCopyFingerprint(
    buttonRendererType: String,
    commandType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = buttonRendererType,
            type = commandType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = commandType,
            location = MatchAfterWithin(4),
        ),
    ),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val copiedCommandFields = instructions.mapIndexedNotNull { index, instruction ->
            val buttonField = instruction.getReference<FieldReference>()
            if (instruction.opcode == Opcode.IGET_OBJECT &&
                buttonField?.definingClass == buttonRendererType &&
                buttonField.type == commandType
            ) {
                val commandCopied = instructions.drop(index + 1).take(4).any { nearby ->
                    val target = nearby.getReference<FieldReference>()
                    nearby.opcode == Opcode.IPUT_OBJECT &&
                        target?.definingClass != buttonRendererType &&
                        target?.type == commandType
                }
                buttonField.takeIf { commandCopied }
            } else {
                null
            }
        }.distinct()
        copiedCommandFields.size == 1
    },
)
