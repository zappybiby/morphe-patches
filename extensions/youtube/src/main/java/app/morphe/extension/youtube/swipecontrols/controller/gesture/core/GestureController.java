package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import android.view.MotionEvent;

/**
 * Describes a class that accepts motion events and detects gestures.
 */
public interface GestureController {
    /**
     * Accepts a touch event and tries to detect the desired gestures using it.
     *
     * @param motionEvent The motion event that was submitted.
     * @return Whether Morphe owns this event and it should not be sent to the player.
     */
    boolean submitTouchEvent(MotionEvent motionEvent);

    /**
     * Stops recognition and releases any saved touch event.
     */
    void dispose();
}
