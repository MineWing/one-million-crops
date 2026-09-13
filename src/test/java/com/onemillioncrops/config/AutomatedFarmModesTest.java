package com.onemillioncrops.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AutomatedFarmModesTest {
    @Test
    void hoppersAreOffByDefaultIncludingLegacyConfigs() {
        var yaml = new YamlConfiguration();
        yaml.set("counting.allow-automated-farms", true);
        var modes = AutomatedFarmModes.read(yaml);
        assertTrue(modes.water());
        assertTrue(modes.pistons());
        assertFalse(modes.hoppers());
    }

    @Test
    void eachSourceOverridesLegacyMasterIndependently() {
        var yaml = new YamlConfiguration();
        yaml.set("counting.allow-automated-farms", false);
        yaml.set("counting.automated-farms.water", true);
        yaml.set("counting.automated-farms.pistons", false);
        yaml.set("counting.automated-farms.hoppers", true);
        var modes = AutomatedFarmModes.read(yaml);
        assertTrue(modes.enabled("water"));
        assertFalse(modes.enabled("pistons"));
        assertTrue(modes.enabled("hoppers"));
        assertTrue(modes.enabled("all"));
        assertThrows(IllegalArgumentException.class, () -> modes.enabled("unknown"));
    }

    @Test
    void disablingWaterDoesNotDisablePistonsAndViceVersa() {
        assertFalse(new AutomatedFarmModes(false, true, false).water());
        assertTrue(new AutomatedFarmModes(false, true, false).pistons());
        assertTrue(new AutomatedFarmModes(true, false, false).water());
        assertFalse(new AutomatedFarmModes(true, false, false).pistons());
        assertFalse(new AutomatedFarmModes(false, false, false).enabled("all"));
    }
}
