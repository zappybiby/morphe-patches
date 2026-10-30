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

import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;

/**
 * Provides the press-to-swipe (PtS) swipe controls experience.
 */
public final class PressToSwipeController extends BaseGestureController {

    /**
     * Whether holding has activated the current swipe session.
     */
    private boolean swipeSessionActive;

    /**
     * @param controller Reference to the main swipe controller.
     */
    public PressToSwipeController(SwipeControlsHostActivity controller) {
        super(controller);
    }

    @Override
    protected boolean shouldDropMotion(MotionEvent motionEvent) {
        return false;
    }

    @Override
    protected void onUp(MotionEvent motionEvent) {
        super.onUp(motionEvent);
        swipeSessionActive = false;
    }

    @Override
    public void onLongPress(MotionEvent motionEvent) {
        // enter swipe session with feedback
        swipeSessionActive = hasSwipeAction();
        if (swipeSessionActive) {
            controller.getOverlay().onEnterSwipeSession();
        }

        // send GestureDetector a ACTION_CANCEL event so it will handle further events
        MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        detector.onTouchEvent(cancelEvent);
        cancelEvent.recycle();
    }

    @Override
    protected boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY) {
        // cancel if not in swipe session
        if (!swipeSessionActive) return false;

        return applySwipeAction(to, distanceX, distanceY);
    }
}
