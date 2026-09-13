package com.onemillioncrops.service;

import com.onemillioncrops.util.Tasks;
import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.sql.SQLException;
import java.util.logging.Level;

/** Announces how much each currently online player harvested during the configured time window. */
public final class HarvestSummaryService {
    private final OneMillionCropsPlugin plugin;
    private final SummaryActionService actions;
    private final Map<UUID, Long> harvested = new HashMap<>();
    private final Map<UUID, Long> personalBests = new HashMap<>();
    private final Map<UUID, BossBar> countdownBars = new HashMap<>();
    private ScheduledTask task;
    private ScheduledTask countdownTask;
    private CountdownStyle countdownStyle;
    private String countdownTitle;
    private long intervalMillis;
    private long nextRunAtMillis;

    public HarvestSummaryService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.actions = new SummaryActionService(plugin);
    }

    public synchronized void start() {
        stopTask();
        try {
            Map<UUID, Long> loadedPersonalBests = plugin.database().harvestPersonalBests();
            personalBests.clear();
            personalBests.putAll(loadedPersonalBests);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load harvest-summary personal bests", exception);
        }
        if (plugin.configManager().harvestSummary().enabled()) {
            schedule();
        } else {
            harvested.clear();
        }
    }

    public synchronized void record(Player player, long amount) {
        if (amount <= 0 || !plugin.configManager().harvestSummary().enabled()) {
            return;
        }
        harvested.merge(player.getUniqueId(), amount, HarvestSummaryService::saturatingAdd);
    }

    public synchronized void stop() {
        stopTask();
        harvested.clear();
    }

    public synchronized void resetPersonalBests() {
        personalBests.clear();
    }

    public void showCountdown(Player player) {
        Tasks.player(plugin, player, () -> updatePlayerCountdown(player));
    }

    private synchronized void updatePlayerCountdown(Player player) {
        if (countdownStyle == null || intervalMillis <= 0L) return;
        long remaining = Math.max(0L, nextRunAtMillis - System.currentTimeMillis());
        var title = plugin.text().parse(Text.replace(countdownTitle,
                Map.of("time", countdownTime(remaining))));
        float progress = countdownProgress(remaining, intervalMillis);
        BossBar bar = countdownBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, countdownStyle.color(), countdownStyle.overlay());
            countdownBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    public synchronized void remove(Player player) {
        BossBar bar = countdownBars.remove(player.getUniqueId());
        if (bar != null) {
            Tasks.player(plugin, player, () -> player.hideBossBar(bar));
        }
    }

    public synchronized SummaryStatus status() {
        long total = 0L;
        for (long amount : harvested.values()) {
            total = saturatingAdd(total, amount);
        }
        boolean scheduled = task != null && !task.isCancelled();
        long remainingMillis = scheduled ? Math.max(0L, nextRunAtMillis - System.currentTimeMillis()) : 0L;
        return new SummaryStatus(scheduled, remainingMillis, harvested.size(), total);
    }

    public synchronized boolean announceNow() {
        if (!plugin.configManager().harvestSummary().enabled()) {
            return false;
        }
        stopTask();
        announce();
        schedule();
        return true;
    }

    private synchronized void announce() {
        int intervalMinutes = plugin.configManager().harvestSummary().intervalMinutes();
        nextRunAtMillis = System.currentTimeMillis() + intervalMillis(intervalMinutes);
        Map<UUID, Long> completedWindow = new HashMap<>(harvested);
        harvested.clear();

        List<PlayerTotal> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(new PlayerTotal(
                    player,
                    completedWindow.getOrDefault(player.getUniqueId(), 0L)
            ));
        }
        if (players.isEmpty()) {
            return;
        }
        players.sort(Comparator.comparingLong(PlayerTotal::amount).reversed()
                .thenComparing(summary -> summary.player().getName(), String.CASE_INSENSITIVE_ORDER));

        long total = 0L;
        List<SummaryActionService.SummaryEntry> entries = new ArrayList<>();
        Map<UUID, Long> improvedPersonalBests = new HashMap<>();
        for (PlayerTotal summary : players) {
            total = saturatingAdd(total, summary.amount());
            UUID playerId = summary.player().getUniqueId();
            boolean personalBest = recordPersonalBest(personalBests, playerId, summary.amount());
            if (personalBest) {
                improvedPersonalBests.put(playerId, summary.amount());
            }
            entries.add(new SummaryActionService.SummaryEntry(
                    summary.player().getName(), summary.amount(), personalBest));
        }
        try {
            plugin.database().saveHarvestPersonalBests(improvedPersonalBests);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save harvest-summary personal bests", exception);
        }
        actions.execute(plugin.configManager().harvestSummary().actions(),
                players.stream().map(PlayerTotal::player).toList(), entries, total, intervalMinutes);
    }

    private synchronized void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
        countdownBars.clear();
        countdownStyle = null;
        countdownTitle = null;
        intervalMillis = 0L;
        nextRunAtMillis = 0L;
    }

    private synchronized void schedule() {
        int minutes = plugin.configManager().harvestSummary().intervalMinutes();
        long intervalTicks = intervalTicks(minutes);
        intervalMillis = intervalMillis(minutes);
        nextRunAtMillis = System.currentTimeMillis() + intervalMillis;
        task = Tasks.globalTimer(plugin, this::announce, intervalTicks, intervalTicks);
        var configuredCountdown = plugin.configManager().action("harvest-summary-countdown");
        CountdownStyle style = countdownStyle(configuredCountdown.actions());
        if (configuredCountdown.enabled() && style == null) {
            plugin.getLogger().warning("harvest-summary-countdown needs a valid [countdown] action");
        } else if (configuredCountdown.enabled()) {
            countdownTitle = style.title();
            countdownStyle = style;
            countdownTask = Tasks.globalTimer(plugin, this::updateCountdown, 0L, 20L);
        }
    }

    private synchronized void updateCountdown() {
        if (countdownStyle == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            showCountdown(player);
        }
    }

    static long intervalTicks(int minutes) {
        return minutes * 60L * 20L;
    }

    static long intervalMillis(int minutes) {
        return minutes * 60L * 1_000L;
    }

    static float countdownProgress(long remainingMillis, long intervalMillis) {
        if (intervalMillis <= 0L) {
            return 0.0f;
        }
        return (float) Math.clamp(remainingMillis / (double) intervalMillis, 0.0, 1.0);
    }

    static String countdownTime(long remainingMillis) {
        long seconds = Math.max(0L, (remainingMillis + 999L) / 1_000L);
        long minutes = seconds / 60L;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60L);
    }

    static CountdownStyle countdownStyle(List<String> actions) {
        for (String configured : actions) {
            SummaryActionService.ParsedAction action = SummaryActionService.parse(configured);
            if (action == null || !action.tag().equals("countdown")) {
                continue;
            }
            String[] fields = action.payload().split("\\s*\\|\\s*", -1);
            if (fields.length == 0 || fields[0].isBlank()) {
                return null;
            }
            try {
                BossBar.Color color = fields.length >= 2
                        ? BossBar.Color.valueOf(fields[1].strip().toUpperCase(Locale.ROOT))
                        : BossBar.Color.GREEN;
                BossBar.Overlay overlay = fields.length >= 3
                        ? BossBar.Overlay.valueOf(fields[2].strip().toUpperCase(Locale.ROOT))
                        : BossBar.Overlay.PROGRESS;
                return new CountdownStyle(fields[0], color, overlay);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    static boolean recordPersonalBest(Map<UUID, Long> personalBests, UUID player, long amount) {
        if (amount <= 0L || amount <= personalBests.getOrDefault(player, 0L)) {
            return false;
        }
        personalBests.put(player, amount);
        return true;
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record PlayerTotal(Player player, long amount) {
    }

    record CountdownStyle(String title, BossBar.Color color, BossBar.Overlay overlay) {
    }

    public record SummaryStatus(boolean scheduled, long remainingMillis, int contributors, long harvested) {
    }
}
