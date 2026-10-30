/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector.SwipeDirection;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * The common base of all {@link GestureController} classes.
 * Handles most of the boilerplate code needed for gesture detection.
 */
public abstract class BaseGestureController extends GestureDetector.SimpleOnGestureListener
        implements GestureController {

    /**
     * Reference to the main swipe controller.
     */
    protected final SwipeControlsHostActivity controller;

    private final SwipeDetector swipeDetector;

    private final VolumeAndBrightnessScroller scroller;

    /**
     * The main gesture detector that powers everything.
     */
    protected final GestureDetector detector;

    @Nullable
    private MotionEvent downEvent;

    private boolean observing;

    private boolean ownsGesture;

    private SwipeZoneAction horizontalAction = SwipeZoneAction.OFF;

    private SwipeZoneAction verticalAction = SwipeZoneAction.OFF;

    /**
     * @param controller Reference to the main swipe controller.
     */
    protected BaseGestureController(SwipeControlsHostActivity controller) {
        this.controller = controller;
        SwipeControlsConfigurationProvider config = controller.getConfig();
        swipeDetector = new SwipeDetector(config.getSwipeMagnitudeThreshold());
        scroller = new VolumeAndBrightnessScroller(
                controller,
                controller.getAudio(),
                controller.getScreen(),
                controller.getOverlay(),
                config.getVolumeSwipeDistance(),
                config.getBrightnessSwipeSensitivity(),
                config.getSpeedSwipeSensitivity(),
                config.getSpeedStepInt(),
                config.isSpeedControlEnabled()
        );
        detector = new GestureDetector(controller, this);
    }

    @Override
    public boolean submitTouchEvent(MotionEvent motionEvent) {
        SwipeControlsConfigurationProvider config = controller.getConfig();
        final int action = motionEvent.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dispose();
            downEvent = MotionEvent.obtain(motionEvent);
            observing = config.isSwipeControlsEnabled()
                    && (config.isFullscreenOrMultiWindowVideo() || config.isVideoSliding());
            if (observing) {
                // The player can move before the swipe threshold is reached, so use where the gesture started.
                Point origin = Point.fromMotionEvent(motionEvent);
                horizontalAction = swipeActionAt(origin, SwipeDirection.HORIZONTAL);
                verticalAction = swipeActionAt(origin, SwipeDirection.VERTICAL);
            }
        } else if (downEvent == null) {
            return false;
        }

        final boolean dropped = shouldDropMotion(motionEvent);
        if (dropped || !config.isSwipeControlsEnabled()
                || (controller.isStatusBarVisible() && !controller.isInSplitScreenMode())
                || (config.shouldIgnoreSwipesWhenLocked() && SwipeControlsHostActivity.isPlayerLocked())) {
            stopObserving(motionEvent);
        } else if (observing) {
            detector.onTouchEvent(motionEvent);
        }

        // If Morphe cancelled the player's tracking, send DOWN again before handing over both fingers.
        if (dropped && action == MotionEvent.ACTION_POINTER_DOWN && ownsGesture) {
            ownsGesture = false;
            controller.dispatchDownstreamTouchEvent(downEvent);
        }

        final boolean handled = ownsGesture;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (observing) {
                observing = false;
                onUp(motionEvent);
            }
            releaseTouch();
        }
        return handled;
    }

    private void stopObserving(MotionEvent motionEvent) {
        if (!observing) return;
        observing = false;
        MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        detector.onTouchEvent(cancelEvent);
        onUp(cancelEvent);
        cancelEvent.recycle();
    }

    @Override
    public void dispose() {
        if (downEvent != null) {
            stopObserving(downEvent);
        }
        releaseTouch();
    }

    private void releaseTouch() {
        if (downEvent != null) {
            downEvent.recycle();
            downEvent = null;
        }
        ownsGesture = false;
        horizontalAction = SwipeZoneAction.OFF;
        verticalAction = SwipeZoneAction.OFF;
    }

    /**
     * Resets recognition when the finger lifts or the detector is stopped.
     *
     * @param motionEvent The motion event.
     */
    protected void onUp(MotionEvent motionEvent) {
        swipeDetector.resetSwipe();
        scroller.resetScroller();
    }

    @Override
    public boolean onScroll(@Nullable MotionEvent from, MotionEvent to, float distanceX, float distanceY) {
        if (from == null) {
            return false;
        }

        // Submit to swipe detector.
        swipeDetector.submitForSwipe(from, to);

        // Call swipe callback if in a swipe.
        if (getCurrentSwipe() == SwipeDirection.NONE) {
            return false;
        }

        return onSwipe(from, to, distanceX, distanceY);
    }

    /**
     * The currently detected swipe.
     */
    protected SwipeDirection getCurrentSwipe() {
        return swipeDetector.getCurrentSwipe();
    }

    /**
     * Whether the gesture started in an enabled swipe zone.
     */
    protected boolean hasSwipeAction() {
        return horizontalAction != SwipeZoneAction.OFF || verticalAction != SwipeZoneAction.OFF;
    }

    /**
     * Resolves the action of the zone a swipe belongs to.
     * <p>
     * The side zones own vertical swipes and the top zone owns horizontal ones. The zones overlap,
     * so the direction is what keeps that overlap unambiguous, and it leaves every other gesture
     * to the player instead of swallowing it.
     *
     * @param origin    Where the swipe started.
     * @param direction The direction of the swipe.
     * @return The action to apply, or {@link SwipeZoneAction#OFF} if the swipe belongs to no zone.
     */
    protected SwipeZoneAction swipeActionAt(Point origin, SwipeDirection direction) {
        SwipeControlsConfigurationProvider config = controller.getConfig();
        SwipeZonesController zones = controller.getZones();

        if (direction == SwipeDirection.HORIZONTAL) {
            return zones.getTop().contains(origin)
                    ? config.getTopZoneAction()
                    : SwipeZoneAction.OFF;
        }

        if (direction == SwipeDirection.VERTICAL) {
            if (zones.getLeft().contains(origin)) {
                return config.getLeftZoneAction();
            }
            if (zones.getRight().contains(origin)) {
                return config.getRightZoneAction();
            }
        }

        return SwipeZoneAction.OFF;
    }

    /**
     * Applies the action of the zone the swipe started in.
     *
     * @param to        Current event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the swipe was consumed.
     */
    protected boolean applySwipeAction(MotionEvent to, double distanceX, double distanceY) {
        SwipeDirection direction = getCurrentSwipe();
        final double distance = direction == SwipeDirection.HORIZONTAL ? -distanceX : distanceY;
        SwipeZoneAction action = switch (direction) {
            case HORIZONTAL -> horizontalAction;
            case VERTICAL -> verticalAction;
            case NONE -> SwipeZoneAction.OFF;
        };
        if (action == SwipeZoneAction.OFF) return false;

        // Cancel the player's gesture before applying the first swipe action.
        if (!ownsGesture) {
            ownsGesture = true;
            MotionEvent cancelEvent = MotionEvent.obtain(to);
            cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
            controller.dispatchDownstreamTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }

        switch (action) {
            case VOLUME -> scroller.scrollVolume(distance);
            case BRIGHTNESS -> scroller.scrollBrightness(distance);
            case SPEED -> scroller.scrollSpeed(distance);
        }

        return true;
    }

    /**
     * Checks if recognition should stop for this gesture.
     * If another finger joins a claimed gesture, it is handed back to the player.
     *
     * @param motionEvent The event to check.
     * @return Whether the event should be dropped.
     */
    protected abstract boolean shouldDropMotion(MotionEvent motionEvent);

    /**
     * Handler for swipe events, once a swipe is detected.
     * The direction of the swipe can be accessed in {@link #getCurrentSwipe()}.
     *
     * @param from      Start event of the swipe.
     * @param to        End event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the event was consumed.
     */
    protected abstract boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY);
}
