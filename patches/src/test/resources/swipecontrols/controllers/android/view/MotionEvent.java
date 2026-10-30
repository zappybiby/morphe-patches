/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package android.view;

public final class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2,
            ACTION_CANCEL = 3, ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6;
    public static int ownedCopies;
    private final long downTime, eventTime;
    private final int[] ids;
    private final float[] x, y;
    private final boolean owned;
    private int action;
    private boolean recycled;

    private MotionEvent(long down, long time, int action, int[] ids, float[] x, float[] y, boolean owned) {
        downTime = down; eventTime = time; this.action = action;
        this.ids = ids.clone(); this.x = x.clone(); this.y = y.clone(); this.owned = owned;
        if (owned) ownedCopies++;
    }

    public static MotionEvent at(long down, long time, int action, float x, float y, int... ids) {
        float[] xs = new float[ids.length], ys = new float[ids.length];
        for (int i = 0; i < ids.length; i++) { xs[i] = x + i * 20; ys[i] = y + i * 20; }
        return new MotionEvent(down, time, action, ids, xs, ys, false);
    }

    public static MotionEvent obtain(MotionEvent other) { return copy(other, true); }
    public static MotionEvent borrowedCopy(MotionEvent other) { return copy(other, false); }
    private static MotionEvent copy(MotionEvent other, boolean owned) {
        other.check();
        return new MotionEvent(other.downTime, other.eventTime, other.action, other.ids, other.x, other.y, owned);
    }
    private void check() { if (recycled) throw new AssertionError("Recycled event used"); }
    public void recycle() { check(); recycled = true; if (owned) ownedCopies--; }
    public int getAction() { check(); return action; }
    public int getActionMasked() { return getAction() & 255; }
    public void setAction(int action) { check(); this.action = action; }
    public long getDownTime() { check(); return downTime; }
    public long getEventTime() { check(); return eventTime; }
    public int getPointerCount() { check(); return ids.length; }
    public int getPointerId(int index) { check(); return ids[index]; }
    public float getX(int index) { check(); return x[index]; }
    public float getY(int index) { check(); return y[index]; }
    public float getX() { return getX(0); }
    public float getY() { return getY(0); }
    public int getFlags() { check(); return 8; }
    public int getSource() { check(); return 0x1002; }
}
