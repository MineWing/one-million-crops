package com.onemillioncrops.service;

import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SummaryActionServiceTest {
    @Test
    void parsesActionTagsAndAllowsBlankMessages() {
        assertEquals(new SummaryActionService.ParsedAction("message", ""),
                SummaryActionService.parse("[message] "));
        assertEquals(new SummaryActionService.ParsedAction("sound", "BLOCK_NOTE_BLOCK_CHIME"),
                SummaryActionService.parse("[SOUND] BLOCK_NOTE_BLOCK_CHIME"));
        assertNull(SummaryActionService.parse("message without a tag"));
    }

    @Test
    void expandsContributorPlaceholdersInLeaderboardOrder() {
        List<String> expanded = SummaryActionService.expand(
                "%player%: %amount% of %total% in %minutes%m",
                List.of(
                        new SummaryActionService.SummaryEntry("Alex", 42L),
                        new SummaryActionService.SummaryEntry("Sam", 7L)
                ),
                Map.of("total", "49", "minutes", "30")
        );

        assertEquals(List.of("Alex: 42 of 49 in 30m", "Sam: 7 of 49 in 30m"), expanded);
    }

    @Test
    void expandsSummaryOnlyPlaceholdersOnce() {
        assertEquals(List.of("Total 1,234"), SummaryActionService.expand(
                "Total %total%",
                List.of(new SummaryActionService.SummaryEntry("Alex", 1_234L)),
                Map.of("total", "1,234", "minutes", "30")));
    }

    @Test
    void stylesOnlyNewPersonalBestAmounts() {
        assertEquals(List.of(
                        "<#FFD166><bold>1,234</bold></#FFD166> "
                                + "<dark_gray>(</dark_gray><#8CE99A>NEW PB</#8CE99A><dark_gray>)</dark_gray>",
                        "<#8CE99A><bold>500</bold></#8CE99A>"),
                SummaryActionService.expand("%amount-display%", List.of(
                        new SummaryActionService.SummaryEntry("Alex", 1_234L, true),
                        new SummaryActionService.SummaryEntry("Sam", 500L, false)
                ), Map.of()));
    }

    @Test
    void personalBestMustBePositiveAndStrictlyHigher() {
        UUID player = UUID.randomUUID();
        Map<UUID, Long> personalBests = new HashMap<>();

        assertEquals(false, HarvestSummaryService.recordPersonalBest(personalBests, player, 0L));
        assertEquals(true, HarvestSummaryService.recordPersonalBest(personalBests, player, 100L));
        assertEquals(false, HarvestSummaryService.recordPersonalBest(personalBests, player, 100L));
        assertEquals(false, HarvestSummaryService.recordPersonalBest(personalBests, player, 99L));
        assertEquals(true, HarvestSummaryService.recordPersonalBest(personalBests, player, 101L));
    }

    @Test
    void convertsConfiguredMinutesToSchedulerUnits() {
        assertEquals(36_000L, HarvestSummaryService.intervalTicks(30));
        assertEquals(1_800_000L, HarvestSummaryService.intervalMillis(30));
    }

    @Test
    void countdownProgressDrainsTowardTheSummary() {
        assertEquals(1.0f, HarvestSummaryService.countdownProgress(1_800_000L, 1_800_000L));
        assertEquals(0.5f, HarvestSummaryService.countdownProgress(900_000L, 1_800_000L));
        assertEquals(0.0f, HarvestSummaryService.countdownProgress(0L, 1_800_000L));
        assertEquals(0.0f, HarvestSummaryService.countdownProgress(-1L, 1_800_000L));
    }

    @Test
    void formatsAndParsesCountdownBossBar() {
        assertEquals("30:00", HarvestSummaryService.countdownTime(1_800_000L));
        assertEquals("1:02", HarvestSummaryService.countdownTime(61_001L));
        assertEquals("0:00", HarvestSummaryService.countdownTime(0L));
        assertEquals(new HarvestSummaryService.CountdownStyle(
                        "<green>Next summary %time%</green>", BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10),
                HarvestSummaryService.countdownStyle(List.of(
                        "[countdown] <green>Next summary %time%</green> | YELLOW | NOTCHED_10")));
    }
}
