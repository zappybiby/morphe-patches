/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val BROWSE_CONTENTS_EXTENSION_FIELD_NUMBER = 58_173_949L
private const val BROWSE_SECTION_EXTENSION_FIELD_NUMBER = 58_174_010L
private const val BROWSE_SECTION_PRESENT_FLAG = 1L
private const val MUSIC_RESPONSIVE_RENDERER_EXTENSION_FIELD_NUMBER = 161_429_595L
private const val PLAYLIST_HEADER_EXTENSION_FIELD_NUMBER = 65_153_809L
private const val PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER = 52_666_186L
private const val PLAYLIST_ARTWORK_EXTENSION_FIELD_NUMBER = 164_480_666L

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

internal fun androidAutoControllerProviderFingerprint(controllerType: String) = Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        methodCall(
            definingClass = controllerType,
            name = "<init>",
            returnType = "V",
        ),
    ),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal fun browseServiceProviderAccessFingerprint(serviceType: String) = Fingerprint(
    filters = listOf(opcode(Opcode.CHECK_CAST)),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                instruction.getReference<TypeReference>()?.type == serviceType
        } == true
    },
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

internal object PlaylistContinuationResponseDecoderFingerprint : Fingerprint(
    classFingerprint = PlaylistRendererDecoderClassFingerprint,
    accessFlags = listOf(
        AccessFlags.PROTECTED,
        AccessFlags.FINAL,
        AccessFlags.BRIDGE,
        AccessFlags.SYNTHETIC,
    ),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L"),
)

internal object PlaylistArtworkExtensionFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.CONST_CLASS),
        literal(
            PLAYLIST_ARTWORK_EXTENSION_FIELD_NUMBER,
            location = MatchAfterWithin(2),
        ),
    ),
)

internal fun generatedExtensionDecoderFingerprint(artworkType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf(artworkType),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
            name = "getGeneratedRegistry",
            parameters = emptyList(),
            returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
        ),
    ),
)

internal fun androidAutoPlaylistArtworkFingerprint(
    payloadFieldTypes: Set<String>,
) = Fingerprint(
    filters = listOf(MEDIA_DESCRIPTION_CONSTRUCTOR_CALL),
    custom = { method, _ ->
        if (method.parameterTypes.size != 1) return@Fingerprint false
        val instructions = method.implementation?.instructions ?: return@Fingerprint false
        val readFieldTypes = instructions
            .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>()?.type }
            .toSet()
        instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.returnType == "Landroid/net/Uri;" &&
                    reference.parameterTypes.size == 1 &&
                    reference.parameterTypes.single().toString() in payloadFieldTypes &&
                    reference.parameterTypes.single().toString() in readFieldTypes
            } == true
        }
    },
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

internal object NativeEndpointMediaIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    custom = { method, _ ->
        val endpointType = method.parameterTypes.single().toString()
        val endpointStores = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .filter { field -> field.type == endpointType }
            .distinct()
        endpointType != "Ljava/lang/String;" && endpointStores.size == 1 &&
            endpointStores.single().definingClass.let { wrapperType ->
                method.instructions.any { instruction ->
                    instruction.getReference<MethodReference>()
                        ?.let { reference ->
                            reference.parameterTypes.map(CharSequence::toString) ==
                                listOf(wrapperType) &&
                                reference.returnType == "Ljava/lang/String;"
                        } == true
                }
            }
    },
)

internal fun playlistHeaderExtensionFingerprint(endpointType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAYLIST_HEADER_EXTENSION_FIELD_NUMBER)),
    custom = { method, _ ->
        val containingType = method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .firstOrNull { field -> field.definingClass == field.type }
            ?.type
        containingType != null && containingType != endpointType
    },
)

internal fun playlistEndpointExtensionFingerprint(endpointType: String) = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(literal(PLAYLIST_ENDPOINT_EXTENSION_FIELD_NUMBER)),
    custom = { method, _ ->
        method.instructions
            .filter { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { instruction -> instruction.getReference<FieldReference>() }
            .any { field -> field.definingClass == field.type && field.type == endpointType }
    },
)

internal fun playlistEndpointIdFieldFingerprint(
    endpointType: String,
    playlistEndpointType: String,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(endpointType),
    custom = { method, _ ->
        method.instructions.any { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.let { field ->
                    field.definingClass == playlistEndpointType &&
                        field.type == "Ljava/lang/String;"
                } == true
        }
    },
)

internal fun playlistHeaderDecoderFingerprint(
    containingType: String,
    headerType: String,
    descriptorField: FieldReference,
) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = headerType,
    parameters = listOf("Z", containingType),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = descriptorField.definingClass,
            name = descriptorField.name,
            type = descriptorField.type,
        ),
    ),
)

internal fun playlistHeaderPlayEndpointFingerprint(
    headerType: String,
    endpointType: String,
) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    custom = { method, _ ->
        method.implementation?.instructions?.toList()?.let { instructions ->
            val endpointReads = instructions.mapIndexedNotNull { index, instruction ->
                instruction.getReference<FieldReference>()?.takeIf { field ->
                    instruction.opcode == Opcode.IGET_OBJECT &&
                        field.definingClass == headerType && field.type == endpointType &&
                        instructions.drop(index + 1).take(4).any { nearby ->
                            nearby.opcode == Opcode.IPUT_OBJECT &&
                                nearby.getReference<FieldReference>()?.let { target ->
                                    target.definingClass != headerType && target.type == endpointType
                                } == true
                        }
                }
            }
                .distinct()
            endpointReads.size == 1
        } == true
    },
)
