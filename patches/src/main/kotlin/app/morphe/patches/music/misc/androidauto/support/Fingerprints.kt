/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.support

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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val BROWSE_TABS_PROTO_FIELD = 58_173_949L
private const val TAB_RENDERER_PROTO_FIELD = 58_174_010L
private const val TAB_CONTENT_PRESENT_FLAG = 1L
private const val SECTION_LIST_CONTENTS_FIELD_NAME = "f"
private const val SHARED_BROWSE_ROW_PROTO_FIELD = 161_429_595L
private const val GRID_SHARED_BROWSE_ROW_PRESENT_FLAG = 0x40000L
private const val NEXT_ACTION_PRESENT_FLAG = 0x1L
private const val RELOAD_ACTION_PRESENT_FLAG = 0x2L
private const val PLAY_BUTTON_PROTO_FIELD = 65_153_809L
private const val THUMBNAIL_PROTO_FIELD = 164_480_666L
private const val WATCH_ENDPOINT_PROTO_FIELD = 48_687_757L

// Android Auto

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

internal object BuildAndroidAutoMediaItemFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        // YTM has separate FLAG_BROWSABLE, FLAG_PLAYABLE, and combined construction paths.
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

internal object SendEmptyAndroidAutoMediaItemsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

// PlaylistEditService sends both song additions and removals through this request.
internal object EditPlaylistRequestFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    strings = listOf("browse/edit_playlist"),
)

internal fun playlistEditFutureFingerprint(requestType: String) = Fingerprint(
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(requestType, "Ljava/util/concurrent/Executor;"),
)

// The compatibility browser's reload method sends a new result to its subscribed client.
internal fun mediaBrowserReloadFingerprint(baseServiceType: String) = Fingerprint(
    definingClass = baseServiceType,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    strings = listOf("onLoadChildren must call detach() or sendResult() before returning for package="),
)

// Intercept playlist selections before YTM decodes the media ID.
internal object AndroidAutoPlayFromMediaIdFingerprint : Fingerprint(
    name = "onPlayFromMediaId",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Landroid/os/Bundle;"),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/media/session/MediaSession\$Callback;"
    },
)

// YTM's playback-state setter updates its own copy and notifies Android.
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

// Phone Browse requests

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

// FEmusic_home anchors the request-building method, which also supports other page IDs.
internal object BrowseRequestFromEndpointFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
        ),
        string("FEmusic_home", location = MatchAfterImmediately()),
    ),
    // The Object-returning lambda only compares the ID; it does not create the request.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)

internal fun sendBrowseRequestFingerprint(
    phoneBrowseRequestsType: String,
    browseRequestType: String,
) = Fingerprint(
    definingClass = phoneBrowseRequestsType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(browseRequestType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = browseRequestType,
            type = "Ljava/lang/String;",
        ),
    ),
)

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

// Phone Browse responses

// YTM uses TabRenderer for Library and opened-playlist contents, even when no tab is visible.
internal object BrowseResponseTabsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(BROWSE_TABS_PROTO_FIELD),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
)

// Converts TabRenderer data into the objects returned by BrowseResponse.patch_getTabs.
internal fun createBrowseTabFingerprint(tabMapperType: String) = Fingerprint(
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

// This flag marks a tab with page contents, such as Library items or playlist songs.
internal fun getSectionListFingerprint(tabWrapperType: String) = Fingerprint(
    definingClass = tabWrapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(literal(TAB_CONTENT_PRESENT_FLAG)),
)

// The page contents use the fixed obfuscated field f.
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
            name = SECTION_LIST_CONTENTS_FIELD_NAME,
        ),
    ),
)

// Library playlists and opened-playlist rows

// The same protobuf row type represents Library playlists, songs, and editor buttons.
internal object SharedBrowseRowFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            SHARED_BROWSE_ROW_PROTO_FIELD,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal object HandleMusicReloadShelfEventFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

// Reads all Library items, including artists and podcasts that the extension must filter out.
internal object GridRendererRowsFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(literal(GRID_SHARED_BROWSE_ROW_PRESENT_FLAG)),
)

// NEXT (0x1) loads more Library items; RELOAD (0x2) refreshes the list.
internal fun gridContinuationActionsFingerprint(getGridRowsMethod: Method) = Fingerprint(
    definingClass = getGridRowsMethod.definingClass,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = getGridRowsMethod.parameterTypes.map(CharSequence::toString),
    filters = listOf(
        literal(NEXT_ACTION_PRESENT_FLAG),
        literal(RELOAD_ACTION_PRESENT_FLAG),
    ),
    custom = { method, _ -> method != getGridRowsMethod },
)

// False returns playlist rows, including editor buttons; true builds UI objects.
internal fun openedPlaylistRowsFingerprint(sharedBrowseRowType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "Z"),
    filters = listOf(opcode(Opcode.CHECK_CAST)),
    custom = { method, _ ->
        method.instructions.any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                instruction.getReference<TypeReference>()?.type == sharedBrowseRowType
        }
    },
)

// Reads additional Library pages, whether the list is at the response root or inside a section.
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

// Field 164480666 is the thumbnail on Library playlists and opened-playlist rows.
internal object SharedBrowseRowThumbnailFingerprint : Fingerprint(
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

// Find YTM's conversion from thumbnail data to Android Auto artwork URIs.
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

internal fun formatTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

// A BrowseEndpoint contains the page ID. Other action types make this converter throw.
internal fun browseEndpointFromActionFingerprint(
    actionType: String,
    browseEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = browseEndpointType,
    parameters = listOf(actionType),
)

// Editor-button actions can also be encoded; a media ID alone does not identify a song.
internal object EncodeActionMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    custom = { method, _ ->
        val actionType = method.parameterTypes.single().toString()
        if (actionType == "Ljava/lang/String;") {
            false
        } else {
            // YTM encodes media IDs by putting the action in a wrapper and serializing it.
            val actionWrapperType = method.instructions
                .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
                .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
                .filter { field -> field.type == actionType }
                .distinct()
                .singleOrNull()
                ?.definingClass
            actionWrapperType != null && method.instructions
                .mapNotNull { instruction -> instruction.getReference<MethodReference>() }
                .any { reference ->
                    reference.parameterTypes.map(CharSequence::toString) ==
                        listOf(actionWrapperType) &&
                        reference.returnType == "Ljava/lang/String;"
                }
        }
    },
)

// Opened playlist playback

// Editor buttons also have action IDs; require a WatchEndpoint video ID to identify a song.
internal object WatchEndpointExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(WATCH_ENDPOINT_PROTO_FIELD)),
)

// Field 65153809 contains the playlist's Play button data (ButtonRenderer).
internal fun playButtonRendererFingerprint(playActionType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAY_BUTTON_PROTO_FIELD)),
    // Exclude the FeedbackEndpoint initializer that uses the same protobuf field number.
    custom = { method, _ ->
        val playButtonMessageType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        playButtonMessageType != null && playButtonMessageType != playActionType
    },
)

internal fun decodeButtonRendererFingerprint(
    playlistHeaderType: String,
    buttonRendererType: String,
    playButtonExtensionField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = buttonRendererType,
    parameters = listOf("Z", playlistHeaderType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = playButtonExtensionField.definingClass,
            name = playButtonExtensionField.name,
            type = playButtonExtensionField.type,
        ),
    ),
)

// The live-chat button copies its click action out of ButtonRenderer.
internal fun buttonRendererActionCopyFingerprint(
    buttonRendererType: String,
    playActionType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = buttonRendererType,
            type = playActionType,
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = playActionType,
            location = MatchAfterWithin(4),
        ),
    ),
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val copiedActionFields = instructions.mapIndexedNotNull { index, instruction ->
            val buttonField = instruction.getReference<FieldReference>()
            if (instruction.opcode == Opcode.IGET_OBJECT &&
                buttonField?.definingClass == buttonRendererType &&
                buttonField.type == playActionType
            ) {
                val copiedToAction = instructions.drop(index + 1).take(4).any { nearby ->
                    val target = nearby.getReference<FieldReference>()
                    nearby.opcode == Opcode.IPUT_OBJECT &&
                        target?.definingClass != buttonRendererType &&
                        target?.type == playActionType
                }
                buttonField.takeIf { copiedToAction }
            } else {
                null
            }
        }.distinct()
        copiedActionFields.size == 1
    },
)
