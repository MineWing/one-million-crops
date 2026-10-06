package com.onemillioncrops.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettingsCopyTest {
    @Test
    void withSettingReplacesOnlyTheNamedComponent() throws Exception {
        PluginSettings original = sampleSettings();
        assertFalse(original.allowAutomatedFarms());

        PluginSettings updated = ConfigManager.withSetting(original, "allowAutomatedFarms", true);

        assertTrue(updated.allowAutomatedFarms());
        assertEquals(original, ConfigManager.withSetting(updated, "allowAutomatedFarms", false));
    }

    @Test
    void withSettingRejectsUnknownComponents() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigManager.withSetting(sampleSettings(), "noSuchSetting", true));
    }

    /** Builds settings without naming every component, so this test survives settings being added or removed. */
    private static PluginSettings sampleSettings() throws Exception {
        RecordComponent[] components = PluginSettings.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            Class<?> type = components[index].getType();
            types[index] = type;
            values[index] = sampleValue(type, index);
        }
        return PluginSettings.class.getDeclaredConstructor(types).newInstance(values);
    }

    private static Object sampleValue(Class<?> type, int index) {
        if (type == long.class) {
            return 100L + index;
        }
        if (type == int.class) {
            return index;
        }
        if (type == float.class) {
            return (float) index;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == String.class) {
            return "value-" + index;
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        return null;
    }
}
