/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val BROWSE_CONTENTS_EXTENSION_FIELD_NUMBER = 58_173_949L
private const val BROWSE_SECTION_EXTENSION_FIELD_NUMBER = 58_174_010L
private const val BROWSE_SECTION_PRESENT_FLAG = 1L
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595L

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

internal object AndroidAutoMediaItemMapperFingerprint : Fingerprint(
    returnType = "Lj$/util/Optional;",
    parameters = listOf("L", "Ljava/util/Set;", "L"),
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    // Three MediaDescriptionCompat constructor calls cover items that open, play, or do both.
    custom = { method, _ ->
        method.findInstructionIndicesReversed(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL).size == 3
    },
)

internal object AndroidAutoMediaIdValidationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

internal fun androidAutoLoadChildrenFingerprint(
    controllerType: String,
    loadResultType: String,
) = Fingerprint(
    definingClass = controllerType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(loadResultType),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal object BrowseRequestBuilderFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
        ),
        string("FEmusic_home", location = MatchAfterImmediately()),
    ),
    // FEmusic_home also appears in a lambda that returns Object, so ignore that match.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)

internal fun authenticatedBrowseRequestFingerprint(
    serviceType: String,
    builderType: String,
) = Fingerprint(
    definingClass = serviceType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf(builderType, "Ljava/util/concurrent/Executor;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = builderType,
            type = "Ljava/lang/String;",
        ),
    ),
)

internal fun browseIdSetterFingerprint(field: FieldReference) = Fingerprint(
    definingClass = field.definingClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = field.definingClass,
            name = field.name,
            type = field.type,
        ),
    ),
)

internal object BrowseResponseContentsFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(
        literal(BROWSE_CONTENTS_EXTENSION_FIELD_NUMBER),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "filter",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Lj$/util/stream/Stream;",
        ),
        newInstance("L", location = MatchAfterWithin(2)),
    ),
)

internal fun browseContentMapperFingerprint(mapperType: String) = Fingerprint(
    definingClass = mapperType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("L"),
        literal(
            BROWSE_SECTION_EXTENSION_FIELD_NUMBER,
            location = MatchAfterWithin(3),
        ),
    ),
)

internal fun browseContentSectionFingerprint(contentType: String) = Fingerprint(
    definingClass = contentType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = emptyList(),
    filters = listOf(literal(BROWSE_SECTION_PRESENT_FLAG)),
)

internal fun browseSectionItemsFingerprint(
    sectionType: String,
    returnType: String,
) = Fingerprint(
    definingClass = sectionType,
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.FINAL,
        AccessFlags.DECLARED_SYNCHRONIZED,
    ),
    returnType = returnType,
    parameters = emptyList(),
)

internal object MusicResponsiveRendererExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

// handleMusicReloadShelfEvent identifies the class that decodes playlist rows.
internal object PlaylistRendererDecoderClassFingerprint : Fingerprint(
    name = "handleMusicReloadShelfEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
)

internal object PlaylistRendererDecoderFingerprint : Fingerprint(
    classFingerprint = PlaylistRendererDecoderClassFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        // Flags 0x1000 and 0x40000 identify the playlist-row decoder; the other List decoder uses 0x1 and 0x2.
        literal(0x1000L),
        literal(0x40000L),
    ),
)

internal fun artworkUrlsFingerprint(artworkType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf(artworkType),
)

internal fun renderTextFingerprint(textType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/text/Spanned;",
    parameters = listOf(textType, "Ljava/lang/String;"),
)

internal fun browseEndpointDecoderFingerprint(
    endpointType: String,
    decodedEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = decodedEndpointType,
    parameters = listOf(endpointType),
)

internal object PlaylistPlaybackMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z"),
            returnType = "L",
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Ljava/lang/String;",
            location = MatchAfterWithin(3),
        ),
    ),
)
