package com.onemillioncrops.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumbersTest {
    @Test
    void saturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, Numbers.saturatingAdd(Long.MAX_VALUE - 1, 5));
        assertEquals(Long.MAX_VALUE, Numbers.saturatingMultiply(Long.MAX_VALUE / 2, 3));
    }

    @Test
    void addsAndMultipliesNormallyWithinRange() {
        assertEquals(12L, Numbers.saturatingAdd(7, 5));
        assertEquals(3_000_000L, Numbers.saturatingMultiply(1_000_000, 3));
        assertEquals(0L, Numbers.saturatingMultiply(1_000_000, 0));
    }
}
