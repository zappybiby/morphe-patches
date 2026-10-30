/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package android.view;

import android.content.Context;

public final class GestureDetector {
    public interface OnGestureListener {
        boolean onDown(MotionEvent event);
        boolean onSingleTapUp(MotionEvent event);
        boolean onScroll(MotionEvent from, MotionEvent to, float distanceX, float distanceY);
        void onLongPress(MotionEvent event);
    }

    public static class SimpleOnGestureListener implements OnGestureListener {
        public boolean onDown(MotionEvent event) { return false; }
        public boolean onSingleTapUp(MotionEvent event) { return false; }
        public boolean onScroll(MotionEvent from, MotionEvent to, float x, float y) { return false; }
        public void onLongPress(MotionEvent event) {}
    }

    public static GestureDetector latest;
    private final OnGestureListener listener;
    private MotionEvent down;
    private boolean inLongPress;
    public boolean consumeDown;
    public boolean pending;

    public GestureDetector(Context context, OnGestureListener listener) {
        this.listener = listener;
        latest = this;
    }

    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = MotionEvent.borrowedCopy(event);
                inLongPress = false;
                pending = true;
                return listener.onDown(event) || consumeDown;
            case MotionEvent.ACTION_MOVE:
                return !inLongPress && down != null && listener.onScroll(down, event,
                        down.getX() - event.getX(), down.getY() - event.getY());
            case MotionEvent.ACTION_UP:
                inLongPress = false;
                pending = false;
                return listener.onSingleTapUp(event);
            case MotionEvent.ACTION_CANCEL:
                inLongPress = false;
                pending = false;
                return false;
            default:
                return false;
        }
    }

    public void fireLongPress() {
        if (pending) {
            pending = false;
            inLongPress = true;
            listener.onLongPress(down);
        }
    }

    public int lastDownAction() { return down.getAction(); }
}
