package com.onemillioncrops.config;

import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.SoundResolver;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ConfigManager {
    static final String MESSAGE_PALETTE_VERSION_KEY = "message-palette-version";
    static final int CURRENT_MESSAGE_PALETTE_VERSION = 1;

    private final JavaPlugin plugin;
    private PluginSettings settings;
    private Map<String, CropDefinition> crops = Map.of();
    private Map<String, CropDefinition> configuredCrops = Map.of();
    private Set<String> enabledCropIds = Set.of();
    private Map<Material, CropDefinition> cropsByItem = Map.of();
    private HarvestSummarySettings harvestSummary;
    private YamlConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ensureResources();
        apply(read());
    }

    public void ensureResources() {
        plugin.saveDefaultConfig();
        saveResourceIfMissing("crops.yml");
        saveResourceIfMissing("messages.yml");
    }

    public LoadedConfiguration read() {
        var config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        long target = Math.max(1L, config.getLong("challenge.target-per-crop", 1_000_000L));
        PluginSettings.ParticipantMode participantMode;
        try {
            participantMode = PluginSettings.ParticipantMode.valueOf(
                    config.getString("participants.mode", "EVERYONE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid participants.mode; using EVERYONE.");
            participantMode = PluginSettings.ParticipantMode.EVERYONE;
        }

        Set<UUID> allowlist = new LinkedHashSet<>();
        for (String value : config.getStringList("participants.allowlist")) {
            try {
                allowlist.add(UUID.fromString(value));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid participant UUID: " + value);
            }
        }

        String databaseFile = config.getString("storage.database-file", "progress.db");
        if (databaseFile == null || databaseFile.isBlank()) {
            plugin.getLogger().warning("storage.database-file is blank; using progress.db.");
            databaseFile = "progress.db";
        }
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        if (!dataFolder.resolve(databaseFile).normalize().startsWith(dataFolder)) {
            throw new IllegalArgumentException("storage.database-file must remain inside the plugin folder");
        }
        String webBindAddress = config.getString("web.bind-address", "127.0.0.1");
        if (webBindAddress == null || webBindAddress.isBlank()) {
            plugin.getLogger().warning("web.bind-address is blank; using 127.0.0.1.");
            webBindAddress = "127.0.0.1";
        }
        String webPublicUrl = config.getString("web.public-url", "");
        if (webPublicUrl == null) {
            webPublicUrl = "";
        }
        String completionSound = config.getString(
                "celebration.completion-sound", "UI_TOAST_CHALLENGE_COMPLETE");
        if (completionSound == null || completionSound.isBlank()) {
            completionSound = "UI_TOAST_CHALLENGE_COMPLETE";
        }

        PluginSettings loadedSettings = new PluginSettings(
                target,
                participantMode,
                Collections.unmodifiableSet(allowlist),
                config.getBoolean("counting.allow-automated-farms", true),
                config.getBoolean("counting.block-player-redrops", true),
                config.getBoolean("counting.require-mature-crops", true),
                Math.max(1, config.getInt("storage.autosave-seconds", 5)),
                databaseFile,
                config.getBoolean("storage.backup-before-reset", true),
                config.getBoolean("web.enabled", true),
                webBindAddress,
                Math.clamp(config.getInt("web.port", 8765), 1, 65_535),
                webPublicUrl.strip(),
                Math.clamp(config.getInt("web.refresh-ticks", 20), 1, 1_200),
                Math.clamp(config.getInt("web.history.sample-seconds", 30), 5, 3_600),
                Math.clamp(config.getInt("web.history.retention-hours", 24), 1, 720),
                config.getBoolean("scoreboard.enabled-by-default", true),
                Math.clamp(config.getInt("scoreboard.animation-ticks", 2), 1, 20),
                Math.max(1, config.getInt("scoreboard.refresh-ticks", 10)),
                Math.max(20, config.getInt("scoreboard.page-change-ticks", 80)),
                Math.clamp(config.getInt("scoreboard.crops-per-page", 7), 1, 8),
                Math.clamp(config.getInt("scoreboard.title-animation-frames", 40), 4, 200),
                nonEmpty(config.getStringList("scoreboard.title-frames"),
                        List.of("<gradient:#55ff55:#ffd54a><bold>1,000,000 CROPS</bold></gradient>")),
                Math.max(1, config.getInt("gui.animation-ticks", 5)),
                config.getBoolean("gui.pickup-sound", false),
                nonEmpty(config.getIntegerList("celebration.milestones"), List.of(25, 50, 75, 90)),
                Math.max(0, config.getInt("celebration.fireworks", 3)),
                Math.max(1, config.getInt("celebration.firework-gap-ticks", 10)),
                completionSound,
                (float) Math.clamp(finite(config.getDouble("celebration.completion-volume", 1.0), 1.0), 0.0, 10.0),
                (float) Math.clamp(finite(config.getDouble("celebration.completion-pitch", 1.0), 1.0), 0.0, 2.0)
        );

        CropMaps cropMaps = readCrops();
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration loadedMessages = YamlConfiguration.loadConfiguration(messagesFile);
        migrateLegacyMessageActions(loadedMessages, messagesFile);
        boolean migrateHarvestSummary = !loadedMessages.isConfigurationSection("harvestSummary")
                || loadedMessages.contains("harvest-summary-header", true)
                || loadedMessages.contains("harvest-summary-player", true)
                || loadedMessages.contains("harvest-summary-footer", true);
        YamlConfiguration bundledMessages = bundledMessages();
        loadedMessages.setDefaults(bundledMessages);
        if (migrateHarvestSummary) {
            migrateHarvestSummary(loadedMessages, messagesFile);
        }
        migrateHarvestSummaryPersonalBest(loadedMessages, messagesFile);
        migrateHarvestSummaryCountdownTitle(loadedMessages, messagesFile);
        migrateAllMessagePlaceholders(loadedMessages, messagesFile);
        migrateMessagePalette(loadedMessages, bundledMessages, messagesFile);
        persistMessageActionDefaults(loadedMessages, messagesFile);
        HarvestSummarySettings loadedHarvestSummary = new HarvestSummarySettings(
                loadedMessages.getBoolean("harvestSummary.enabled", true),
                Math.clamp(loadedMessages.getInt("harvestSummary.amount", 30), 1, 10_080),
                List.copyOf(loadedMessages.getStringList("harvestSummary.actions"))
        );
        return new LoadedConfiguration(loadedSettings, cropMaps.enabledCrops(), cropMaps.configuredCrops(),
                cropMaps.enabledIds(), cropMaps.byItem(), loadedHarvestSummary, loadedMessages);
    }

    private YamlConfiguration bundledMessages() {
        InputStream bundled = plugin.getResource("messages.yml");
        if (bundled == null) {
            throw new IllegalStateException("The plugin JAR is missing messages.yml");
        }
        try (InputStreamReader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the bundled messages.yml", exception);
        }
    }

    private void migrateMessagePalette(YamlConfiguration messages, YamlConfiguration bundled,
                                       File messagesFile) {
        if (!applyMessagePalette(messages, bundled)) {
            return;
        }
        try {
            messages.save(messagesFile);
            plugin.getLogger().info("Updated messages.yml to the unified Crops message palette.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update the messages.yml palette", exception);
        }
    }

    static boolean applyMessagePalette(YamlConfiguration messages, YamlConfiguration bundled) {
        if (messages.contains(MESSAGE_PALETTE_VERSION_KEY, true)
                && messages.getInt(MESSAGE_PALETTE_VERSION_KEY) >= CURRENT_MESSAGE_PALETTE_VERSION) {
            return false;
        }

        messages.set("prefix", bundled.getString("prefix", ""));
        for (String key : bundled.getKeys(false)) {
            String actionsPath = key + ".actions";
            if (bundled.isList(actionsPath)) {
                messages.set(actionsPath, List.copyOf(bundled.getStringList(actionsPath)));
            }
        }
        messages.set(MESSAGE_PALETTE_VERSION_KEY, CURRENT_MESSAGE_PALETTE_VERSION);
        return true;
    }

    private void migrateHarvestSummary(YamlConfiguration loadedMessages, File messagesFile) {
        loadedMessages.set("harvestSummary.enabled",
                loadedMessages.getBoolean("harvestSummary.enabled", true));
        loadedMessages.set("harvestSummary.amount",
                loadedMessages.getInt("harvestSummary.amount", 30));
        loadedMessages.set("harvestSummary.actions",
                loadedMessages.getStringList("harvestSummary.actions"));
        loadedMessages.set("harvest-summary-header", null);
        loadedMessages.set("harvest-summary-player", null);
        loadedMessages.set("harvest-summary-footer", null);
        try {
            loadedMessages.save(messagesFile);
            plugin.getLogger().info("Added the configurable harvestSummary action section to messages.yml.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update messages.yml with harvestSummary actions", exception);
        }
    }

    private void persistMessageActionDefaults(YamlConfiguration messages, File messagesFile) {
        messages.options().copyDefaults(true);
        try {
            messages.save(messagesFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save configurable message actions", exception);
        }
    }

    private void migrateHarvestSummaryPersonalBest(YamlConfiguration messages, File messagesFile) {
        String oldDefault = "[message] <dark_gray>  ◆</dark_gray> <white>%player%</white> "
                + "<dark_gray>—</dark_gray> <green><bold>%amount%</bold> harvested</green>";
        String newDefault = "[message] <dark_gray>  ◆</dark_gray> <white>%player%</white> "
                + "<dark_gray>—</dark_gray> %amount-display% <green>harvested</green>";
        List<String> actions = messages.getStringList("harvestSummary.actions");
        List<String> migrated = actions.stream()
                .map(action -> action.equals(oldDefault) ? newDefault : action)
                .toList();
        if (migrated.equals(actions)) {
            return;
        }
        messages.set("harvestSummary.actions", migrated);
        try {
            messages.save(messagesFile);
            plugin.getLogger().info("Added personal-best styling to the harvest summary.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate harvest-summary personal-best styling", exception);
        }
    }

    private void migrateHarvestSummaryCountdownTitle(YamlConfiguration messages, File messagesFile) {
        String oldDefault = "[countdown] <gradient:#55ff55:#ffd54a><bold>NEXT HARVEST SUMMARY</bold>"
                + "</gradient> <white>%time%</white> | GREEN | PROGRESS";
        String newDefault = "[countdown] Summary in %time% | GREEN | PROGRESS";
        List<String> actions = messages.getStringList("harvest-summary-countdown.actions");
        List<String> migrated = actions.stream()
                .map(action -> action.equals(oldDefault) ? newDefault : action)
                .toList();
        if (migrated.equals(actions)) {
            return;
        }
        messages.set("harvest-summary-countdown.actions", migrated);
        try {
            messages.save(messagesFile);
            plugin.getLogger().info("Updated the harvest-summary countdown title.");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate the harvest-summary countdown title", exception);
        }
    }

    private void migrateAllMessagePlaceholders(YamlConfiguration messages, File messagesFile) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(messages.getValues(true)).entrySet()) {
            if (entry.getValue() instanceof String text) {
                String migrated = percentPlaceholders(text);
                if (!migrated.equals(text)) {
                    messages.set(entry.getKey(), migrated);
                    changed = true;
                }
            } else if (entry.getValue() instanceof List<?> values
                    && values.stream().allMatch(String.class::isInstance)) {
                List<String> migrated = values.stream()
                        .map(String.class::cast)
                        .map(ConfigManager::percentPlaceholders)
                        .toList();
                if (!migrated.equals(values)) {
                    messages.set(entry.getKey(), migrated);
                    changed = true;
                }
            }
        }
        if (changed) {
            try {
                messages.save(messagesFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not migrate message placeholders", exception);
            }
        }
    }

    private void migrateLegacyMessageActions(YamlConfiguration yaml, File messagesFile) {
        List<String> prefixed = List.of(
                "no-permission", "player-only", "unknown-crop", "reloaded", "scoreboard-on", "scoreboard-off",
                "crop-toggle-updating", "crop-enabled", "crop-disabled", "crop-toggle-last", "crop-toggle-failed",
                "reset-warning", "reset-complete", "crop-reset-complete", "backup-complete", "backup-failed",
                "harvest-summary-status", "harvest-summary-forced", "harvest-summary-not-scheduled"
        );
        boolean changed = false;
        for (String key : prefixed) {
            if (!yaml.isString(key)) {
                continue;
            }
            String message = percentPlaceholders(yaml.getString(key, ""));
            yaml.set(key, null);
            yaml.set(key + ".enabled", true);
            yaml.set(key + ".actions", List.of("[message] %prefix%" + message));
            changed = true;
        }

        if (yaml.isString("status-header") || yaml.isString("status-line")) {
            String header = percentPlaceholders(yaml.getString("status-header", ""));
            String line = percentPlaceholders(yaml.getString("status-line", ""));
            yaml.set("status-header", null);
            yaml.set("status-line", null);
            yaml.set("status.enabled", true);
            yaml.set("status.actions", List.of("[message] " + header, "[message] " + line));
            changed = true;
        }
        if (yaml.isString("milestone")) {
            String message = percentPlaceholders(yaml.getString("milestone", ""));
            yaml.set("milestone", null);
            yaml.set("milestone.enabled", true);
            yaml.set("milestone.actions", List.of(
                    "[broadcast] " + message,
                    "[actionbar] %crop% <green>reached <white><bold>%percent%%</bold></white>!</green>",
                    "[sound] BLOCK_NOTE_BLOCK_PLING 0.7 %pitch%",
                    "[particles] HAPPY_VILLAGER 20 0.6 0.8 0.6 0.05"
            ));
            changed = true;
        }
        changed |= migrateLegacyAction(yaml, "completed-broadcast", "broadcast");
        changed |= migrateLegacyAction(yaml, "grand-finale", "broadcast");
        if (yaml.isString("harvest-action-bar")) {
            yaml.set("harvest-action-bar", null);
            yaml.set("harvest-action-bar.enabled", true);
            yaml.set("harvest-action-bar.actions", List.of("[actionbar] %entries%"));
            changed = true;
        }
        for (String obsolete : List.of("title-complete", "subtitle-complete", "title-finale", "subtitle-finale")) {
            if (yaml.contains(obsolete, true)) {
                yaml.set(obsolete, null);
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(messagesFile);
                plugin.getLogger().info("Migrated messages.yml to configurable action sections and %placeholders%.");
            } catch (IOException exception) {
                throw new IllegalStateException("Could not migrate messages.yml actions", exception);
            }
        }
    }

    private static boolean migrateLegacyAction(YamlConfiguration yaml, String key, String tag) {
        if (!yaml.isString(key)) {
            return false;
        }
        String message = percentPlaceholders(yaml.getString(key, ""));
        yaml.set(key, null);
        yaml.set(key + ".enabled", true);
        yaml.set(key + ".actions", List.of("[" + tag + "] " + message));
        return true;
    }

    private static String percentPlaceholders(String input) {
        String result = input;
        for (String placeholder : List.of("crop", "file", "completed", "total", "amount", "target",
                "percent", "time", "players", "player", "minutes", "url", "entries", "pitch",
                "sound", "volume", "fireworks", "firework-gap", "amount-display")) {
            result = result.replace("<" + placeholder + ">", "%" + placeholder + "%");
        }
        return result;
    }

    public void apply(LoadedConfiguration loaded) {
        if (SoundResolver.resolve(loaded.settings().completionSound()) == null) {
            throw new IllegalArgumentException("Unknown celebration sound: "
                    + loaded.settings().completionSound());
        }
        settings = loaded.settings();
        crops = loaded.crops();
        configuredCrops = loaded.configuredCrops();
        enabledCropIds = loaded.enabledCropIds();
        cropsByItem = loaded.cropsByItem();
        harvestSummary = loaded.harvestSummary();
        messages = loaded.messages();
    }

    private CropMaps readCrops() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "crops.yml"));
        ConfigurationSection section = Objects.requireNonNull(yaml.getConfigurationSection("crops"),
                "crops.yml is missing the crops section");
        Map<String, CropDefinition> configured = new LinkedHashMap<>();
        Map<String, CropDefinition> enabled = new LinkedHashMap<>();
        Set<String> enabledIds = new LinkedHashSet<>();
        Map<Material, CropDefinition> byItem = new LinkedHashMap<>();

        for (String rawId : section.getKeys(false)) {
            ConfigurationSection cropSection = section.getConfigurationSection(rawId);
            if (cropSection == null) {
                continue;
            }
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9][a-z0-9_-]*")) {
                plugin.getLogger().warning("Invalid crop id " + rawId + "; use letters, numbers, underscores, or hyphens.");
                continue;
            }
            if (configured.containsKey(id)) {
                plugin.getLogger().warning("Duplicate crop id after normalisation: " + rawId);
                continue;
            }
            Material item = parseMaterial(cropSection.getString("item"), id + ".item");
            if (item == null || !item.isItem() || item.isAir()) {
                plugin.getLogger().warning("Crop " + id + " item must be a usable inventory material.");
                continue;
            }
            Set<Material> sources = new LinkedHashSet<>();
            for (String sourceName : cropSection.getStringList("sources")) {
                Material source = parseMaterial(sourceName, id + ".sources");
                if (source != null && source.isBlock() && !source.isAir()) {
                    sources.add(source);
                } else if (source != null) {
                    plugin.getLogger().warning("Ignoring non-block source " + source + " for " + id);
                }
            }
            if (sources.isEmpty()) {
                plugin.getLogger().warning("Crop " + id + " has no valid sources and will only work with automation enabled.");
            }
            String display = cropSection.getString("display", "<green>" + pretty(id));
            CropDefinition crop = new CropDefinition(id, item, Collections.unmodifiableSet(sources), display);
            configured.put(id, crop);
            if (!cropSection.getBoolean("enabled", true)) {
                continue;
            }
            if (byItem.putIfAbsent(item, crop) != null) {
                plugin.getLogger().warning("Duplicate crop item " + item + "; skipping " + id);
                continue;
            }
            enabled.put(id, crop);
            enabledIds.add(id);
        }
        if (enabled.isEmpty()) {
            throw new IllegalStateException("No valid crops are enabled in crops.yml");
        }
        return new CropMaps(Collections.unmodifiableMap(enabled), Collections.unmodifiableMap(configured),
                Collections.unmodifiableSet(enabledIds), Collections.unmodifiableMap(byItem));
    }

    public LoadedConfiguration setCropEnabled(String cropId, boolean enabled) throws IOException {
        String normalised = cropId == null ? "" : cropId.toLowerCase(Locale.ROOT);
        if (!configuredCrops.containsKey(normalised)) {
            throw new IllegalArgumentException("Unknown configured crop: " + cropId);
        }
        if (!enabled && enabledCropIds.size() <= 1 && enabledCropIds.contains(normalised)) {
            throw new IllegalStateException("At least one crop must remain enabled");
        }

        File cropsFile = new File(plugin.getDataFolder(), "crops.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cropsFile);
        if (!yaml.isConfigurationSection("crops." + normalised)) {
            throw new IllegalArgumentException("Unknown configured crop: " + cropId);
        }
        yaml.set("crops." + normalised + ".enabled", enabled);

        Path destination = cropsFile.toPath();
        Path temporary = Files.createTempFile(destination.getParent(), "crops-", ".yml.tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return read();
    }

    public LoadedConfiguration setAllowAutomatedFarms(boolean enabled) throws IOException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        yaml.set("counting.allow-automated-farms", enabled);

        Path destination = configFile.toPath();
        Path temporary = Files.createTempFile(destination.getParent(), "config-", ".yml.tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return read();
    }

    private Material parseMaterial(String name, String path) {
        if (name == null) {
            plugin.getLogger().warning("Missing material at " + path);
            return null;
        }
        Material material = Material.matchMaterial(name);
        if (material == null) {
            plugin.getLogger().warning("Unknown material " + name + " at " + path);
        }
        return material;
    }

    private void saveResourceIfMissing(String resource) {
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
    }

    private static <T> List<T> nonEmpty(List<T> input, List<T> fallback) {
        return input.isEmpty() ? fallback : List.copyOf(input);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static String pretty(String id) {
        String[] words = id.split("_");
        List<String> result = new ArrayList<>();
        for (String word : words) {
            result.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return String.join(" ", result);
    }

    public PluginSettings settings() {
        return settings;
    }

    public Map<String, CropDefinition> crops() {
        return crops;
    }

    public Map<String, CropDefinition> configuredCrops() {
        return configuredCrops;
    }

    public boolean isCropEnabled(String cropId) {
        return cropId != null && enabledCropIds.contains(cropId.toLowerCase(Locale.ROOT));
    }

    public CropDefinition cropByItem(Material item) {
        return cropsByItem.get(item);
    }

    public CropDefinition crop(String id) {
        return id == null ? null : crops.get(id.toLowerCase(Locale.ROOT));
    }

    public CropDefinition cropBySource(BlockState state) {
        return cropBySource(state, settings.requireMatureCrops());
    }

    public CropDefinition cropBySource(BlockState state, boolean requireMature) {
        for (CropDefinition crop : crops.values()) {
            if (crop.matchesSource(state, requireMature)) {
                return crop;
            }
        }
        return null;
    }

    public String message(String key) {
        return messages.getString(key, "<red>Missing message: " + key);
    }

    /**
     * Reads configurable lore as a YAML list. A scalar string is also accepted
     * so an older messages.yml does not break when the plugin is updated.
     */
    public List<String> lore(String key, Map<String, String> replacements) {
        return configuredLines(messages.get(key)).stream()
                .map(line -> replacePlaceholders(line, replacements))
                .toList();
    }

    public List<String> lore(String key) {
        return lore(key, Map.of());
    }

    static List<String> configuredLines(Object configured) {
        if (configured instanceof String line) {
            return line.isEmpty() ? List.of() : List.of(line);
        }
        if (!(configured instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String replacePlaceholders(String line, Map<String, String> replacements) {
        String rendered = line;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            rendered = rendered.replace("%" + replacement.getKey() + "%", replacement.getValue());
        }
        return rendered;
    }

    public ActionSettings action(String key) {
        return new ActionSettings(
                messages.getBoolean(key + ".enabled", true),
                List.copyOf(messages.getStringList(key + ".actions"))
        );
    }

    public HarvestSummarySettings harvestSummary() {
        return harvestSummary;
    }

    public record LoadedConfiguration(
            PluginSettings settings,
            Map<String, CropDefinition> crops,
            Map<String, CropDefinition> configuredCrops,
            Set<String> enabledCropIds,
            Map<Material, CropDefinition> cropsByItem,
            HarvestSummarySettings harvestSummary,
            YamlConfiguration messages
    ) {
    }

    public record ActionSettings(boolean enabled, List<String> actions) {
    }

    private record CropMaps(Map<String, CropDefinition> enabledCrops,
                            Map<String, CropDefinition> configuredCrops,
                            Set<String> enabledIds,
                            Map<Material, CropDefinition> byItem) {
    }
}
