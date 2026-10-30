/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

import android.view.GestureDetector;
import android.view.MotionEvent;
import java.util.List;
import java.util.stream.Collectors;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.ClassicSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.PressToSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.GestureController;
import app.morphe.extension.youtube.swipecontrols.misc.Rectangle;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;

public final class SwipeControllerFixture implements AutoCloseable {
    private final SwipeControlsHostActivity host = new SwipeControlsHostActivity();
    private final GestureController gesture;
    private final GestureDetector detector;
    private long time = 100;

    private SwipeControllerFixture(boolean press) {
        SwipeControlsHostActivity.locked = false;
        gesture = press ? new PressToSwipeController(host) : new ClassicSwipeController(host);
        detector = GestureDetector.latest;
    }

    private MotionEvent event(int action, float x, float y, int... ids) {
        return MotionEvent.at(100, time += 50, action, x, y, ids);
    }

    private boolean input(MotionEvent event) {
        return gesture.submitTouchEvent(event) || host.dispatchDownstreamTouchEvent(event);
    }

    private void input(int action, float x, float y, int... ids) { input(event(action, x, y, ids)); }
    private void down() { input(MotionEvent.ACTION_DOWN, 100, 400, 0); }
    private void move() { input(MotionEvent.ACTION_MOVE, 100, 300, 0); }
    private void up() { input(MotionEvent.ACTION_UP, 100, 300, 0); }

    private void nativeActions(Integer... expected) {
        equal(List.of(expected), host.nativeEvents.stream().map(MotionEvent::getActionMasked)
                .collect(Collectors.toList()));
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void same(MotionEvent expected, MotionEvent actual) {
        equal(expected.getAction(), actual.getAction());
        equal(expected.getDownTime(), actual.getDownTime());
        equal(expected.getEventTime(), actual.getEventTime());
        equal(expected.getPointerCount(), actual.getPointerCount());
        equal(expected.getFlags(), actual.getFlags());
        equal(expected.getSource(), actual.getSource());
        for (int i = 0; i < expected.getPointerCount(); i++) {
            equal(expected.getPointerId(i), actual.getPointerId(i));
            equal(expected.getX(i), actual.getX(i));
            equal(expected.getY(i), actual.getY(i));
        }
    }

    public static void passiveTouches() {
        for (boolean press : List.of(false, true)) for (boolean result : List.of(false, true)) {
            try (SwipeControllerFixture f = new SwipeControllerFixture(press)) {
                f.host.nativeResult = result;
                f.detector.consumeDown = true;
                MotionEvent down = f.event(MotionEvent.ACTION_DOWN, 100, 400, 31);
                equal(result, f.input(down));
                same(down, f.host.nativeEvents.get(0));
                MotionEvent up = f.event(MotionEvent.ACTION_UP, 100, 400, 31);
                equal(result, f.input(up));
                same(up, f.host.nativeEvents.get(1));
                f.nativeActions(0, 1);
                equal(List.of(), f.host.actions);
            }
        }
    }

    public static void claimedSwipe() {
        try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
            f.down(); f.move();
            f.input(MotionEvent.ACTION_MOVE, 100, 250, 0);
            equal(List.of("native:0", "native:3", "brightness", "brightness"), f.host.timeline);
            f.host.zones.left = new Rectangle(500, 0, 300, 800);
            f.host.config.left = SwipeZoneAction.VOLUME;
            equal(true, f.input(f.event(MotionEvent.ACTION_MOVE, 100, 200, 0)));
            equal(true, f.input(f.event(MotionEvent.ACTION_UP, 100, 200, 0)));
            f.nativeActions(0, 3);
            equal(List.of("brightness", "brightness", "brightness"), f.host.actions);
        }
    }

    public static void unclaimedResize() {
        try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
            f.input(MotionEvent.ACTION_DOWN, 500, 400, 0);
            f.input(MotionEvent.ACTION_MOVE, 500, 300, 0);
            f.host.zones.left = new Rectangle(400, 0, 300, 800);
            f.input(MotionEvent.ACTION_MOVE, 500, 250, 0);
            f.input(MotionEvent.ACTION_UP, 500, 300, 0);
            f.nativeActions(0, 2, 2, 1);
            equal(List.of(), f.host.actions);
        }
        try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
            f.host.config.fullscreen = false;
            f.down();
            f.host.config.fullscreen = true;
            f.host.config.sliding = false;
            f.move(); f.up();
            f.nativeActions(0, 2, 1);
            equal(List.of(), f.host.actions);
        }
    }

    public static void nativeSideEffects() {
        for (boolean sliding : List.of(false, true)) try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
            f.host.config.fullscreen = !sliding;
            f.host.config.sliding = sliding;
            f.host.nativeFullscreenExitDistance = 27;
            f.input(MotionEvent.ACTION_DOWN, 850, 450, 0);
            f.input(MotionEvent.ACTION_MOVE, 850, 423, 0);
            equal(false, f.host.config.fullscreen);
            equal(false, f.host.config.sliding);
            equal(List.of(), f.host.actions);
            f.input(MotionEvent.ACTION_MOVE, 850, 419, 0);
            equal(List.of("volume"), f.host.actions);
            equal(true, f.host.config.fullscreen);
            equal(1, f.host.nativeFullscreenExits);
            equal(1, f.host.nativeFullscreenRestores);
            equal(List.of("native:0", "native:2", "native:3", "volume"), f.host.timeline);
            f.input(MotionEvent.ACTION_UP, 850, 419, 0);
            f.nativeActions(0, 2, 3);
        }
    }

    public static void classicHandoff() {
        for (boolean claimed : List.of(false, true)) for (boolean gated : List.of(false, true)) {
            try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
                f.down();
                MotionEvent down = f.host.nativeEvents.get(0);
                if (claimed) f.move();
                if (gated) {
                    f.host.config.enabled = false;
                    f.input(MotionEvent.ACTION_MOVE, 100, 250, 0);
                }
                MotionEvent join = f.event(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 100, 250, 0, 31);
                f.input(join);
                same(join, f.host.nativeEvents.get(f.host.nativeEvents.size() - 1));
                if (claimed) same(down, f.host.nativeEvents.get(2));
                f.input(MotionEvent.ACTION_POINTER_UP, 100, 250, 0, 31);
                f.input(MotionEvent.ACTION_UP, 120, 270, 31);
                if (claimed) f.nativeActions(0, 3, 0, 5, 6, 1);
                else if (gated) f.nativeActions(0, 2, 5, 6, 1);
                else f.nativeActions(0, 5, 6, 1);
                equal(claimed ? List.of("brightness") : List.of(), f.host.actions);
            }
        }
    }

    public static void gateTransitions() {
        for (boolean claimed : List.of(false, true)) for (boolean press : List.of(false, true)) {
            try (SwipeControllerFixture f = new SwipeControllerFixture(press)) {
                f.down();
                if (press && claimed) f.detector.fireLongPress();
                if (claimed) f.move();
                f.host.statusBar = true;
                f.input(MotionEvent.ACTION_MOVE, 100, 250, 0);
                f.host.statusBar = false;
                f.input(MotionEvent.ACTION_MOVE, 100, 200, 0);
                f.up();
                if (claimed) f.nativeActions(0, 3);
                else f.nativeActions(0, 2, 2, 1);
                equal(claimed ? List.of("brightness") : List.of(), f.host.actions);
                equal(false, f.detector.pending);
            }
        }
    }

    public static void physicalCancellation() {
        for (boolean claimed : List.of(false, true)) for (boolean result : List.of(false, true)) {
            try (SwipeControllerFixture f = new SwipeControllerFixture(false)) {
                f.host.nativeResult = result;
                f.down();
                if (claimed) f.move();
                MotionEvent cancel = MotionEvent.at(900, 1000, MotionEvent.ACTION_CANCEL, 700, 800, 7, 31);
                equal(claimed || result, f.input(cancel));
                if (claimed) f.nativeActions(0, 3);
                else { f.nativeActions(0, 3); same(cancel, f.host.nativeEvents.get(1)); }
                MotionEvent idle = MotionEvent.at(1200, 1300, MotionEvent.ACTION_CANCEL, 800, 900, 31);
                equal(result, f.input(idle));
                same(idle, f.host.nativeEvents.get(2));
            }
        }
    }

    public static void pressActivation() {
        try (SwipeControllerFixture f = new SwipeControllerFixture(true)) {
            f.down(); f.move(); f.up();
            equal(List.of(), f.host.actions);
            f.down();
            f.host.zones.left = new Rectangle(400, 0, 300, 800);
            f.host.config.left = SwipeZoneAction.VOLUME;
            f.detector.fireLongPress();
            equal(1, f.host.overlay.sessions);
            equal(MotionEvent.ACTION_DOWN, f.detector.lastDownAction());
            f.move(); f.up();
            equal(List.of("brightness"), f.host.actions);
            f.host.zones.left = new Rectangle(0, 0, 300, 800);
            f.host.config.left = SwipeZoneAction.BRIGHTNESS;
            f.down(); f.move(); f.up();
            equal(List.of("brightness"), f.host.actions);
            f.nativeActions(0, 2, 1, 0, 3, 0, 2, 1);
            equal(0, f.host.nativeEvents.get(3).getAction());
        }
        try (SwipeControllerFixture f = new SwipeControllerFixture(true)) {
            f.input(MotionEvent.ACTION_DOWN, 500, 400, 0);
            f.host.zones.left = new Rectangle(400, 0, 300, 800);
            f.detector.fireLongPress();
            f.input(MotionEvent.ACTION_MOVE, 500, 300, 0);
            f.input(MotionEvent.ACTION_UP, 500, 300, 0);
            equal(0, f.host.overlay.sessions);
            equal(List.of(), f.host.actions);
            f.nativeActions(0, 2, 1);
        }
    }

    public static void disposal() {
        try (SwipeControllerFixture f = new SwipeControllerFixture(true)) {
            f.down();
            equal(true, f.detector.pending);
            f.gesture.dispose();
            f.detector.fireLongPress();
            equal(0, f.host.overlay.sessions);
            equal(0, MotionEvent.ownedCopies);
            f.down(); f.detector.fireLongPress(); f.move(); f.up();
            equal(List.of("brightness"), f.host.actions);
        }
    }

    @Override public void close() {
        gesture.dispose();
        equal(0, MotionEvent.ownedCopies);
    }
}
