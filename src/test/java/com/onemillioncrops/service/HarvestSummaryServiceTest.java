package com.onemillioncrops.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Test
    void leaderboardKeepsTheTopRowsInRankOrder() {
        List<SummaryActionService.SummaryEntry> entries = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            entries.add(new SummaryActionService.SummaryEntry("Player" + index, index));
        }
        entries.add(new SummaryActionService.SummaryEntry("bea", 24L));

        List<SummaryActionService.SummaryEntry> leaderboard = HarvestSummaryService.leaderboard(entries);

        assertEquals(HarvestSummaryService.MAX_ROWS, leaderboard.size());
        assertEquals(List.of("bea", "Player24", "Player23", "Player22"),
                leaderboard.subList(0, 4).stream().map(SummaryActionService.SummaryEntry::player).toList());
        assertEquals(16L, leaderboard.getLast().amount());
    }

    @Test
    void leaderboardKeepsEveryRowOnSmallServers() {
        assertEquals(List.of(new SummaryActionService.SummaryEntry("Alex", 5L),
                        new SummaryActionService.SummaryEntry("Sam", 0L)),
                HarvestSummaryService.leaderboard(List.of(new SummaryActionService.SummaryEntry("Sam", 0L),
                        new SummaryActionService.SummaryEntry("Alex", 5L))));
    }
}
