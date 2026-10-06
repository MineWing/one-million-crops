package com.onemillioncrops.command;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.service.HarvestSummaryService;
import com.onemillioncrops.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainCommand implements CommandExecutor, TabCompleter {
    private final OneMillionCropsPlugin plugin;

    public MainCommand(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("status")) {
            if (permitted(sender, "onemillion.progress")) {
                status(sender);
            }
            return true;
        }
        if (subcommand.equals("scoreboard")) {
            if (permitted(sender, "onemillion.progress") && requirePlayer(sender) instanceof Player player) {
                boolean enabled = plugin.scoreboards().toggle(player);
                plugin.sendActions(enabled ? "scoreboard-on" : "scoreboard-off", sender, Map.of());
            }
            return true;
        }
        if (subcommand.equals("wand")) {
            if (permitted(sender, "onemillion.wand") && requirePlayer(sender) instanceof Player player) {
                plugin.cropWand().giveWand(player);
            }
            return true;
        }
        if (subcommand.equals("plantwand")) {
            if (permitted(sender, "onemillion.plantwand") && requirePlayer(sender) instanceof Player player) {
                plugin.plantWand().giveWand(player);
            }
            return true;
        }
        if (!permitted(sender, "onemillion.admin")) {
            return true;
        }
        switch (subcommand) {
            case "crops" -> {
                if (requirePlayer(sender) instanceof Player player) {
                    plugin.gui().openCropToggles(player, 0);
                }
            }
            case "reload" -> plugin.reloadPlugin(sender);
            case "automode" -> plugin.toggleAutoHarvest(sender);
            case "backup" -> plugin.backup(sender);
            case "reset" -> reset(sender, args);
            case "summary" -> summary(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.sendActions("no-permission", sender, Map.of());
        return false;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.sendActions("player-only", sender, Map.of());
        return null;
    }

    private void summary(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("now")) {
            boolean announced = plugin.harvestSummary().announceNow();
            plugin.sendActions(announced ? "harvest-summary-forced" : "harvest-summary-not-scheduled", sender,
                    Map.of("minutes", Integer.toString(plugin.configManager().harvestSummary().intervalMinutes())));
            return;
        }
        HarvestSummaryService.SummaryStatus status = plugin.harvestSummary().status();
        if (!status.scheduled()) {
            plugin.sendActions("harvest-summary-not-scheduled", sender, Map.of());
            return;
        }
        plugin.sendActions("harvest-summary-status", sender, Map.of(
                "time", formatDuration(status.remainingMillis()),
                "amount", Text.number(status.harvested()),
                "players", Integer.toString(status.contributors())
        ));
    }

    static String formatDuration(long remainingMillis) {
        long seconds = Math.max(0L, (remainingMillis + 999L) / 1_000L);
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return minutes + "m " + remainder + "s";
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            plugin.resetAll(sender);
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("confirm")) {
            CropDefinition crop = plugin.configManager().crop(args[1]);
            if (crop == null) {
                plugin.sendActions("unknown-crop", sender, Map.of("crop", Text.escape(args[1])));
            } else {
                plugin.resetCrop(sender, crop);
            }
            return;
        }
        plugin.sendActions("reset-warning", sender, Map.of());
    }

    private void status(CommandSender sender) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (CropDefinition crop : plugin.progress().crops().values()) {
            long amount = plugin.progress().amount(crop.id());
            rows.add(Map.of(
                    "crop", crop.displayMiniMessage(),
                    "amount", Text.number(amount),
                    "target", Text.number(plugin.progress().target()),
                    "percent", Text.percent(amount, plugin.progress().target())
            ));
        }
        plugin.actions().execute("status", List.of(sender),
                sender instanceof Player player ? List.of(player) : List.of(), rows, Map.of(
                "completed", Integer.toString(plugin.progress().completedCount()),
                "total", Integer.toString(plugin.progress().crops().size())
        ));
    }

    private void help(CommandSender sender) {
        plugin.sendActions("help", sender, Map.of());
        if (sender.hasPermission("onemillion.admin")) {
            plugin.sendActions("admin-help", sender, Map.of());
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "status", "scoreboard"));
            if (sender.hasPermission("onemillion.wand")) {
                options.add("wand");
            }
            if (sender.hasPermission("onemillion.plantwand")) {
                options.add("plantwand");
            }
            if (sender.hasPermission("onemillion.admin")) {
                options.addAll(List.of("crops", "reload", "automode", "backup", "reset", "summary"));
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("onemillion.admin")) {
                return List.of();
            }
            List<String> options = new ArrayList<>(plugin.configManager().crops().keySet());
            options.add("confirm");
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")
                && plugin.configManager().crop(args[1]) != null) {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("summary")
                && sender.hasPermission("onemillion.admin")) {
            return "now".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("now") : List.of();
        }
        return List.of();
    }
}
