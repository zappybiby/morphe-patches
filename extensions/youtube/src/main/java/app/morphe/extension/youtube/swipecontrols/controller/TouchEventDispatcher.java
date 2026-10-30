/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import android.view.MotionEvent;

/** Owns touch delivery below the swipe controls, including their synthetic events. */
public final class TouchEventDispatcher {
    @FunctionalInterface
    public interface Receiver {
        boolean dispatch(MotionEvent event);
    }

    private static final class Input {
        final Input previous;
        final long ticket;
        final long generation;
        final boolean valid;
        final MotionEvent source;
        final int action;
        boolean upDelivered;
        boolean upResult;
        long upNativeStart;
        long upEventTime;
        int upPointers;

        Input(Input previous, long ticket, long generation, boolean valid, MotionEvent source) {
            this.previous = previous;
            this.ticket = ticket;
            this.generation = generation;
            this.valid = valid;
            this.source = source;
            this.action = source.getActionMasked();
        }
    }

    private final Receiver receiver;
    private MotionEvent originalDown;
    private Input input;
    private long generation;
    private long nextTicket;
    private long nativeStart;
    private boolean inputActive;
    private boolean nativeActive;
    private boolean nativeDownResult;
    private boolean disposed;
    private int nativePointers;

    public TouchEventDispatcher(Receiver receiver) {
        this.receiver = receiver;
    }

    /** Call before gesture callbacks, even when swipe controls will ignore the input. */
    public long beginInput(MotionEvent event) {
        long ticket = ++nextTicket;
        int action = event.getActionMasked();
        if (!disposed && action == MotionEvent.ACTION_DOWN) {
            MotionEvent previousDown = originalDown;
            originalDown = MotionEvent.obtain(event);
            generation++;
            inputActive = true;
            input = new Input(input, ticket, generation, true, event);
            try {
                if (nativeActive) {
                    sendCancel(previousDown != null ? previousDown : event);
                }
            } finally {
                if (previousDown != null) previousDown.recycle();
            }
        } else {
            boolean valid = !disposed && (action == MotionEvent.ACTION_CANCEL || !isTouchAction(action)
                    || inputActive && originalDown != null
                    && event.getDownTime() == originalDown.getDownTime());
            input = new Input(input, ticket, generation, valid, event);
        }
        return ticket;
    }

    /** Pair with beginInput in finally; an older nested call cannot end a newer gesture. */
    public void endInput(MotionEvent event, long ticket) {
        Input finished = input;
        if (finished == null || finished.ticket != ticket) return;
        input = finished.previous;
        int action = finished.action;
        if (finished.valid && finished.generation == generation
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            cancel();
        }
    }

    /** Forward every ordinary or synthetic native-bound event through this method. */
    public boolean dispatch(MotionEvent event) {
        if (disposed || input != null && (!input.valid || input.generation != generation)) {
            return false;
        }
        int action = event.getActionMasked();
        if (!isTouchAction(action)) return receiver.dispatch(event);
        boolean sourceCancel = action == MotionEvent.ACTION_CANCEL
                && input != null && input.source == event;
        if (!sourceCancel && !isCurrent(event)) return false;

        if (action == MotionEvent.ACTION_DOWN) {
            if (nativeActive) return nativeDownResult;
            MotionEvent down = MotionEvent.obtain(originalDown);
            try {
                return sendDown(down);
            } finally {
                down.recycle();
            }
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            nativeActive = false;
            nativePointers = 0;
            nativeStart++;
            return receiver.dispatch(event);
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // If swipe controls blocked or cancelled the gesture, restore the fingers already on screen first.
            if (event.getPointerCount() < 2 || !restorePointers(event)) return false;
            nativePointers = pointerBits(event);
            return receiver.dispatch(event);
        }
        if (!nativeActive) {
            return action == MotionEvent.ACTION_UP && input != null && input.action == action
                    && input.upDelivered && input.upNativeStart == nativeStart
                    && input.upEventTime == event.getEventTime()
                    && input.upPointers == pointerBits(event) && input.upResult;
        }
        if (pointerBits(event) != nativePointers) return false;
        if (action == MotionEvent.ACTION_UP) {
            if (event.getPointerCount() != 1) return false;
            nativeActive = false;
            nativePointers = 0;
            long start = ++nativeStart;
            Input owner = input;
            if (owner != null && owner.action == action) {
                owner.upDelivered = true;
                owner.upResult = true;
                owner.upNativeStart = start;
                owner.upEventTime = event.getEventTime();
                owner.upPointers = pointerBits(event);
            }
            boolean result = receiver.dispatch(event);
            if (owner != null && owner.generation == generation && nativeStart == start) {
                owner.upResult = result;
            }
            return result;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerCount() < 2) return false;
            nativePointers &= ~(1 << event.getPointerId(event.getActionIndex()));
        }
        return receiver.dispatch(event);
    }

    /** End this input gesture; only a new input DOWN may restart it. */
    public void cancel() {
        MotionEvent down = originalDown;
        originalDown = null;
        inputActive = false;
        generation++;
        try {
            if (nativeActive && down != null) sendCancel(down);
        } finally {
            if (down != null) down.recycle();
        }
    }

    public void dispose() {
        disposed = true;
        cancel();
        input = null;
    }

    private boolean isCurrent(MotionEvent event) {
        return inputActive && originalDown != null
                && event.getDownTime() == originalDown.getDownTime()
                && (input == null || input.valid && input.generation == generation);
    }

    private boolean sendDown(MotionEvent event) {
        nativeActive = true;
        nativePointers = pointerBits(event);
        nativeDownResult = true;
        long start = ++nativeStart;
        boolean result = receiver.dispatch(event);
        if (nativeActive && nativeStart == start) nativeDownResult = result;
        return result;
    }

    private void sendCancel(MotionEvent source) {
        MotionEvent event = MotionEvent.obtain(source);
        event.setAction(MotionEvent.ACTION_CANCEL);
        nativeActive = false;
        nativePointers = 0;
        nativeStart++;
        try {
            receiver.dispatch(event);
        } finally {
            event.recycle();
        }
    }

    private boolean restorePointers(MotionEvent event) {
        long expectedGeneration = generation;
        int added = event.getPointerId(event.getActionIndex());
        int existing = pointerBits(event) & ~(1 << added);
        if (nativeActive && nativePointers == existing) return true;
        if (nativeActive) {
            sendCancel(event);
            if (nativeActive) {
                return isCurrent(event) && generation == expectedGeneration
                        && nativePointers == existing;
            }
        }
        if (!isCurrent(event) || generation != expectedGeneration) return false;

        int first = originalDown.getPointerId(0);
        boolean useOriginal = (existing & (1 << first)) != 0;
        if (!useOriginal) {
            first = event.getPointerId(event.getActionIndex() == 0 ? 1 : 0);
        }
        MotionEvent down = useOriginal ? MotionEvent.obtain(originalDown)
                : subset(event, 1 << first, MotionEvent.ACTION_DOWN, first);
        try {
            sendDown(down);
        } finally {
            down.recycle();
        }
        if (!isCurrent(event) || generation != expectedGeneration || !nativeActive) return false;

        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            int bit = 1 << id;
            if ((existing & bit) == 0 || (nativePointers & bit) != 0) continue;
            MotionEvent pointer = subset(event, nativePointers | bit,
                    MotionEvent.ACTION_POINTER_DOWN, id);
            nativePointers |= bit;
            try {
                receiver.dispatch(pointer);
            } finally {
                pointer.recycle();
            }
            if (!isCurrent(event) || generation != expectedGeneration || !nativeActive) return false;
        }
        return nativePointers == existing;
    }

    private static int pointerBits(MotionEvent event) {
        int bits = 0;
        for (int i = 0; i < event.getPointerCount(); i++) bits |= 1 << event.getPointerId(i);
        return bits;
    }

    private static MotionEvent subset(MotionEvent event, int bits, int action, int added) {
        int count = Integer.bitCount(bits);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        int index = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if ((bits & (1 << event.getPointerId(i))) == 0) continue;
            properties[index] = new MotionEvent.PointerProperties();
            coordinates[index] = new MotionEvent.PointerCoords();
            event.getPointerProperties(i, properties[index]);
            event.getPointerCoords(i, coordinates[index]);
            if (event.getPointerId(i) == added) action |= index << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            index++;
        }
        return MotionEvent.obtain(event.getDownTime(), event.getEventTime(), action, count,
                properties, coordinates, event.getMetaState(), event.getButtonState(),
                event.getXPrecision(), event.getYPrecision(), event.getDeviceId(),
                event.getEdgeFlags(), event.getSource(), event.getFlags());
    }

    private static boolean isTouchAction(int action) {
        return action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP;
    }
}
