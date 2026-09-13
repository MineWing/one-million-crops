package com.onemillioncrops.command;

import com.onemillioncrops.util.Tasks;
import com.onemillioncrops.util.Text;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Operator shortcuts, with every player operation dispatched to its owning region. */
public final class UtilityCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final com.onemillioncrops.service.TravelService travel;
    private final com.onemillioncrops.service.TravelEffects effects;

    public UtilityCommand(Plugin plugin) {
        this.plugin = plugin;
        this.travel = new com.onemillioncrops.service.TravelService(plugin);
        this.effects = new com.onemillioncrops.service.TravelEffects(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = (name.equals("tp") || name.equals("tphere")) ? "onemillion.teleport" : "onemillion.gamemode";
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
                    send(player, "<#FF8FBD>Game mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".</#FF8FBD>");
                } else {
                    send(player, "<red>The game mode change was cancelled.</red>");
                }
            });
            return true;
        }
        boolean here = name.equals("tphere");
        if (args.length != 1) {
            send(player, "<yellow>Usage: /" + name + " player</yellow>");
            return true;
        }
        String targetName = args[0];
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
                ? "<#FF8FBD>Brought " + Text.escape(target.getName()) + " to you.</#FF8FBD>"
                : "<#FF8FBD>Teleported to " + Text.escape(target.getName()) + ".</#FF8FBD>";
        travel.toPlayer(traveler, destination, player, success);
        return true;
    }

    private void send(CommandSender sender, String message) {
        effects.message(sender, message, message.startsWith("<#FF8FBD>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(command.getName().equalsIgnoreCase("tp") || command.getName().equalsIgnoreCase("tphere")) || !sender.hasPermission("onemillion.teleport")
                || !(sender instanceof Player viewer)
                || args.length != 1) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.getUniqueId().equals(viewer.getUniqueId()) && viewer.canSee(player)) {
                candidates.add(player.getName());
            }
        }
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
