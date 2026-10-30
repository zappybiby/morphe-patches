/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.swipecontrols;

import android.app.Activity;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController;
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController;
import app.morphe.extension.youtube.swipecontrols.views.SwipeControlsOverlayLayout;

public final class SwipeControlsHostActivity extends Activity {
    public final SwipeControlsConfigurationProvider config = new SwipeControlsConfigurationProvider();
    public final SwipeZonesController zones = new SwipeZonesController();
    public final SwipeControlsOverlayLayout overlay = new SwipeControlsOverlayLayout();
    public final List<MotionEvent> nativeEvents = new ArrayList<>();
    public final List<String> actions = new ArrayList<>();
    public final List<String> timeline = new ArrayList<>();
    public boolean nativeResult = true;
    public boolean statusBar;
    public boolean splitScreen;
    public boolean controlsVisible;
    public int nativeFullscreenExitDistance;
    public int nativeFullscreenExits;
    public int nativeFullscreenRestores;
    private float nativeStartX, nativeStartY;
    private boolean nativeExitedFullscreen;
    public static boolean locked;
    public SwipeControlsConfigurationProvider getConfig() { return config; }
    public SwipeZonesController getZones() { return zones; }
    public AudioVolumeController getAudio() { return null; }
    public ScreenBrightnessController getScreen() { return null; }
    public SwipeControlsOverlayLayout getOverlay() { return overlay; }
    public boolean isStatusBarVisible() { return statusBar; }
    public boolean isInSplitScreenMode() { return splitScreen; }
    public static boolean isPlayerLocked() { return locked; }
    public boolean dispatchDownstreamTouchEvent(MotionEvent event) {
        nativeEvents.add(MotionEvent.borrowedCopy(event));
        timeline.add("native:" + event.getActionMasked());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            nativeStartX = event.getX(); nativeStartY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && nativeFullscreenExitDistance > 0
                && (config.fullscreen || config.sliding) && Math.max(Math.abs(event.getX() - nativeStartX),
                        Math.abs(event.getY() - nativeStartY)) >= nativeFullscreenExitDistance) {
            config.fullscreen = false;
            config.sliding = false;
            nativeExitedFullscreen = true;
            nativeFullscreenExits++;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && nativeExitedFullscreen) {
            config.fullscreen = true;
            nativeExitedFullscreen = false;
            nativeFullscreenRestores++;
        }
        return nativeResult;
    }
}
