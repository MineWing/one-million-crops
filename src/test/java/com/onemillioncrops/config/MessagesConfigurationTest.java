package com.onemillioncrops.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MessagesConfigurationTest {
    private static final Pattern LEGACY_PLACEHOLDER = Pattern.compile(
            "<(?:crop|file|completed|total|amount|target|percent|time|players|player|minutes|url|entries|pitch)>"
    );

    @Test
    void everyConfiguredOutputUsesAnEnabledActionSection() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        for (String key : yaml.getKeys(false)) {
            if (key.equals("prefix") || key.equals("gui")
                    || key.equals(ConfigManager.MESSAGE_PALETTE_VERSION_KEY)) {
                continue;
            }
            var section = yaml.getConfigurationSection(key);
            assertNotNull(section, key + " must be an action section");
            assertTrue(section.isBoolean("enabled"), key + " must have an enabled switch");
            assertFalse(section.getStringList("actions").isEmpty(), key + " must have actions");
        }
    }

    @Test
    void loreSupportsListsAndLegacyScalarValues() {
        assertEquals(List.of("line one", "", "line three"),
                ConfigManager.configuredLines(List.of("line one", "", "line three")));
        assertEquals(List.of("old line"), ConfigManager.configuredLines("old line"));
        assertEquals(List.of(), ConfigManager.configuredLines(""));
    }

    @Test
    void bundledGuiLoreUsesYamlLists() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        for (String path : List.of(
                "gui.navigation.previous", "gui.navigation.next", "gui.navigation.close",
                "gui.crop-toggle.crop", "gui.crop-toggle.summary", "gui.progress.crop",
                "gui.progress.overall", "gui.crop-wand.lore", "gui.plant-wand.wand",
                "gui.plant-wand.crop-option", "gui.plant-wand.menu-summary")) {
            assertTrue(yaml.isList(path), path + " must be a YAML lore list");
        }
    }

    @Test
    void configuredMessagesUsePercentPlaceholders() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        for (Object value : yaml.getValues(true).values()) {
            if (value instanceof String text) {
                assertFalse(LEGACY_PLACEHOLDER.matcher(text).find(), text);
            } else if (value instanceof List<?> values) {
                for (Object entry : values) {
                    if (entry instanceof String text) {
                        assertFalse(LEGACY_PLACEHOLDER.matcher(text).find(), text);
                    }
                }
            }
        }
    }

    @Test
    void everyMiniMessagePayloadParses() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        var miniMessage = MiniMessage.miniMessage();

        miniMessage.deserialize(yaml.getString("prefix", ""));
        for (String key : yaml.getKeys(false)) {
            for (String action : yaml.getStringList(key + ".actions")) {
                int payloadStart = action.indexOf(']') + 1;
                if (payloadStart <= 0) {
                    continue;
                }
                String tag = action.substring(1, payloadStart - 1).toLowerCase();
                String payload = action.substring(payloadStart).stripLeading();
                if (tag.equals("message") || tag.equals("broadcast") || tag.equals("actionbar")) {
                    miniMessage.deserialize(payload);
                } else if (tag.equals("title")) {
                    String[] fields = payload.split("\\s*\\|\\s*", -1);
                    miniMessage.deserialize(fields[0]);
                    if (fields.length > 1) {
                        miniMessage.deserialize(fields[1]);
                    }
                } else if (tag.equals("bossbar") || tag.equals("countdown")) {
                    miniMessage.deserialize(payload.split("\\s*\\|\\s*", -1)[0]);
                }
            }
        }
    }

    @Test
    void harvestSummaryUsesConditionalPersonalBestAmountDisplay() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertTrue(yaml.getStringList("harvestSummary.actions").stream()
                .anyMatch(action -> action.contains("%amount-display%")));
    }

    @Test
    void harvestSummaryCountdownUsesRequestedTitle() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertTrue(yaml.getStringList("harvest-summary-countdown.actions").stream()
                .anyMatch(action -> action.contains("NEXT HARVEST") && action.contains("%time%")));
    }

    @Test
    void paletteMigrationUpdatesCopyWhilePreservingSettings() {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/messages.yml"));
        var bundled = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        var installed = new YamlConfiguration();
        installed.set("prefix", "old");
        installed.set("help.enabled", false);
        installed.set("help.actions", List.of("[message] old"));
        installed.set("harvestSummary.amount", 60);
        installed.setDefaults(bundled);

        assertTrue(ConfigManager.applyMessagePalette(installed, bundled));
        assertEquals(bundled.getString("prefix"), installed.getString("prefix"));
        assertEquals(bundled.getStringList("help.actions"), installed.getStringList("help.actions"));
        assertFalse(installed.getBoolean("help.enabled"));
        assertEquals(60, installed.getInt("harvestSummary.amount"));
        assertFalse(ConfigManager.applyMessagePalette(installed, bundled));
    }
}
