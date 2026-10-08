/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.audio

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_32_or_greater
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.matchSingle
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val enableExclusiveAudioPlaybackPatch = bytecodePatch(
    name = "Enable exclusive audio playback",
    description = "Enables the option to play audio without video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val fingerprint = if (is_9_32_or_greater) {
            AllowExclusiveAudioPlaybackFingerprint
        } else {
            AllowExclusiveAudioPlaybackLegacyFingerprint
        }

        fingerprint.method.returnEarly(true)
        patchExternalAudioPlayback()
    }
}

/**
 * Prevents podcast episodes from stopping on load in Android Auto. YTM tries to skip episodes
 * marked FEATURE_AVAILABILITY_BLOCKED ("Audio-only not available"), then stops if the skip request
 * returns "action unavailable" (11). Pressing Play on the phone can resume playback; this override
 * avoids that extra step.
 */
private fun BytecodePatchContext.patchExternalAudioPlayback() {
    val stopMethod = StopMusicMediaSessionFingerprint.matchSingle().originalMethod
    val match = externalAudioPlaybackRestrictionFingerprint(stopMethod).matchSingle()
    val result = match.instructionMatches[1]
    val resultRegister = result.getInstruction<OneRegisterInstruction>().registerA
    val branchRegister = match.instructionMatches[2].getInstruction<OneRegisterInstruction>().registerA
    if (resultRegister != branchRegister) {
        throw PatchException("Playback restriction result and branch use different registers")
    }

    // Keep the override at this call site: YTM also uses the shared method for the Audio/Video switch.
    match.method.replaceInstruction(result.index, "const/16 v$resultRegister, 0x0")
}
