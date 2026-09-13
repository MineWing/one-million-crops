package com.onemillioncrops.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.TimeUnit;

/** Routes work to the owner of the data it accesses, on both Paper and Folia. */
public final class Tasks {
    private Tasks() { }

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static ScheduledTask global(Plugin plugin, Runnable action) {
        return plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> action.run());
    }

    public static ScheduledTask globalLater(Plugin plugin, Runnable action, long delay) {
        return plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> action.run(), Math.max(1L, delay));
    }

    public static ScheduledTask globalTimer(Plugin plugin, Runnable action, long delay, long period) {
        return plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> action.run(),
                Math.max(1L, delay), Math.max(1L, period));
    }

    public static ScheduledTask async(Plugin plugin, Runnable action) {
        return plugin.getServer().getAsyncScheduler().runNow(plugin, task -> action.run());
    }

    public static ScheduledTask asyncLater(Plugin plugin, Runnable action, long ticks) {
        return plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> action.run(),
                Math.max(1L, ticks) * 50L, TimeUnit.MILLISECONDS);
    }

    public static void player(Plugin plugin, Player player, Runnable action) {
        if (!plugin.isEnabled()) return;
        if (plugin.getServer().isOwnedByCurrentRegion(player)) {
            if (player.isOnline()) action.run();
        } else {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) action.run();
            }, null);
        }
    }

    public static ScheduledTask playerLater(Plugin plugin, Player player, Runnable action, long delay) {
        return player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) action.run();
        }, null, Math.max(1L, delay));
    }

    public static ScheduledTask regionLater(Plugin plugin, Location location, Runnable action, long delay) {
        return plugin.getServer().getRegionScheduler().runDelayed(plugin, location, task -> action.run(), Math.max(1L, delay));
    }
}
