/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture;

import android.view.MotionEvent;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * Provides the classic swipe controls experience, as it was with 'XFenster'.
 */
public final class ClassicSwipeController extends BaseGestureController {

    private final PlayerControlsVisibilityObserver playerControlsVisibilityObserver;

    /**
     * The last event captured in {@link #onDown}.
     */
    @Nullable
    private MotionEvent lastOnDownEvent;

    private boolean shouldReplayDownEvent;

    /**
     * @param controller Reference to the main swipe controller.
     */
    public ClassicSwipeController(SwipeControlsHostActivity controller) {
        super(controller);
        playerControlsVisibilityObserver = new PlayerControlsVisibilityObserver(controller);
    }

    @Override
    protected boolean shouldForceInterceptEvents() {
        if (lastOnDownEvent == null) return false;
        Point origin = Point.fromMotionEvent(lastOnDownEvent);
        return swipeActionAt(origin, getCurrentSwipe()) != SwipeZoneAction.OFF;
    }

    @Override
    protected boolean shouldDropMotion(MotionEvent motionEvent) {
        // Leave gestures with more than one pointer to the player.
        // Replay DOWN if swipe controls withheld it or cancelled the player's touch stream.
        if (motionEvent.getPointerCount() > 1) {
            if (lastOnDownEvent != null) {
                if (shouldReplayDownEvent) {
                    controller.dispatchDownstreamTouchEvent(lastOnDownEvent);
                }
                lastOnDownEvent.recycle();
            }
            lastOnDownEvent = null;
            return true;
        }

        // ignore gestures when player controls are visible
        return playerControlsVisibilityObserver.arePlayerControlsVisible();
    }

    @Override
    protected void onUp(MotionEvent motionEvent) {
        super.onUp(motionEvent);
        if (lastOnDownEvent != null) {
            lastOnDownEvent.recycle();
        }
        lastOnDownEvent = null;
    }

    @Override
    public boolean onDown(MotionEvent motionEvent) {
        // save the event for later
        if (lastOnDownEvent != null) {
            lastOnDownEvent.recycle();
        }
        lastOnDownEvent = MotionEvent.obtain(motionEvent);

        // must be inside swipe zone
        shouldReplayDownEvent = isInSwipeZone(motionEvent);
        return shouldReplayDownEvent;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent motionEvent) {
        MotionEvent event = MotionEvent.obtain(motionEvent);
        event.setAction(MotionEvent.ACTION_DOWN);
        controller.dispatchDownstreamTouchEvent(event);
        event.recycle();

        return false;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent motionEvent) {
        MotionEvent event = MotionEvent.obtain(motionEvent);
        controller.dispatchDownstreamTouchEvent(event);
        event.recycle();

        return super.onDoubleTapEvent(motionEvent);
    }

    @Override
    public void onLongPress(MotionEvent motionEvent) {
        MotionEvent event = MotionEvent.obtain(motionEvent);
        controller.dispatchDownstreamTouchEvent(event);
        event.recycle();

        super.onLongPress(motionEvent);
    }

    @Override
    protected boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY) {
        // cancel if not fullscreen or multi-window
        if (!controller.getConfig().isFullscreenOrMultiWindowVideo()) return false;
        // cancel if the swipe does not belong to any zone
        if (!shouldForceInterceptEvents()) return false;

        final boolean consumed = applySwipeAction(from, distanceX, distanceY);
        if (consumed) {
            // If the player resizes, swipe controls can take over a swipe that started outside a zone.
            // The player then gets CANCEL, so send DOWN again if a second finger touches the screen.
            shouldReplayDownEvent = true;
        }
        return consumed;
    }
}
