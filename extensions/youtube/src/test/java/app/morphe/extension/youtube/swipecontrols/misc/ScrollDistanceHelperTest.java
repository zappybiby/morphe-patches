/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.swipecontrols.misc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ScrollDistanceHelperTest {
    @Test
    public void exactUnitPreservesDirection() {
        for (int unit : new int[]{1, 10, 26}) for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(unit, steps::add);
            helper.add(direction * unit);
            assertEquals(List.of(direction), steps);
            helper.add(0);
            assertEquals(List.of(direction), steps);
        }
    }

    @Test
    public void repeatedExactUnitsEachProduceAFullStep() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            for (int i = 0; i < 3; i++) helper.add(direction * 26);
            assertEquals(List.of(direction, direction, direction), steps);
        }
    }

    @Test
    public void oversizedExactMultiplePreservesEveryStepDirection() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            helper.add(direction * 78);
            assertEquals(List.of(direction, direction, direction), steps);
        }
    }

    @Test
    public void oversizedDistanceRetainsItsRemainder() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            helper.add(direction * 65);
            assertEquals(List.of(direction, direction), steps);
            helper.add(direction * 13);
            assertEquals(List.of(direction, direction, direction), steps);
        }
    }

    @Test
    public void fractionalDistancesAccumulateToAFullStep() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            for (int i = 0; i < 3; i++) helper.add(direction * 6.5);
            assertEquals(List.of(), steps);
            helper.add(direction * 6.5);
            assertEquals(List.of(direction), steps);
        }
    }

    @Test
    public void directionReversalCancelsDistanceBeforeEmittingSteps() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            helper.add(direction * 20);
            helper.add(direction * -40);
            assertEquals(List.of(), steps);
            helper.add(direction * -6);
            helper.add(direction * 26);
            assertEquals(List.of(-direction, direction), steps);
        }
    }

    @Test
    public void resetDiscardsPartialDistance() {
        for (int direction : new int[]{1, -1}) {
            List<Integer> steps = new ArrayList<>();
            ScrollDistanceHelper helper = new ScrollDistanceHelper(26, steps::add);
            helper.add(direction * 25);
            helper.reset();
            helper.add(direction);
            assertEquals(List.of(), steps);
            helper.add(direction * 25);
            assertEquals(List.of(direction), steps);
        }
    }
}
