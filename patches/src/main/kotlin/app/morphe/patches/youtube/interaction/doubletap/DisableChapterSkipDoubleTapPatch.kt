package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_13_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableDoubleTapActionsPatch;"

@Suppress("unused")
val disableDoubleTapActionsPatch = bytecodePatch(
    name = "Disable double tap actions",
    description = "Restores double-tap chapter skipping and adds an option to disable it.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_chapter_skip_double_tap", summary = true),
        )

        if (is_21_13_or_greater) {
            // Allow native chapter selection. The existing disable setting still applies to each gesture.
            ChapterSkipDisabledFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index, false
                )
            }
        }

        val doubleTapInfoGetSeekSourceFingerprint = Fingerprint(
            classFingerprint = DoubleTapInfoCtorFingerprint,
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = SeekTypeEnumFingerprint.originalClassDef.type,
            parameters = listOf("Z")
        )

        // Force isChapterSeek flag to false.
        doubleTapInfoGetSeekSourceFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                move-result p1
            """
        )

        DoubleTapInfoCtorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p3 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                move-result p3
            """
        )
    }
}
