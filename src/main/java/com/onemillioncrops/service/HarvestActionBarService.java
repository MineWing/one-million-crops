package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Numbers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Batches rapid crop pickups into a single action-bar update per player. */
public final class HarvestActionBarService {
    private static final long QUIET_PERIOD_TICKS = 20L;
    private final OneMillionCropsPlugin plugin;
    private final Map<UUID, PendingHarvest> pending = new HashMap<>();

    public HarvestActionBarService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, CropDefinition crop, long amount) {
        if (amount <= 0 || !player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PendingHarvest harvest = pending.computeIfAbsent(playerId, ignored -> new PendingHarvest());
        harvest.batch().add(crop, amount);
        harvest.cancel();
        harvest.task(Bukkit.getScheduler().runTaskLater(plugin, () -> flush(playerId), QUIET_PERIOD_TICKS));
    }

    public void remove(Player player) {
        PendingHarvest harvest = pending.remove(player.getUniqueId());
        if (harvest != null) {
            harvest.cancel();
        }
    }

    public void stop() {
        pending.values().forEach(PendingHarvest::cancel);
        pending.clear();
    }

    private void flush(UUID playerId) {
        PendingHarvest harvest = pending.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (harvest == null || player == null || !player.isOnline()) {
            return;
        }

        String entries = harvest.batch().entries().stream()
                .map(entry -> "<#8CE99A><bold>HARVEST</bold></#8CE99A> <white><bold>" + entry.amount()
                        + "</bold></white> " + entry.crop().displayMiniMessage())
                .collect(Collectors.joining(" <dark_gray>•</dark_gray> "));
        plugin.actions().execute("harvest-action-bar", List.of(player), List.of(player), List.of(),
                Map.of("entries", entries));
    }

    static final class HarvestBatch {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        void add(CropDefinition crop, long amount) {
            entries.compute(crop.id(), (ignored, current) -> new Entry(
                    crop,
                    current == null ? amount : Numbers.saturatingAdd(current.amount(), amount)
            ));
        }

        List<Entry> entries() {
            return List.copyOf(entries.values());
        }

        record Entry(CropDefinition crop, long amount) {
        }
    }

    private static final class PendingHarvest {
        private final HarvestBatch batch = new HarvestBatch();
        private BukkitTask task;

        HarvestBatch batch() {
            return batch;
        }

        void task(BukkitTask task) {
            this.task = task;
        }

        void cancel() {
            if (task != null) {
                task.cancel();
            }
        }
    }
}
