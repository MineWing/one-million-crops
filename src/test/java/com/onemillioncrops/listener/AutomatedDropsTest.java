package com.onemillioncrops.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class AutomatedDropsTest {
    @Test
    void takesOnlyTheAcceptedAmountAcrossStacks() {
        assertArrayEquals(new int[] {0, 3, 5}, AutomatedDrops.remainingAfterTaking(new int[] {4, 5, 5}, 6));
    }

    @Test
    void leavesEveryDropWhenNothingWasAccepted() {
        assertArrayEquals(new int[] {4, 5}, AutomatedDrops.remainingAfterTaking(new int[] {4, 5}, 0));
        assertArrayEquals(new int[] {4, 5}, AutomatedDrops.remainingAfterTaking(new int[] {4, 5}, -1));
    }

    @Test
    void takesEverythingWhenTheWholeAmountWasAccepted() {
        assertArrayEquals(new int[] {0, 0}, AutomatedDrops.remainingAfterTaking(new int[] {4, 5}, 9));
        assertArrayEquals(new int[] {0, 0}, AutomatedDrops.remainingAfterTaking(new int[] {4, 5}, 100));
    }
}
