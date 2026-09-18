package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.util.SoundResolver;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the configurable actions used by the periodic harvest summary. */
public final class SummaryActionService {
    private static final Pattern ACTION = Pattern.compile("^\\s*\\[([a-zA-Z]+)](?:\\s?(.*))?$", Pattern.DOTALL);
    private static final String PERSONAL_BEST_AMOUNT =
            "<#FFD166><bold>%s</bold></#FFD166> "
                    + "<dark_gray>(</dark_gray><#8CE99A>NEW PB</#8CE99A><dark_gray>)</dark_gray>";
    private static final float DEFAULT_SOUND_VOLUME = 0.7f;
    private static final float DEFAULT_SOUND_PITCH = 1.2f;

    private final OneMillionCropsPlugin plugin;

    public SummaryActionService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(String event, List<? extends Audience> recipients, List<Player> players,
                        List<Map<String, String>> rows, Map<String, String> replacements) {
        var configured = plugin.configManager().action(event);
        if (!configured.enabled()) {
            return;
        }
        Map<String, String> common = new java.util.HashMap<>(replacements);
        common.putIfAbsent("prefix", plugin.configManager().message("prefix"));
        executeExpanded(configured.actions(), recipients, players, rows, Map.copyOf(common));
    }

    void execute(List<String> configuredActions, List<Player> recipients, List<SummaryEntry> entries,
                 long total, int intervalMinutes) {
        Map<String, String> common = Map.of(
                "total", Text.number(total),
                "minutes", Integer.toString(intervalMinutes),
                "prefix", plugin.configManager().message("prefix")
        );
        List<Map<String, String>> rows = entries.stream().map(SummaryActionService::row).toList();
        executeExpanded(configuredActions, recipients, recipients, rows, common);
    }

    private void executeExpanded(List<String> configuredActions, List<? extends Audience> recipients,
                                 List<Player> players, List<Map<String, String>> rows,
                                 Map<String, String> common) {
        for (String configuredAction : configuredActions) {
            ParsedAction action = parse(configuredAction);
            if (action == null) {
                plugin.getLogger().warning("Ignoring invalid harvestSummary action: " + configuredAction);
                continue;
            }
            List<String> payloads = expandRows(action.payload(), rows, common);
            for (String payload : payloads) {
                execute(action.tag(), payload, recipients, players);
            }
        }
    }

    private void execute(String tag, String payload, List<? extends Audience> recipients, List<Player> players) {
        try {
            switch (tag) {
                case "message" -> sendMessage(payload, recipients);
                case "broadcast" -> Bukkit.broadcast(plugin.text().parse(payload));
                case "sound" -> playSound(payload, players);
                case "bossbar" -> showBossBar(payload, players);
                case "title" -> showTitle(payload, players);
                case "actionbar" -> sendActionBar(payload, players);
                case "lightning" -> showLightning(players);
                case "particles" -> spawnParticles(payload, players);
                case "firework" -> spawnFireworks(payload, players);
                default -> plugin.getLogger().warning("Unknown harvestSummary action tag: [" + tag + "]");
            }
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid [" + tag + "] harvestSummary action: " + exception.getMessage());
        }
    }

    private void sendMessage(String payload, List<? extends Audience> recipients) {
        Component message = plugin.text().parse(payload);
        recipients.forEach(player -> player.sendMessage(message));
    }

    private void playSound(String payload, List<Player> recipients) {
        String[] arguments = words(payload);
        if (arguments.length == 0) {
            throw new IllegalArgumentException("a sound name is required");
        }
        Sound sound = SoundResolver.resolve(arguments[0]);
        if (sound == null) {
            throw new IllegalArgumentException("unknown sound " + arguments[0]);
        }
        float volume = arguments.length >= 2 ? positiveFloat(arguments[1], "volume") : DEFAULT_SOUND_VOLUME;
        float pitch = arguments.length >= 3 ? rangedFloat(arguments[2], "pitch", 0.0f, 2.0f) : DEFAULT_SOUND_PITCH;
        recipients.forEach(player -> player.playSound(player.getLocation(), sound, volume, pitch));
    }

    private void showBossBar(String payload, List<Player> recipients) {
        String[] arguments = fields(payload);
        String text = required(arguments, 0, "bossbar text");
        int seconds = arguments.length >= 2 ? positiveInt(arguments[1], "duration") : 5;
        BossBar.Color color = arguments.length >= 3
                ? enumValue(BossBar.Color.class, arguments[2], "bossbar color") : BossBar.Color.GREEN;
        BossBar.Overlay overlay = arguments.length >= 4
                ? enumValue(BossBar.Overlay.class, arguments[3], "bossbar overlay") : BossBar.Overlay.PROGRESS;
        float progress = arguments.length >= 5 ? rangedFloat(arguments[4], "progress", 0.0f, 1.0f) : 1.0f;
        BossBar bossBar = BossBar.bossBar(plugin.text().parse(text), progress, color, overlay);
        recipients.forEach(player -> player.showBossBar(bossBar));
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> recipients.forEach(player -> player.hideBossBar(bossBar)), seconds * 20L);
    }

    private void showTitle(String payload, List<Player> recipients) {
        String[] arguments = fields(payload);
        Component title = plugin.text().parse(required(arguments, 0, "title text"));
        Component subtitle = plugin.text().parse(arguments.length >= 2 ? arguments[1] : "");
        int fadeIn = arguments.length >= 3 ? nonNegativeInt(arguments[2], "fade-in ticks") : 10;
        int stay = arguments.length >= 4 ? nonNegativeInt(arguments[3], "stay ticks") : 60;
        int fadeOut = arguments.length >= 5 ? nonNegativeInt(arguments[4], "fade-out ticks") : 10;
        Title.Times times = Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut));
        Title rendered = Title.title(title, subtitle, times);
        recipients.forEach(player -> player.showTitle(rendered));
    }

    private void sendActionBar(String payload, List<Player> recipients) {
        Component message = plugin.text().parse(payload);
        recipients.forEach(player -> player.sendActionBar(message));
    }

    private static void showLightning(List<Player> recipients) {
        recipients.forEach(player -> player.getWorld().strikeLightningEffect(player.getLocation()));
    }

    private void spawnParticles(String payload, List<Player> recipients) {
        String[] arguments = words(payload);
        Particle particle = arguments.length >= 1
                ? enumValue(Particle.class, arguments[0], "particle") : Particle.HAPPY_VILLAGER;
        int amount = arguments.length >= 2 ? nonNegativeInt(arguments[1], "particle amount") : 20;
        double offsetX = arguments.length >= 3 ? finiteDouble(arguments[2], "x offset") : 0.6;
        double offsetY = arguments.length >= 4 ? finiteDouble(arguments[3], "y offset") : 0.8;
        double offsetZ = arguments.length >= 5 ? finiteDouble(arguments[4], "z offset") : 0.6;
        double speed = arguments.length >= 6 ? finiteDouble(arguments[5], "particle speed") : 0.05;
        recipients.forEach(player -> player.spawnParticle(particle,
                player.getLocation().add(0.0, 1.0, 0.0), amount, offsetX, offsetY, offsetZ, speed));
    }

    private void spawnFireworks(String payload, List<Player> recipients) {
        String[] arguments = fields(payload);
        List<Color> colors = arguments.length >= 1 && !arguments[0].isBlank()
                ? parseColors(arguments[0]) : List.of(Color.LIME, Color.YELLOW);
        FireworkEffect.Type type = arguments.length >= 2
                ? enumValue(FireworkEffect.Type.class, arguments[1], "firework type")
                : FireworkEffect.Type.BALL_LARGE;
        int power = arguments.length >= 3 ? Math.clamp(nonNegativeInt(arguments[2], "firework power"), 0, 2) : 1;
        int count = arguments.length >= 4 ? nonNegativeInt(arguments[3], "firework count") : 1;
        int gapTicks = arguments.length >= 5 ? nonNegativeInt(arguments[4], "firework gap ticks") : 10;
        for (int index = 0; index < count; index++) {
            long delay = (long) index * gapTicks;
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> recipients.stream().filter(Player::isOnline)
                            .forEach(player -> spawnFirework(player, colors, type, power)), delay);
        }
    }

    private static void spawnFirework(Player player, List<Color> colors, FireworkEffect.Type type, int power) {
        Firework firework = player.getWorld().spawn(player.getLocation().add(0.0, 1.0, 0.0), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder().with(type).withColor(colors).flicker(true).trail(true).build());
        meta.setPower(power);
        firework.setFireworkMeta(meta);
    }

    static ParsedAction parse(String configured) {
        if (configured == null) {
            return null;
        }
        Matcher matcher = ACTION.matcher(configured);
        if (!matcher.matches()) {
            return null;
        }
        String tag = matcher.group(1).toLowerCase(Locale.ROOT);
        String payload = matcher.group(2) == null ? "" : matcher.group(2);
        return new ParsedAction(tag, payload);
    }

    static List<String> expand(String payload, List<SummaryEntry> entries, Map<String, String> common) {
        List<Map<String, String>> rows = entries.stream().map(SummaryActionService::row).toList();
        return expandRows(payload, rows, common);
    }

    private static Map<String, String> row(SummaryEntry entry) {
        String amount = Text.number(entry.amount());
        String amountDisplay = entry.personalBest()
                ? PERSONAL_BEST_AMOUNT.formatted(amount)
                : "<#8CE99A><bold>" + amount + "</bold></#8CE99A>";
        return Map.of(
                "player", Text.escape(entry.player()),
                "amount", amount,
                "amount-display", amountDisplay
        );
    }

    static List<String> expandRows(String payload, List<Map<String, String>> rows, Map<String, String> common) {
        boolean hasRowPlaceholder = rows.stream().flatMap(row -> row.keySet().stream())
                .anyMatch(key -> payload.contains("%" + key + "%") || payload.contains("<" + key + ">"));
        if (!hasRowPlaceholder) {
            return List.of(Text.replace(payload, common));
        }
        List<String> expanded = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> replacements = new java.util.HashMap<>(common);
            replacements.putAll(row);
            expanded.add(Text.replace(payload, replacements));
        }
        return List.copyOf(expanded);
    }

    private static String[] words(String input) {
        String stripped = input.strip();
        return stripped.isEmpty() ? new String[0] : stripped.split("\\s+");
    }

    private static String[] fields(String input) {
        return input.split("\\s*\\|\\s*", -1);
    }

    private static String required(String[] arguments, int index, String name) {
        if (arguments.length <= index || arguments[index].isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return arguments[index];
    }

    private static int positiveInt(String input, String name) {
        int value = nonNegativeInt(input, name);
        if (value == 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static int nonNegativeInt(String input, String name) {
        try {
            int value = Integer.parseInt(input.strip());
            if (value < 0) {
                throw new IllegalArgumentException(name + " cannot be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static float positiveFloat(String input, String name) {
        float value = rangedFloat(input, name, 0.0f, Float.MAX_VALUE);
        if (value == 0.0f) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static float rangedFloat(String input, String name, float minimum, float maximum) {
        try {
            float value = Float.parseFloat(input.strip());
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static double finiteDouble(String input, String name) {
        try {
            double value = Double.parseDouble(input.strip());
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String input, String name) {
        try {
            return Enum.valueOf(type, input.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown " + name + " " + input);
        }
    }

    private static List<Color> parseColors(String input) {
        List<Color> colors = new ArrayList<>();
        for (String configured : input.split(",")) {
            String hex = configured.strip();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            if (!hex.matches("[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("firework colors must be six-digit hex values");
            }
            colors.add(Color.fromRGB(Integer.parseInt(hex, 16)));
        }
        return List.copyOf(colors);
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    record ParsedAction(String tag, String payload) {
    }

    record SummaryEntry(String player, long amount, boolean personalBest) {
        SummaryEntry(String player, long amount) {
            this(player, amount, false);
        }
    }
}
