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

import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;

/**
 * Provides the classic swipe controls experience, as it was with 'XFenster'.
 */
public final class ClassicSwipeController extends BaseGestureController {

    private final PlayerControlsVisibilityObserver playerControlsVisibilityObserver;

    /**
     * @param controller Reference to the main swipe controller.
     */
    public ClassicSwipeController(SwipeControlsHostActivity controller) {
        super(controller);
        playerControlsVisibilityObserver = new PlayerControlsVisibilityObserver(controller);
    }

    @Override
    protected boolean shouldDropMotion(MotionEvent motionEvent) {
        // Leave gestures with more than one pointer to the player.
        if (motionEvent.getPointerCount() > 1) {
            return true;
        }

        // ignore gestures when player controls are visible
        return playerControlsVisibilityObserver.arePlayerControlsVisible();
    }

    @Override
    public boolean onDown(MotionEvent motionEvent) {
        // must be inside swipe zone
        return isInSwipeZone(motionEvent);
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
        // Cancelling this detector leaves the original event for the player.
        if (motionEvent.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            MotionEvent event = MotionEvent.obtain(motionEvent);
            controller.dispatchDownstreamTouchEvent(event);
            event.recycle();
        }

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
        return applySwipeAction(from, distanceX, distanceY);
    }
}
