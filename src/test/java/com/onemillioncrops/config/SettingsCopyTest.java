package com.onemillioncrops.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettingsCopyTest {
    @Test
    void withAllowAutomatedFarmsReplacesOnlyThatSetting() {
        PluginSettings original = new PluginSettings(1_000_000L, PluginSettings.ParticipantMode.EVERYONE, Set.of(),
                false, true, true, 5, "progress.db", true, true, 1, 10, 80, 7, 120, List.of("CROPS"),
                5, false, List.of(25, 50), 3, 10, "UI_TOAST_CHALLENGE_COMPLETE", 1.0f, 1.0f);
        assertFalse(original.allowAutomatedFarms());

        PluginSettings updated = original.withAllowAutomatedFarms(true);

        assertTrue(updated.allowAutomatedFarms());
        assertEquals(original, updated.withAllowAutomatedFarms(false));
    }
}
