/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController;
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController;
import android.content.Context;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsOverlay;

public final class VolumeAndBrightnessScroller {
    private final SwipeControlsHostActivity host;
    public VolumeAndBrightnessScroller(Context context, AudioVolumeController audio,
            ScreenBrightnessController screen, SwipeControlsOverlay overlay,
            int volumeDistance, int brightness, int speed, int step, boolean enabled) {
        this.host = (SwipeControlsHostActivity) context;
    }
    public void scrollVolume(double distance) { host.actions.add("volume"); host.timeline.add("volume"); }
    public void scrollBrightness(double distance) { host.actions.add("brightness"); host.timeline.add("brightness"); }
    public void scrollSpeed(double distance) { host.actions.add("speed"); host.timeline.add("speed"); }
    public void resetScroller() {}
}
