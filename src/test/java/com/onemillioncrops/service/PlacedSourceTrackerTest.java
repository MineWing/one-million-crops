package com.onemillioncrops.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacedSourceTrackerTest {
    @Test
    void movesAChainOfTrackedBlocksWithoutLosingAnyMarker() {
        Set<Integer> tracked = new HashSet<>(Set.of(1, 2, 3));

        push(tracked, List.of(1, 2, 3));

        assertEquals(Set.of(2, 3, 4), tracked);
    }

    @Test
    void onlyCarriesMarkersOfTrackedBlocks() {
        Set<Integer> tracked = new HashSet<>(Set.of(1, 3));

        push(tracked, List.of(1, 2, 3));

        assertEquals(Set.of(2, 4), tracked);
    }

    @Test
    void consumesEverySourceBeforeMarkingADestination() {
        Set<Integer> tracked = new HashSet<>(Set.of(5));

        // Listed front first, as pistons do: the block leaving 6 must not take the marker 5 brings there.
        List<Integer> destinations = PlacedSourceTracker.relocate(List.of(6, 5), tracked::remove, value -> value + 1);

        assertEquals(List.of(6), destinations);
        assertEquals(Set.of(), tracked);
    }

    private static void push(Set<Integer> tracked, List<Integer> blocks) {
        PlacedSourceTracker.relocate(blocks, tracked::remove, value -> value + 1).forEach(tracked::add);
    }
}
