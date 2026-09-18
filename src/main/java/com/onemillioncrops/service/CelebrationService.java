package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class CelebrationService {
    private final OneMillionCropsPlugin plugin;

    public CelebrationService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void milestone(CropDefinition crop, int percent) {
        List<Player> players = new java.util.ArrayList<>();
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> plugin.configManager().settings().mayContribute(player.getUniqueId()))
                .forEach(players::add);
        plugin.actions().execute("milestone", players, players, List.of(), Map.of(
                "crop", crop.displayMiniMessage(),
                "percent", Integer.toString(percent),
                "pitch", Float.toString(Math.min(2.0f, 0.8f + percent / 100.0f))
        ));
    }

    public void completedPersisted(CropDefinition crop, boolean grandFinale) {
        List<Player> players = new java.util.ArrayList<>();
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> plugin.configManager().settings().mayContribute(player.getUniqueId()))
                .forEach(players::add);
        plugin.actions().execute("completed-broadcast", players, players, List.of(), Map.of(
                "crop", crop.displayMiniMessage(),
                "target", Long.toString(plugin.progress().target())
        ));
        if (grandFinale) {
            plugin.actions().execute("grand-finale", players, players, List.of(), Map.of());
        }
        for (Player player : players) {
            play(player, crop, grandFinale);
            clearPendingAsync(player.getUniqueId(), crop.id());
        }
    }

    public void playPending(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                var pending = plugin.database().pendingCelebrations(playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    for (var celebration : pending) {
                        CropDefinition crop = plugin.configManager().crop(celebration.cropId());
                        if (crop != null) {
                            play(player, crop, celebration.grandFinale());
                        }
                        clearPendingAsync(playerId, celebration.cropId());
                    }
                });
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load pending celebrations", exception);
            }
        }, 30L);
    }

    private void play(Player player, CropDefinition crop, boolean grandFinale) {
        var settings = plugin.configManager().settings();
        plugin.actions().execute(grandFinale ? "finale-celebration" : "crop-celebration",
                List.of(player), List.of(player), List.of(), Map.of(
                        "crop", crop.displayMiniMessage(),
                        "sound", settings.completionSound(),
                        "volume", Float.toString(settings.completionVolume()),
                        "pitch", Float.toString(settings.completionPitch()),
                        "fireworks", Integer.toString(settings.celebrationFireworks()),
                        "firework-gap", Integer.toString(settings.fireworkGapTicks())
                ));
    }

    private void clearPendingAsync(UUID player, String cropId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.database().clearPending(player, cropId);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not clear a pending celebration", exception);
            }
        });
    }
}
