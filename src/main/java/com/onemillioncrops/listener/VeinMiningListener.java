package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Rivet's connected-ore matching, using real player breaks on the owning region. */
public final class VeinMiningListener implements Listener {
    private static final int MAX_ORES = 64;
    private final OneMillionCropsPlugin plugin;
    private final ThreadLocal<Boolean> breaking = ThreadLocal.withInitial(() -> false);

    public VeinMiningListener(OneMillionCropsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block base = event.getBlock();
        if (breaking.get() || !plugin.configManager().utilityFeatures().veinMining()
                || player.getGameMode() != GameMode.SURVIVAL || player.isSneaking()
                || !player.hasPermission("onemillion.veinminer")
                || !isPickaxe(player.getInventory().getItemInMainHand().getType())
                || oreKey(base.getType()) == null
                || base.getDrops(player.getInventory().getItemInMainHand(), player).isEmpty()) return;
        Set<Block> vein = connectedVein(base, block -> plugin.getServer().isOwnedByCurrentRegion(
                block.getWorld(), block.getX() >> 4, block.getZ() >> 4));
        if (vein.size() < 2) return;
        event.setCancelled(true);
        breaking.set(true);
        try {
            mine(vein, player::breakBlock,
                    () -> isPickaxe(player.getInventory().getItemInMainHand().getType()));
        } finally {
            breaking.remove();
        }
    }

    static void mine(Set<Block> vein, Predicate<Block> breakBlock, java.util.function.BooleanSupplier hasPickaxe) {
        for (Block block : vein) {
            if (!hasPickaxe.getAsBoolean() || !breakBlock.test(block)) break;
        }
    }

    static Set<Block> connectedVein(Block base, Predicate<Block> owned) {
        if (!owned.test(base)) return Set.of();
        String key = oreKey(base.getType());
        if (key == null) return Set.of();
        Set<Block> visited = new HashSet<>();
        Set<Block> ores = new LinkedHashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(base);
        while (!queue.isEmpty()) {
            Block block = queue.removeFirst();
            if (!visited.add(block)) continue;
            // Abort before reading block state in another Folia region or an unloaded chunk.
            if (!owned.test(block)) return Set.of();
            if (!key.equals(oreKey(block.getType()))) continue;
            ores.add(block);
            if (ores.size() > MAX_ORES) return Set.of();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    int height = block.getY() + y;
                    if (height < block.getWorld().getMinHeight() || height >= block.getWorld().getMaxHeight()) continue;
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) queue.add(block.getRelative(x, y, z));
                    }
                }
            }
        }
        return ores;
    }

    static boolean isPickaxe(Material material) { return material.name().endsWith("_PICKAXE"); }

    static String oreKey(Material material) {
        String name = material.name();
        if (name.startsWith("DEEPSLATE_")) name = name.substring(10);
        return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS
                || material == Material.GLOWSTONE ? name : null;
    }
}
