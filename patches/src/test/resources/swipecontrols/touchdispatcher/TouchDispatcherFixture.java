/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

import android.view.MotionEvent;
import app.morphe.extension.youtube.swipecontrols.controller.TouchEventDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class TouchDispatcherFixture {
    private static void check(boolean value, Object message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Packet {
        final int action, index;
        final long down, time;
        final List<Integer> ids = new ArrayList<>();
        final String values;

        Packet(MotionEvent event) {
            action = event.getActionMasked(); index = event.getActionIndex();
            down = event.getDownTime(); time = event.getEventTime();
            StringBuilder values = new StringBuilder().append(event.getAction()).append(':')
                    .append(down).append(':').append(time);
            for (int i = 0; i < event.getPointerCount(); i++) {
                ids.add(event.getPointerId(i));
                MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
                MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
                event.getPointerProperties(i, properties); event.getPointerCoords(i, coordinates);
                values.append(':').append(properties.id).append(',').append(properties.toolType)
                        .append(',').append(coordinates.x).append(',').append(coordinates.y)
                        .append(',').append(coordinates.pressure).append(',').append(coordinates.size)
                        .append(',').append(coordinates.orientation);
            }
            this.values = values.append(':').append(event.getMetaState()).append(',')
                    .append(event.getButtonState()).append(',').append(event.getDeviceId()).append(',')
                    .append(event.getSource()).append(',').append(event.getFlags()).append(',')
                    .append(event.getEdgeFlags()).append(',').append(event.getXPrecision()).append(',')
                    .append(event.getYPrecision()).toString();
        }

        public String toString() { return values; }
    }

    // This oracle checks Android's pointer protocol, without reproducing the dispatcher's state machine.
    private static final class Oracle {
        final List<Packet> events = new ArrayList<>();
        final Set<Integer> pointers = new HashSet<>();
        boolean active;
        long down;

        void accept(MotionEvent event) {
            Packet packet = new Packet(event);
            Set<Integer> ids = new HashSet<>(packet.ids);
            check(ids.size() == packet.ids.size(), "Duplicate pointer ids: " + packet);
            switch (packet.action) {
                case MotionEvent.ACTION_DOWN:
                    check(!active, "DOWN while a native gesture is already active: " + packet);
                    check(ids.size() == 1, "DOWN must introduce one pointer");
                    active = true; down = packet.down; pointers.addAll(ids);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    active = false; pointers.clear();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    check(active && packet.down == down, "Pointer without its native DOWN");
                    int added = packet.ids.get(packet.index);
                    check(!pointers.contains(added), "Pointer introduced twice");
                    Set<Integer> expected = new HashSet<>(pointers);
                    expected.add(added);
                    check(expected.equals(ids), "Pointer appeared without being introduced: " + packet);
                    pointers.add(added);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    check(active && packet.down == down && pointers.equals(ids), "Invalid pointer UP");
                    check(ids.size() > 1, "Final pointer needs ACTION_UP");
                    pointers.remove(packet.ids.get(packet.index));
                    break;
                case MotionEvent.ACTION_MOVE:
                    check(active && packet.down == down && pointers.equals(ids), "MOVE without matching pointers");
                    break;
                case MotionEvent.ACTION_UP:
                    check(active && packet.down == down && pointers.equals(ids) && ids.size() == 1,
                            "UP without its native DOWN");
                    active = false; pointers.clear();
                    break;
                default:
                    break;
            }
            events.add(packet);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Oracle oracle = new Oracle();
        final TouchEventDispatcher dispatcher;
        Consumer<MotionEvent> callback;
        boolean result;

        Fixture(boolean result) {
            this.result = result;
            dispatcher = new TouchEventDispatcher(event -> {
                oracle.accept(event);
                boolean accepted = this.result;
                Consumer<MotionEvent> callback = this.callback;
                if (callback != null) callback.accept(event);
                return accepted;
            });
        }

        boolean input(MotionEvent event, boolean forward) {
            long ticket = dispatcher.beginInput(event);
            try {
                return forward && dispatcher.dispatch(event);
            } finally {
                dispatcher.endInput(event, ticket);
            }
        }

        List<Integer> actions() {
            List<Integer> actions = new ArrayList<>();
            for (Packet packet : oracle.events) actions.add(packet.action);
            return actions;
        }

        public void close() {
            dispatcher.dispose();
            check(!oracle.active, "Native gesture survived disposal");
            check(MotionEvent.ownedCopies == 0, "MotionEvent copy leaked: " + MotionEvent.ownedCopies);
        }
    }

    private static MotionEvent event(long down, long time, int action, int... ids) {
        return MotionEvent.create(down, time, action, ids);
    }

    public static void canonical() {
        try (Fixture fixture = new Fixture(true)) {
            MotionEvent first = event(10, 10, MotionEvent.ACTION_DOWN, 31);
            fixture.input(first, false);
            fixture.input(event(10, 20, MotionEvent.ACTION_MOVE, 31), true);
            check(fixture.oracle.events.isEmpty(), "MOVE started a withheld native gesture");
            MotionEvent up = event(10, 90, MotionEvent.ACTION_UP, 31);
            long ticket = fixture.dispatcher.beginInput(up);
            try {
                fixture.dispatcher.dispatch(event(10, 90, MotionEvent.ACTION_DOWN, 31));
                check(fixture.oracle.events.get(0).values.equals(new Packet(first).values),
                        "Tap callback rewrote the original DOWN values");
                check(fixture.dispatcher.dispatch(up), "Changed the native UP result");
                check(fixture.dispatcher.dispatch(up), "Lost the handled result of an already delivered UP");
            } finally {
                fixture.dispatcher.endInput(up, ticket);
            }
            int completed = fixture.oracle.events.size();
            check(!fixture.dispatcher.dispatch(first), "Late callback created an orphan DOWN");
            check(fixture.oracle.events.size() == completed, "Late callback escaped the gesture");

            MotionEvent second = event(10, 100, MotionEvent.ACTION_DOWN, 0);
            fixture.input(second, true);
            fixture.dispatcher.dispatch(event(10, 150, MotionEvent.ACTION_DOWN, 0));
            fixture.input(event(10, 160, MotionEvent.ACTION_POINTER_DOWN, 31, 0), true);
            fixture.input(event(10, 170, MotionEvent.ACTION_POINTER_UP, 31, 0), true);
            fixture.input(event(10, 180, MotionEvent.ACTION_UP, 0), true);
            check(fixture.actions().equals(List.of(0, 1, 0, 5, 6, 1)), fixture.actions());
            check(fixture.oracle.events.get(2).values.equals(new Packet(second).values), "New gesture reused old DOWN");
        }
    }

    public static void pointerRecovery() {
        try (Fixture fixture = new Fixture(true)) {
            MotionEvent first = event(1, 1, MotionEvent.ACTION_DOWN, 31);
            fixture.input(first, false);
            fixture.input(event(1, 2, MotionEvent.ACTION_POINTER_DOWN, 0, 31), true);
            check(fixture.oracle.events.get(0).values.equals(new Packet(first).values), "Handoff lost original DOWN");
            fixture.input(event(1, 3, MotionEvent.ACTION_POINTER_UP | 1 << 8, 0, 31), true);
            fixture.dispatcher.dispatch(event(1, 4, MotionEvent.ACTION_CANCEL, 0));
            fixture.input(event(1, 5, MotionEvent.ACTION_POINTER_DOWN, 7, 0), true);
            Packet survivor = fixture.oracle.events.get(4);
            check(survivor.action == 0 && survivor.ids.equals(List.of(0)) && survivor.time == 5,
                    "Restored a pointer that already left: " + survivor);
            fixture.dispatcher.dispatch(event(1, 6, MotionEvent.ACTION_CANCEL, 7, 0));
            fixture.input(event(1, 7, MotionEvent.ACTION_POINTER_DOWN | 2 << 8, 7, 0, 31), true);
            check(fixture.oracle.pointers.equals(Set.of(0, 7, 31)), "Not all surviving pointers were restored");
            fixture.input(event(500, 500, MotionEvent.ACTION_CANCEL, 8), true);
        }
    }

    public static void receiverResults() {
        try (Fixture fixture = new Fixture(false)) {
            MotionEvent down = event(1, 1, MotionEvent.ACTION_DOWN, 0);
            check(!fixture.input(down, true), "Changed native false result");
            check(!fixture.dispatcher.dispatch(down), "Duplicate DOWN changed false result");
            check(fixture.oracle.events.size() == 1, "False return caused duplicate delivery");
            MotionEvent move = event(1, 2, MotionEvent.ACTION_MOVE, 0);
            check(!fixture.input(move, true), "Changed MOVE result");
            check(fixture.oracle.events.get(1).values.equals(new Packet(move).values), "MOVE values changed");
            fixture.input(event(1, 3, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, 0, 31), true);
            fixture.input(event(1, 4, MotionEvent.ACTION_POINTER_UP | 1 << 8, 0, 31), true);
            MotionEvent up = event(1, 5, MotionEvent.ACTION_UP, 0);
            long ticket = fixture.dispatcher.beginInput(up);
            try {
                check(!fixture.dispatcher.dispatch(up), "Changed UP result");
                check(!fixture.dispatcher.dispatch(up), "Duplicate UP changed false result");
            } finally {
                fixture.dispatcher.endInput(up, ticket);
            }
            MotionEvent outside = event(99, 99, MotionEvent.ACTION_OUTSIDE, 0);
            check(!fixture.input(outside, true), "Changed unrelated action result");
            check(fixture.oracle.events.get(5).values.equals(new Packet(outside).values), "Unrelated action changed");
        }
    }

    public static void cancellation() {
        try (Fixture fixture = new Fixture(true)) {
            fixture.input(event(10, 10, MotionEvent.ACTION_DOWN, 0), true);
            MotionEvent cancel = event(999, 1000, MotionEvent.ACTION_CANCEL, 31);
            String cancelValues = new Packet(cancel).values;
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    fixture.callback = null;
                    event.setAction(MotionEvent.ACTION_MOVE);
                }
            };
            fixture.input(cancel, true);
            check(fixture.oracle.events.get(1).values.equals(cancelValues), "Source CANCEL was rejected or rewritten");
            int ended = fixture.oracle.events.size();
            fixture.dispatcher.dispatch(event(10, 1001, MotionEvent.ACTION_DOWN, 0));
            fixture.input(event(10, 1002, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, 0, 31), true);
            check(fixture.oracle.events.size() == ended, "Cancelled gesture restarted without new input DOWN");
            fixture.input(event(10, 1100, MotionEvent.ACTION_DOWN, 31), true);
            check(!fixture.dispatcher.dispatch(event(9, 1101, MotionEvent.ACTION_CANCEL, 0)), "Stale synthetic CANCEL killed a new gesture");
            fixture.dispatcher.cancel();
            int cancelled = fixture.oracle.events.size();
            fixture.dispatcher.cancel();
            fixture.dispatcher.dispatch(event(10, 1102, MotionEvent.ACTION_MOVE, 31));
            fixture.dispatcher.dispatch(event(10, 1103, MotionEvent.ACTION_UP, 31));
            check(fixture.oracle.events.size() == cancelled, "Cancellation was not idempotent");
            fixture.input(event(20, 1200, MotionEvent.ACTION_DOWN, 0), true);
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    fixture.callback = null;
                    fixture.input(event(30, 1201, MotionEvent.ACTION_DOWN, 31), true);
                }
            };
            fixture.dispatcher.dispose();
            check(!fixture.oracle.active, "Disposed receiver revived through cancellation callback");
            int disposed = fixture.oracle.events.size();
            fixture.input(event(40, 1300, MotionEvent.ACTION_DOWN, 0), true);
            fixture.input(event(40, 1301, MotionEvent.ACTION_OUTSIDE, 0), true);
            check(fixture.oracle.events.size() == disposed, "Disposed dispatcher still delivered events");
        }
    }

    public static void explicitCancellation() {
        for (boolean accepted : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(accepted)) {
                boolean[] pending = {false};
                fixture.callback = event -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) pending[0] = true;
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) pending[0] = false;
                };
                fixture.input(event(1, 1, MotionEvent.ACTION_DOWN, 0), true);
                fixture.input(event(1, 2, MotionEvent.ACTION_UP, 0), true);
                fixture.input(event(10, 10, MotionEvent.ACTION_DOWN, 31), false);
                check(pending[0], "Receiver did not retain its state after UP");
                check(!fixture.dispatcher.dispatch(event(1, 11, MotionEvent.ACTION_CANCEL, 0)),
                        "Stale cancellation reached a newer input");
                check(pending[0], "Stale cancellation changed receiver state");
                MotionEvent cancel = event(10, 12, MotionEvent.ACTION_CANCEL, 8);
                String values = new Packet(cancel).values;
                check(fixture.dispatcher.dispatch(cancel) == accepted, "Changed explicit CANCEL result");
                check(!pending[0], "Explicit cancellation was withheld without a native pointer target");
                check(fixture.oracle.events.get(2).values.equals(values), "Explicit CANCEL values changed");
                check(fixture.dispatcher.dispatch(cancel) == accepted, "Repeated explicit CANCEL result changed");
                check(fixture.oracle.events.size() == 4, "An explicit cancellation was silently deduplicated");
                fixture.input(event(90, 90, MotionEvent.ACTION_CANCEL, 7), true);
                int ended = fixture.oracle.events.size();
                fixture.dispatcher.cancel();
                fixture.dispatcher.dispose();
                check(fixture.oracle.events.size() == ended, "Automatic inactive cleanup was not idempotent");
            }
        }
        try (Fixture fixture = new Fixture(true)) {
            MotionEvent cancel = event(99, 99, MotionEvent.ACTION_CANCEL, 31);
            check(fixture.input(cancel, true), "Input CANCEL was dropped without a preceding DOWN");
            check(fixture.oracle.events.size() == 1
                    && fixture.oracle.events.get(0).values.equals(new Packet(cancel).values),
                    "Input CANCEL without DOWN changed values");
        }
    }

    public static void reentrancy() {
        try (Fixture fixture = new Fixture(true)) {
            MotionEvent newer = event(10, 11, MotionEvent.ACTION_DOWN, 31);
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    fixture.callback = null;
                    fixture.result = false;
                    check(!fixture.input(newer, true), "Changed nested receiver result");
                    fixture.result = true;
                }
            };
            check(fixture.input(event(10, 10, MotionEvent.ACTION_DOWN, 0), true), "Changed outer receiver result");
            check(!fixture.dispatcher.dispatch(newer), "Older return overwrote newer DOWN result");
            check(fixture.actions().equals(List.of(0, 3, 0)), fixture.actions());
            fixture.input(event(10, 12, MotionEvent.ACTION_MOVE, 31), true);
            fixture.input(event(10, 13, MotionEvent.ACTION_UP, 31), true);
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    fixture.callback = null;
                    fixture.dispatcher.dispatch(event(1, 2, MotionEvent.ACTION_CANCEL, 0));
                }
            };
            fixture.input(event(1, 1, MotionEvent.ACTION_DOWN, 0), true);
            fixture.input(event(1, 3, MotionEvent.ACTION_MOVE, 0), true);
            check(fixture.actions().equals(List.of(0, 3)), fixture.actions());
            fixture.input(event(1, 4, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, 0, 31), true);
            check(fixture.oracle.pointers.equals(Set.of(0, 31)), "Failed to restart after nested cancel");
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    fixture.callback = null;
                    fixture.input(event(1, 2, MotionEvent.ACTION_UP, 0), true);
                }
            };
            fixture.input(event(1, 1, MotionEvent.ACTION_DOWN, 0), true);
            check(fixture.actions().equals(List.of(0, 1)), fixture.actions());
            fixture.dispatcher.dispatch(event(1, 3, MotionEvent.ACTION_DOWN, 0));
            check(fixture.actions().equals(List.of(0, 1)), "Outer DOWN revived a nested terminal input");
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.input(event(1, 1, MotionEvent.ACTION_DOWN, 0), true);
            fixture.callback = event -> {
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    fixture.callback = null;
                    fixture.input(event(2, 2, MotionEvent.ACTION_DOWN, 31), true);
                }
            };
            fixture.dispatcher.cancel();
            fixture.input(event(2, 3, MotionEvent.ACTION_MOVE, 31), true);
            fixture.input(event(2, 4, MotionEvent.ACTION_UP, 31), true);
            check(fixture.actions().equals(List.of(0, 3, 0, 2, 1)), "Cancellation erased reentrant input");
        }
    }

    private static int programs;

    public static void finitePrograms() {
        programs = 0;
        generate(new char[4], 0);
        check(programs == 32768, "Missing finite programs: " + programs);
    }

    private static void generate(char[] program, int index) {
        if (index == program.length) {
            for (boolean accepted : new boolean[]{false, true}) {
                for (boolean initialForward : new boolean[]{false, true}) {
                    for (int first : new int[]{0, 31}) runProgram(program, accepted, initialForward, first);
                }
            }
            return;
        }
        for (char action : new char[]{'M', 'P', 'L', 'U', 'C', 'N', 'D', 'X'}) {
            program[index] = action;
            generate(program, index + 1);
        }
    }

    private static void runProgram(char[] program, boolean accepted, boolean forward, int first) {
        try (Fixture fixture = new Fixture(accepted)) {
            List<Integer> fingers = new ArrayList<>(List.of(first));
            boolean active = true;
            long clock = 10;
            fixture.input(event(10, clock, MotionEvent.ACTION_DOWN, first), forward);
            for (int step = 0; step < program.length; step++) {
                clock++;
                int[] ids = fingers.stream().mapToInt(Integer::intValue).toArray();
                switch (program[step]) {
                    case 'M':
                        fixture.input(event(10, clock, MotionEvent.ACTION_MOVE, ids), step % 2 == 0);
                        break;
                    case 'P':
                        if (!active || fingers.size() == 3) break;
                        int added = Arrays.stream(new int[]{0, 7, 31}).filter(id -> !fingers.contains(id)).findFirst().orElseThrow();
                        int index = step % 2 == 0 ? 0 : fingers.size();
                        fingers.add(index, added);
                        fixture.input(event(10, clock, MotionEvent.ACTION_POINTER_DOWN | index << 8,
                                fingers.stream().mapToInt(Integer::intValue).toArray()), true);
                        check(fixture.oracle.pointers.equals(new HashSet<>(fingers)), "Forwarded handoff lost fingers");
                        break;
                    case 'L':
                        if (!active || fingers.size() < 2) break;
                        int removed = step % fingers.size();
                        fixture.input(event(10, clock, MotionEvent.ACTION_POINTER_UP | removed << 8, ids), true);
                        fingers.remove(removed);
                        break;
                    case 'U':
                        if (!active || fingers.size() != 1) break;
                        fixture.input(event(10, clock, MotionEvent.ACTION_UP, ids), step % 2 == 0);
                        active = false;
                        break;
                    case 'C':
                        fixture.input(event(1000 + clock, 1000 + clock, MotionEvent.ACTION_CANCEL, 8), true);
                        active = false;
                        break;
                    case 'N':
                        fingers.clear(); fingers.add(first);
                        fixture.input(event(10, clock, MotionEvent.ACTION_DOWN, first), forward);
                        active = true;
                        break;
                    case 'D':
                        int before = fixture.oracle.events.size();
                        if (!active || fingers.size() == 1 && fingers.get(0) == first) {
                            fixture.dispatcher.dispatch(event(10, clock + 100, MotionEvent.ACTION_DOWN, first));
                            if (!active) check(fixture.oracle.events.size() == before, "Late callback created orphan DOWN");
                        }
                        break;
                    case 'X':
                        fixture.dispatcher.dispatch(event(10, clock, MotionEvent.ACTION_CANCEL, ids));
                        check(!fixture.oracle.active, "Explicit cancellation left native pointers");
                        break;
                    default: throw new AssertionError();
                }
            }
            programs++;
        } catch (AssertionError error) {
            throw new AssertionError("Program " + new String(program) + ", result=" + accepted
                    + ", forward=" + forward + ", first=" + first + ": " + error.getMessage(), error);
        }
    }
}
