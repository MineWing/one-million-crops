package com.onemillioncrops.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ScoreboardServiceTest {
    @Test
    void compactSwitchesToMillionsBeforeRoundingReachesAThousandK() {
        assertEquals("999", ScoreboardService.compact(999));
        assertEquals("1.0k", ScoreboardService.compact(1_000));
        assertEquals("999.9k", ScoreboardService.compact(999_949));
        assertEquals("1.00M", ScoreboardService.compact(999_950));
        assertEquals("1.00M", ScoreboardService.compact(999_999));
        assertEquals("1.00M", ScoreboardService.compact(1_000_000));
    }
}
