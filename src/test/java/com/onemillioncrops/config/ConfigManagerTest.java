package com.onemillioncrops.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ConfigManagerTest {
    @Test
    void unknownCelebrationSoundFailsTheRead() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigManager.requireKnownSound("not_a_sound", name -> false));
        assertDoesNotThrow(() -> ConfigManager.requireKnownSound("UI_TOAST_CHALLENGE_COMPLETE", name -> true));
    }
}
