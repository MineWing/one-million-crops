package com.onemillioncrops.util;

/** Overflow-safe arithmetic for progress totals that may approach {@link Long#MAX_VALUE}. */
public final class Numbers {
    private Numbers() {
    }

    public static long saturatingAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long saturatingMultiply(long value, int multiplier) {
        return multiplier > 0 && value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
