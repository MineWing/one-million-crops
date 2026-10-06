package com.onemillioncrops.command;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProgressCommand implements CommandExecutor, TabCompleter {
    private final OneMillionCropsPlugin plugin;

    public ProgressCommand(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.sendActions("player-only", sender, Map.of());
            return true;
        }
        if (!sender.hasPermission("onemillion.progress")) {
            plugin.sendActions("no-permission", sender, Map.of());
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player, 0);
            return true;
        }
        CropDefinition crop = plugin.configManager().crop(args[0]);
        if (crop == null) {
            plugin.sendActions("unknown-crop", sender, Map.of("crop", Text.escape(args[0])));
            return true;
        }
        plugin.gui().openCrop(player, crop.id());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return plugin.configManager().crops().keySet().stream().filter(id -> id.startsWith(prefix)).toList();
    }
}
