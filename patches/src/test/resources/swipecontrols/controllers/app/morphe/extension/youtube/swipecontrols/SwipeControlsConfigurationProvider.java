/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.swipecontrols;

public final class SwipeControlsConfigurationProvider {
    public enum SwipeZoneAction { OFF, VOLUME, BRIGHTNESS, SPEED }
    public boolean enabled = true;
    public boolean fullscreen = true;
    public boolean sliding;
    public boolean ignoreLocked = true;
    public SwipeZoneAction left = SwipeZoneAction.BRIGHTNESS;
    public SwipeZoneAction right = SwipeZoneAction.VOLUME;
    public SwipeZoneAction top = SwipeZoneAction.SPEED;
    public boolean isSwipeControlsEnabled() {
        return enabled && (left != SwipeZoneAction.OFF || right != SwipeZoneAction.OFF || top != SwipeZoneAction.OFF);
    }
    public boolean isFullscreenOrMultiWindowVideo() { return fullscreen; }
    public boolean isVideoSliding() { return sliding; }
    public boolean shouldIgnoreSwipesWhenLocked() { return ignoreLocked; }
    public SwipeZoneAction getLeftZoneAction() { return left; }
    public SwipeZoneAction getRightZoneAction() { return right; }
    public SwipeZoneAction getTopZoneAction() { return top; }
    public int getSwipeMagnitudeThreshold() { return 30; }
    public int getVolumeSwipeDistance() { return 10; }
    public int getBrightnessSwipeSensitivity() { return 1; }
    public int getSpeedSwipeSensitivity() { return 1; }
    public int getSpeedStepInt() { return 5; }
    public boolean isSpeedControlEnabled() { return true; }
}
