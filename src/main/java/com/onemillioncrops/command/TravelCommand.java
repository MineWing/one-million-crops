package com.onemillioncrops.command;

import com.onemillioncrops.data.TravelStore;
import com.onemillioncrops.service.TravelEffects;
import com.onemillioncrops.service.TravelService;
import com.onemillioncrops.util.Tasks;
import com.onemillioncrops.util.Text;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Keeps location writes and reads ordered without doing database I/O on region threads. */
public final class TravelCommand implements CommandExecutor, TabCompleter, AutoCloseable {
    private final Plugin plugin;
    private final TravelStore store;
    private final TravelService travel;
    private final TravelEffects effects;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OneMillionCrops-locations");
        thread.setDaemon(true);
        return thread;
    });

    public TravelCommand(Plugin plugin, TravelStore store) {
        this.plugin = plugin;
        this.store = store;
        travel = new TravelService(plugin);
        effects = new TravelEffects(plugin);
    }

    static String permission(String command) {
        return switch (command) {
            case "setspawn" -> "onemillion.setspawn";
            case "spawn" -> "onemillion.spawn";
            case "setwarp", "delwarp" -> "onemillion.warp.admin";
            case "warp" -> "onemillion.warp";
            default -> "onemillion.home";
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String verb = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(permission(verb))) {
            effects.message(sender, "<red>You do not have permission to use this command.</red>", false);
            return true;
        }
        if (!(sender instanceof Player player)) {
            effects.message(sender, "<red>Use this command in game.</red>", false);
            return true;
        }
        boolean spawn = verb.endsWith("spawn");
        boolean home = verb.endsWith("home");
        String scope = spawn ? "spawn" : home ? player.getUniqueId().toString() : "warps";
        if ((spawn && args.length != 0) || (!spawn && args.length > 1)
                || ((verb.equals("setwarp") || verb.equals("delwarp")) && args.length != 1)) {
            effects.message(player, "<yellow>Usage: /" + verb + (spawn ? "" : home ? " [name]" : " name") + "</yellow>", false);
            return true;
        }
        if (verb.equals("warp") && args.length == 0) {
            List<String> names = store.names(scope);
            effects.message(player, names.isEmpty() ? "<yellow>No warps have been set yet.</yellow>"
                    : "<#FFC2DE>Warps</#FFC2DE> <dark_gray>•</dark_gray> <white>" + String.join(", ", names) + "</white>", !names.isEmpty());
            return true;
        }
        final String name;
        try {
            name = spawn ? "spawn" : TravelStore.normalize(args.length == 0 ? "home" : args[0]);
        } catch (IllegalArgumentException exception) {
            effects.message(player, "<yellow>Use 1 to 32 letters, numbers, underscores or hyphens.</yellow>", false);
            return true;
        }
        String display = spawn ? "Spawn" : (home ? "Home " : "Warp ") + Text.escape(name);
        if (verb.startsWith("set")) {
            Tasks.player(plugin, player, () -> {
                Location location = player.getLocation();
                TravelStore.Point point = new TravelStore.Point(location.getWorld().getUID(), location.getX(),
                        location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                submit(player, () -> {
                    store.set(scope, name, point);
                    effects.message(player, "<#FF8FBD>" + display + " saved.</#FF8FBD> <gray>Your next journey starts here.</gray>", true);
                });
            });
        } else if (verb.startsWith("del")) {
            submit(player, () -> {
                boolean removed = store.delete(scope, name);
                effects.message(player, removed ? "<#FFC2DE>" + display + " removed.</#FFC2DE>"
                        : "<yellow>" + display + " has not been set.</yellow>", removed);
            });
        } else {
            submit(player, () -> {
                TravelStore.Point point = store.get(scope, name);
                if (point == null) {
                    effects.message(player, "<yellow>" + display + " has not been set.</yellow>", false);
                    return;
                }
                Tasks.player(plugin, player, () -> {
                    var world = plugin.getServer().getWorld(point.world());
                    if (world == null) {
                        effects.message(player, "<red>That destination's world is not loaded.</red>", false);
                        return;
                    }
                    travel.toLocation(player, new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()),
                            player, "<#FF8FBD>Welcome to </#FF8FBD><#FFC2DE>" + display + "</#FFC2DE><#FF8FBD>!</#FF8FBD>");
                });
            });
        }
        return true;
    }

    private void submit(Player player, Work work) {
        if (io.isShutdown()) return;
        io.execute(() -> {
            try {
                work.run();
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Could not access saved travel locations", exception);
                effects.message(player, "<red>Could not save or load that location. Please try again.</red>", false);
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String verb = command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player) || !sender.hasPermission(permission(verb)) || args.length != 1
                || verb.endsWith("spawn")) return List.of();
        String scope = verb.endsWith("home") ? player.getUniqueId().toString() : "warps";
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return store.names(scope).stream().filter(name -> name.startsWith(prefix)).toList();
    }

    @Override
    public void close() throws Exception {
        // Drain accepted writes before closing SQLite, so shutdown cannot discard a saved home.
        io.close();
        store.close();
    }

    @FunctionalInterface
    private interface Work { void run() throws Exception; }
}
