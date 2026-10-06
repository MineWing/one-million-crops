package com.onemillioncrops.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HarvestSummaryServiceTest {
    @Test
    void loadedPersonalBestsNeverLowerABestSetWhileLoading() {
        UUID announced = UUID.randomUUID();
        UUID storedOnly = UUID.randomUUID();
        UUID storedHigher = UUID.randomUUID();
        Map<UUID, Long> personalBests = new HashMap<>(Map.of(announced, 500L, storedHigher, 10L));

        HarvestSummaryService.mergePersonalBests(personalBests,
                Map.of(announced, 200L, storedOnly, 75L, storedHigher, 90L));

        assertEquals(Map.of(announced, 500L, storedOnly, 75L, storedHigher, 90L), personalBests);
    }
}
