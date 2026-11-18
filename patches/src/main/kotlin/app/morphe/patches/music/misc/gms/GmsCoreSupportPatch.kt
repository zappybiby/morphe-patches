package app.morphe.patches.music.misc.gms

import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.fileprovider.fileProviderPatch
import app.morphe.patches.music.misc.gms.Constants.MORPHE_MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.playservice.is_9_45_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.PrimeMethodFingerprint
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch

val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = MUSIC_PACKAGE_NAME,
    toPackageNameDefault = MORPHE_MUSIC_PACKAGE_NAME,
    primeMethodFingerprint = PrimeMethodFingerprint,
    mainActivityOnCreateFingerprint = MusicActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
    useLegacyGnpFingerprint = { !is_9_45_or_greater },
    executeBlock = {
        allowCastingToAudioOnlyDevices()
        hidePlayServicesCastRoutes()
        sendQueueMovesToReceiver()
        skipQueueRestorationWhileCasting()
        searchDeviceFilesWithMediaProvider()

        // Hide YouTube Music's stock Settings button by omitting its PendingIntent from the media browser root extras.
        // Android Auto's MediaSettingsStub.startCarApp() rejects the sideloaded settings template,
        // so tapping the stock button crashes every supported version of YouTube Music.
        AndroidAutoSettingsIntentFingerprint.let {
            it.method.removeInstruction(it.instructionMatches.last().index)
        }
        mutableClassDefBy("Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab;")
            .methods.single { it.name == "replacesStockSettings" }.returnEarly(true)
    },
) {
    dependsOn(
        spoofVideoStreamsPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
}

private fun gmsCoreSupportResourcePatch() =
    app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
        fromPackageName = MUSIC_PACKAGE_NAME,
        toPackageNameDefault = MORPHE_MUSIC_PACKAGE_NAME,
        spoofedPackageSignature = "afb0fed5eeaebdd86f56a97742f4b6b33ef59875",
        screen = PreferenceScreen.MISC,
        block = {
            dependsOn(
                settingsPatch,
                fileProviderPatch(
                    MUSIC_PACKAGE_NAME,
                    MORPHE_MUSIC_PACKAGE_NAME
                )
            )
        }
    )
