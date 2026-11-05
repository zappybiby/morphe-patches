/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.util.Log;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class RememberShuffleStatePatch {

    // Queue edits can turn native shuffle off. Restore once per queue so later tracks preserve those edits.
    private static int queueGeneration;
    private static int restoredQueueGeneration = -1;

    /**
     * Injection point.
     */
    public static void onQueueCleared() {
        Utils.runOnMainThreadNowOrLater(() -> queueGeneration++);
    }

    /**
     * Injection point.
     */
    public static void saveShuffleState(Enum<?> shuffleState) {
        if (Settings.REMEMBER_SHUFFLE_STATE.get()) {
            Settings.SAVED_SHUFFLE_STATE.save(shuffleState.ordinal() == 1);
        }
    }

    /**
     * Injection point.
     */
    public static void applySavedShuffleState(String videoId) {
        if (!Settings.REMEMBER_SHUFFLE_STATE.get() || !Settings.SAVED_SHUFFLE_STATE.get()) {
            return;
        }

        Utils.runOnMainThreadNowOrLater(() -> {
            final int generation = queueGeneration;
            if (restoredQueueGeneration == generation) return;
            restoredQueueGeneration = generation;

            Utils.runOnMainThreadDelayed(() -> {
                if (queueGeneration == generation && !shuffleTracks()) {
                    restoredQueueGeneration = -1;
                }
            }, 500);
        });
    }

    /**
     * Injection point.
     */
    public static boolean shuffleTracks() {
        // Method is modified during patching.
        Log.d("Morphe: RememberShuffle", "Tracks are shuffled");
        return false;
    }
}
