package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        if (harvest.task() != null) {
            harvest.task().cancel();
        }
        harvest.task(Bukkit.getScheduler().runTaskLater(plugin, () -> flush(playerId), QUIET_PERIOD_TICKS));
    }

    public void remove(Player player) {
        PendingHarvest harvest = pending.remove(player.getUniqueId());
        if (harvest != null && harvest.task() != null) {
            harvest.task().cancel();
        }
    }

    public void stop() {
        for (PendingHarvest harvest : pending.values()) {
            if (harvest.task() != null) {
                harvest.task().cancel();
            }
        }
        pending.clear();
    }

    private void flush(UUID playerId) {
        PendingHarvest harvest = pending.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (harvest == null || player == null || !player.isOnline()) {
            return;
        }

        StringBuilder entries = new StringBuilder();
        boolean first = true;
        for (HarvestBatch.Entry entry : harvest.batch().entries()) {
            if (!first) {
                entries.append(" <dark_gray>•</dark_gray> ");
            }
            entries.append("<#8CE99A><bold>HARVEST</bold></#8CE99A> <white><bold>")
                    .append(entry.amount()).append("</bold></white> ")
                    .append(entry.crop().displayMiniMessage());
            first = false;
        }
        plugin.actions().execute("harvest-action-bar", List.of(player), List.of(player), List.of(),
                Map.of("entries", entries.toString()));
    }

    static final class HarvestBatch {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        void add(CropDefinition crop, long amount) {
            entries.compute(crop.id(), (ignored, current) -> new Entry(
                    crop,
                    current == null ? amount : saturatingAdd(current.amount(), amount)
            ));
        }

        List<Entry> entries() {
            return List.copyOf(entries.values());
        }

        private static long saturatingAdd(long left, long right) {
            return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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

        BukkitTask task() {
            return task;
        }

        void task(BukkitTask task) {
            this.task = task;
        }
    }
}
