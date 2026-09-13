package com.onemillioncrops.service;

import com.onemillioncrops.util.Tasks;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Matching presentation for utility commands; effects always follow the player. */
public final class TravelEffects {
    private static final String PREFIX = "<gradient:#FF8FBD:#FFC2DE><bold>✦ CROPS</bold></gradient> <dark_gray>›</dark_gray> ";
    private final Plugin plugin;

    public TravelEffects(Plugin plugin) { this.plugin = plugin; }

    public void message(CommandSender sender, String message, boolean success) {
        var component = MiniMessage.miniMessage().deserialize(PREFIX + message);
        if (sender instanceof Player player) {
            Tasks.player(plugin, player, () -> {
                player.sendMessage(component);
                player.playSound(player.getLocation(), success ? "minecraft:block.amethyst_block.chime"
                        : "minecraft:block.note_block.bass", 0.55f, success ? 1.4f : 0.8f);
                player.spawnParticle(success ? Particle.HAPPY_VILLAGER : Particle.SMOKE,
                        player.getLocation().add(0, 1, 0), success ? 16 : 4, 0.45, 0.5, 0.45, 0.02);
            });
        } else {
            sender.sendMessage(component);
        }
    }

    public void arrival(Player player) {
        Tasks.player(plugin, player, () -> {
            var center = player.getLocation().add(0, 0.2, 0);
            for (int step = 0; step < 24; step++) {
                double angle = step * Math.PI / 12;
                player.spawnParticle(Particle.DUST, center.clone().add(Math.cos(angle), 0.1, Math.sin(angle)),
                        1, 0, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 143, 189), 1.2f));
                player.spawnParticle(Particle.END_ROD, center.clone().add(Math.cos(angle), 0.2, Math.sin(angle)),
                        1, 0, 0.15, 0, 0.01);
            }
            player.spawnParticle(Particle.PORTAL, center.clone().add(0, 0.8, 0), 40, 0.5, 0.7, 0.5, 0.2);
            player.playSound(center, "minecraft:entity.enderman.teleport", 0.55f, 1.25f);
        });
    }
}
