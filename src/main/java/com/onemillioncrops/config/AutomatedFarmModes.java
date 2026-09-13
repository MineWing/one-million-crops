package com.onemillioncrops.config;

import org.bukkit.configuration.ConfigurationSection;

public record AutomatedFarmModes(boolean water, boolean pistons, boolean hoppers) {
    public static AutomatedFarmModes read(ConfigurationSection config) {
        boolean legacy = config.getBoolean("counting.allow-automated-farms", true);
        return new AutomatedFarmModes(config.getBoolean("counting.automated-farms.water", legacy),
                config.getBoolean("counting.automated-farms.pistons", legacy),
                config.getBoolean("counting.automated-farms.hoppers", false));
    }

    public boolean enabled(String source) {
        return switch (source) {
            case "water" -> water;
            case "pistons" -> pistons;
            case "hoppers" -> hoppers;
            case "all" -> water || pistons || hoppers;
            default -> throw new IllegalArgumentException("Unknown farm source: " + source);
        };
    }
}
