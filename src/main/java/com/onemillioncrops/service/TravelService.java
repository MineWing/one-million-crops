package com.onemillioncrops.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

/** Captures destinations and initiates asynchronous teleports on the correct entity owners. */
public final class TravelService {
    private final Plugin plugin;
    private final TravelEffects effects;

    public TravelService(Plugin plugin) {
        this.plugin = plugin;
        this.effects = new TravelEffects(plugin);
    }

    public void toPlayer(Player traveler, Player destination, Player requester, String success) {
        onPlayer(destination, requester, () -> toLocation(traveler, destination.getLocation().clone(), requester, success));
    }

    public void toLocation(Player traveler, Location location, Player requester, String success) {
        onPlayer(traveler, requester, () -> {
            try {
                traveler.teleportAsync(location, TeleportCause.COMMAND).whenComplete((moved, error) -> {
                    if (error == null && Boolean.TRUE.equals(moved)) {
                        effects.message(requester, success, true);
                        effects.arrival(traveler);
                        if (!traveler.getUniqueId().equals(requester.getUniqueId())) {
                            effects.message(traveler, "<#8CE99A>You have been brought to another player.</#8CE99A>", true);
                        }
                    } else {
                        effects.message(requester, "<red>Teleport failed or was cancelled.</red>", false);
                    }
                });
            } catch (RuntimeException exception) {
                effects.message(requester, "<red>Teleport failed or was cancelled.</red>", false);
            }
        });
    }

    private void onPlayer(Player player, Player requester, Runnable action) {
        Runnable disconnected = () -> effects.message(requester,
                "<red>A player disconnected before the teleport.</red>", false);
        Runnable checked = () -> {
            if (player.isOnline()) action.run();
            else disconnected.run();
        };
        if (!plugin.isEnabled()) return;
        if (plugin.getServer().isOwnedByCurrentRegion(player)) {
            checked.run();
        } else if (!player.getScheduler().execute(plugin, checked, disconnected, 1L)) {
            disconnected.run();
        }
    }
}
