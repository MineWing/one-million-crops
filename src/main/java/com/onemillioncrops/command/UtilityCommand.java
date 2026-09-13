package com.onemillioncrops.command;

import com.onemillioncrops.util.Tasks;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Operator shortcuts, with every player operation dispatched to its owning region. */
public final class UtilityCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;

    public UtilityCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = name.equals("tp") ? "onemillion.teleport" : "onemillion.gamemode";
        if (!sender.hasPermission(permission)) {
            send(sender, "<red>You do not have permission to use this command.</red>");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>This command must be used by a player.</red>");
            return true;
        }
        GameMode mode = switch (name) {
            case "gms" -> GameMode.SURVIVAL;
            case "gmc" -> GameMode.CREATIVE;
            case "gmsp" -> GameMode.SPECTATOR;
            default -> null;
        };
        if (mode != null) {
            if (args.length != 0) {
                send(player, "<yellow>Usage: /" + name + "</yellow>");
                return true;
            }
            Tasks.player(plugin, player, () -> {
                player.setGameMode(mode);
                if (player.getGameMode() == mode) {
                    send(player, "<green>Game mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".</green>");
                } else {
                    send(player, "<red>The game mode change was cancelled.</red>");
                }
            });
            return true;
        }
        boolean here = args.length == 2 && args[0].equalsIgnoreCase("here");
        if (!here && args.length != 1) {
            send(player, "<yellow>Usage: /tp player or /tp here player</yellow>");
            return true;
        }
        String targetName = args[here ? 1 : 0];
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) {
            send(player, "<red>That player is not online.</red>");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "<yellow>You are already at your own location.</yellow>");
            return true;
        }
        Player traveler = here ? target : player;
        Player destination = here ? player : target;
        String success = here
                ? "<green>Brought " + Text.escape(target.getName()) + " to you.</green>"
                : "<green>Teleported to " + Text.escape(target.getName()) + ".</green>";
        // Capture the destination on its owner, then initiate the teleport on the traveler's owner.
        onPlayer(destination, player, () -> {
            Location location = destination.getLocation().clone();
            onPlayer(traveler, player, () -> teleport(traveler, location, player, success));
        });
        return true;
    }

    private void teleport(Player traveler, Location location, Player requester, String success) {
        try {
            traveler.teleportAsync(location, TeleportCause.COMMAND).whenComplete((moved, error) ->
                    send(requester, error == null && Boolean.TRUE.equals(moved)
                            ? success : "<red>Teleport failed or was cancelled.</red>"));
        } catch (RuntimeException exception) {
            send(requester, "<red>Teleport failed or was cancelled.</red>");
        }
    }

    private void onPlayer(Player player, Player requester, Runnable action) {
        Runnable checked = () -> {
            if (player.isOnline()) {
                action.run();
            } else {
                send(requester, "<red>A player disconnected before the teleport.</red>");
            }
        };
        if (plugin.getServer().isOwnedByCurrentRegion(player)) {
            checked.run();
        } else {
            if (!player.getScheduler().execute(plugin, checked,
                    () -> send(requester, "<red>A player disconnected before the teleport.</red>"), 1L)) {
                send(requester, "<red>A player disconnected before the teleport.</red>");
            }
        }
    }

    private void send(CommandSender sender, String message) {
        var component = MiniMessage.miniMessage().deserialize(message);
        if (sender instanceof Player player) {
            Tasks.player(plugin, player, () -> player.sendMessage(component));
        } else {
            sender.sendMessage(component);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("tp") || !sender.hasPermission("onemillion.teleport")
                || !(sender instanceof Player viewer)
                || !(args.length == 1 || args.length == 2 && args[0].equalsIgnoreCase("here"))) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        if (args.length == 1) candidates.add("here");
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.getUniqueId().equals(viewer.getUniqueId()) && viewer.canSee(player)) {
                candidates.add(player.getName());
            }
        }
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
