/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package android.view;

public final class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2,
            ACTION_CANCEL = 3, ACTION_OUTSIDE = 4, ACTION_POINTER_DOWN = 5,
            ACTION_POINTER_UP = 6, ACTION_POINTER_INDEX_SHIFT = 8;
    public static int ownedCopies;

    public static final class PointerProperties {
        public int id, toolType;
        void copy(PointerProperties other) { id = other.id; toolType = other.toolType; }
    }

    public static final class PointerCoords {
        public float x, y, pressure, size, orientation;
        void copy(PointerCoords other) {
            x = other.x; y = other.y; pressure = other.pressure;
            size = other.size; orientation = other.orientation;
        }
    }

    private final long downTime, eventTime;
    private int action;
    private final PointerProperties[] properties;
    private final PointerCoords[] coordinates;
    private final int meta, buttons, device, edges, source, flags;
    private final float xPrecision, yPrecision;
    private final boolean owned;
    private boolean recycled;

    private MotionEvent(long down, long time, int action, PointerProperties[] properties,
                        PointerCoords[] coordinates, int meta, int buttons,
                        float xp, float yp, int device, int edges, int source, int flags,
                        boolean owned) {
        this.downTime = down; this.eventTime = time; this.action = action;
        this.properties = new PointerProperties[properties.length];
        this.coordinates = new PointerCoords[coordinates.length];
        for (int i = 0; i < properties.length; i++) {
            this.properties[i] = new PointerProperties();
            this.properties[i].copy(properties[i]);
            this.coordinates[i] = new PointerCoords();
            this.coordinates[i].copy(coordinates[i]);
        }
        this.meta = meta; this.buttons = buttons; xPrecision = xp; yPrecision = yp;
        this.device = device; this.edges = edges; this.source = source; this.flags = flags;
        this.owned = owned;
        if (owned) ownedCopies++;
    }

    public static MotionEvent create(long down, long time, int action, int... ids) {
        PointerProperties[] properties = new PointerProperties[ids.length];
        PointerCoords[] coordinates = new PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new PointerProperties();
            properties[i].id = ids[i]; properties[i].toolType = 1 + i;
            coordinates[i] = new PointerCoords();
            coordinates[i].x = time + ids[i]; coordinates[i].y = time * 2 + ids[i];
            coordinates[i].pressure = .35f; coordinates[i].size = .7f;
            coordinates[i].orientation = .3f;
        }
        return new MotionEvent(down, time, action, properties, coordinates,
                7, 2, .5f, .25f, 33, 4, 0x1002, 8, false);
    }

    public static MotionEvent obtain(MotionEvent other) {
        other.check();
        return new MotionEvent(other.downTime, other.eventTime, other.action,
                other.properties, other.coordinates, other.meta, other.buttons,
                other.xPrecision, other.yPrecision, other.device, other.edges,
                other.source, other.flags, true);
    }

    public static MotionEvent obtain(long down, long time, int action, int count,
                                     PointerProperties[] properties, PointerCoords[] coordinates,
                                     int meta, int buttons, float xp, float yp, int device,
                                     int edges, int source, int flags) {
        if (count != properties.length || count != coordinates.length) throw new AssertionError();
        return new MotionEvent(down, time, action, properties, coordinates,
                meta, buttons, xp, yp, device, edges, source, flags, true);
    }

    private void check() { if (recycled) throw new AssertionError("Recycled event used"); }
    public void recycle() { check(); recycled = true; if (owned) ownedCopies--; }
    public int getAction() { check(); return action; }
    public int getActionMasked() { return getAction() & 255; }
    public int getActionIndex() { return getAction() >> ACTION_POINTER_INDEX_SHIFT; }
    public void setAction(int action) { check(); this.action = action; }
    public long getDownTime() { check(); return downTime; }
    public long getEventTime() { check(); return eventTime; }
    public int getPointerCount() { check(); return properties.length; }
    public int getPointerId(int index) { check(); return properties[index].id; }
    public float getX(int index) { check(); return coordinates[index].x; }
    public float getY(int index) { check(); return coordinates[index].y; }
    public float getX() { return getX(0); }
    public float getY() { return getY(0); }
    public int getMetaState() { check(); return meta; }
    public int getButtonState() { check(); return buttons; }
    public float getXPrecision() { check(); return xPrecision; }
    public float getYPrecision() { check(); return yPrecision; }
    public int getDeviceId() { check(); return device; }
    public int getEdgeFlags() { check(); return edges; }
    public int getSource() { check(); return source; }
    public int getFlags() { check(); return flags; }
    public void getPointerProperties(int index, PointerProperties out) { check(); out.copy(properties[index]); }
    public void getPointerCoords(int index, PointerCoords out) { check(); out.copy(coordinates[index]); }
}
