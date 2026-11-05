/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.remember.shufflestate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MusicPlaybackQueueFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("com/google/android/apps/youtube/music/player/queue/MusicPlaybackQueue")
    )
)

internal object LoadQueueFingerprint : Fingerprint(
    classFingerprint = MusicPlaybackQueueFingerprint,
    returnType = "V",
    parameters = listOf("L", "Z", "L"),
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = emptyList(),
            returnType = "V"
        )
    )
)

internal object ShuffleOnClickFingerprint : Fingerprint(
    name = "onClick",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        literal(45468L)
    )
)

internal object ShuffleEnumFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("SHUFFLE_OFF"),
        string("SHUFFLE_ALL"),
        string("SHUFFLE_DISABLED")
    )
)
