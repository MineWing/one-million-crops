package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import org.bukkit.Axis;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tree detection adapted from Rivet's TreeFeller. */
public final class TimberListener implements Listener {
    private static final List<BlockFace> HORIZONTAL_TREE_FACES = List.of(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private static final List<BlockFace> VINE_ANCHOR_FACES = List.of(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN);
    private final OneMillionCropsPlugin plugin;
    private final ThreadLocal<Boolean> breaking = ThreadLocal.withInitial(() -> false);

    public TimberListener(OneMillionCropsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block base = event.getBlock();
        if (breaking.get() || !plugin.configManager().utilityFeatures().timber()
                || player.getGameMode() != GameMode.SURVIVAL || player.isSneaking()
                || !player.hasPermission("onemillion.timber")
                || !isAxe(player.getInventory().getItemInMainHand().getType())
                || !isTreeTrunk(base.getType())) return;
        Set<Block> tree;
        try {
            if (isTreeTrunk(relative(base, 0, -1, 0).getType())) return;
            boolean large = isLargeJungleTrunk(base);
            Set<Block> logs = connectedLogs(base, large ? 256 : 96);
            Set<Block> leaves = connectedLeaves(logs, large ? 1024 : 512);
            int vertical = 0;
            int horizontal = 0;
            for (Block log : logs) {
                if (log.getBlockData() instanceof Orientable data) {
                    if (data.getAxis() == Axis.Y) vertical++; else horizontal++;
                }
            }
            if (!validStructure(logs.size(), leaves.size(), longestHorizontalRun(logs), vertical, horizontal)) return;
            tree = new HashSet<>(logs);
            tree.addAll(leaves);
            tree.addAll(attachedJungleGrowth(logs, leaves));
        } catch (OutsideRegion ignored) {
            // Leave vanilla breaking intact if the complete tree cannot safely be inspected.
            return;
        }
        event.setCancelled(true);
        breaking.set(true);
        try {
            breakTree(base, tree, player::breakBlock,
                    () -> isAxe(player.getInventory().getItemInMainHand().getType()));
        } finally {
            breaking.remove();
        }
    }

    static void breakTree(Block base, Set<Block> tree, java.util.function.Predicate<Block> breakBlock,
                          java.util.function.BooleanSupplier hasAxe) {
        // Actual player breaks preserve protection events, drops, crop tracking and tool durability.
        if (!breakBlock.test(base)) return;
        for (Block block : tree.stream().sorted(Comparator.comparingInt(Block::getY).reversed()).toList()) {
            if (block.equals(base)) continue;
            if (!hasAxe.getAsBoolean()) break;
            if (!breakBlock.test(block)) break;
        }
    }

    private Block relative(Block block, int x, int y, int z) {
        int targetY = block.getY() + y;
        if (targetY < block.getWorld().getMinHeight() || targetY >= block.getWorld().getMaxHeight()
                || !plugin.getServer().isOwnedByCurrentRegion(block.getWorld(),
                        (block.getX() + x) >> 4, (block.getZ() + z) >> 4)) throw new OutsideRegion();
        return block.getRelative(x, y, z);
    }

    static boolean isAxe(Material material) { return material.name().endsWith("_AXE"); }

    static boolean isTreeTrunk(Material material) {
        return material.name().endsWith("_LOG") && !material.name().startsWith("STRIPPED_")
                || material == Material.WARPED_STEM || material == Material.CRIMSON_STEM;
    }

    static boolean validStructure(int logs, int leaves, int run, int vertical, int horizontal) {
        return logs >= 4 && leaves >= 10 && run <= 6
                && (horizontal == 0 || vertical / (double) horizontal >= .5);
    }

    static boolean isNetherFungusFoliage(Material stem, Material material) {
        return material == Material.SHROOMLIGHT
                || stem == Material.WARPED_STEM && material == Material.WARPED_WART_BLOCK
                || stem == Material.CRIMSON_STEM && material == Material.NETHER_WART_BLOCK;
    }

    private Set<Block> connectedLogs(Block base, int maximumLogs) {
        Set<Block> logs = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(base);

        while (!queue.isEmpty()) {
            Block block = queue.removeFirst();
            if (block.getY() < base.getY() || block.getType() != base.getType() || !logs.add(block)) {
                continue;
            }
            if (logs.size() > maximumLogs) {
                return Set.of();
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            queue.add(relative(block, x, y, z));
                        }
                    }
                }
            }
        }
        return logs;
    }

    private Set<Block> connectedLeaves(Set<Block> logs, int maximumLeaves) {
        if (logs.isEmpty()) {
            return Set.of();
        }
        Material trunk = logs.iterator().next().getType();
        if (trunk == Material.WARPED_STEM || trunk == Material.CRIMSON_STEM) {
            return connectedNetherFungusCanopy(logs, maximumLeaves, trunk);
        }

        Set<Block> leaves = new HashSet<>();
        ArrayDeque<LeafCandidate> queue = new ArrayDeque<>();
        for (Block log : logs) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (Math.abs(x) + Math.abs(y) + Math.abs(z) == 1) {
                            queue.add(new LeafCandidate(relative(log, x, y, z), 0));
                        }
                    }
                }
            }
        }

        while (!queue.isEmpty() && leaves.size() < maximumLeaves) {
            LeafCandidate candidate = queue.removeFirst();
            Block block = candidate.block();
            if (!(block.getBlockData() instanceof Leaves data) || data.isPersistent()
                || data.getDistance() <= candidate.previousDistance() || !leaves.add(block)) {
                continue;
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            queue.add(new LeafCandidate(relative(block, x, y, z), data.getDistance()));
                        }
                    }
                }
            }
        }
        return leaves;
    }

    private Set<Block> connectedNetherFungusCanopy(Set<Block> stems, int maximumBlocks,
                                                    Material stem) {
        Set<Block> canopy = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        for (Block block : stems) {
            addNeighbors(queue, block);
        }
        while (!queue.isEmpty() && canopy.size() < maximumBlocks) {
            Block block = queue.removeFirst();
            if (!isNetherFungusFoliage(stem, block.getType()) || !canopy.add(block)) {
                continue;
            }
            addNeighbors(queue, block);
        }
        return canopy;
    }

    private void addNeighbors(ArrayDeque<Block> queue, Block block) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        queue.add(relative(block, x, y, z));
                    }
                }
            }
        }
    }

    private Set<Block> attachedJungleGrowth(Set<Block> logs, Set<Block> leaves) {
        int maximum = 512;
        return attachedJungleGrowth(logs, leaves, maximum);
    }

    private Set<Block> attachedJungleGrowth(Set<Block> logs, Set<Block> leaves, int maximum) {
        Set<Block> attached = new HashSet<>();
        for (Block log : logs) {
            for (BlockFace face : HORIZONTAL_TREE_FACES) {
                Block candidate = relative(log, face.getModX(), face.getModY(), face.getModZ());
                if (candidate.getType() == Material.COCOA) {
                    attached.add(candidate);
                    if (attached.size() >= maximum) {
                        return attached;
                    }
                }
            }
        }
        Set<Block> anchors = new HashSet<>(logs);
        anchors.addAll(leaves);
        for (Block anchor : anchors) {
            for (BlockFace face : VINE_ANCHOR_FACES) {
                if (attached.size() >= maximum) {
                    return attached;
                }
                Block vine = relative(anchor, face.getModX(), face.getModY(), face.getModZ());
                if (vine.getType() != Material.VINE) {
                    continue;
                }
                do {
                    attached.add(vine);
                    vine = relative(vine, 0, -1, 0);
                } while (attached.size() < maximum && vine.getType() == Material.VINE);
            }
        }
        return attached;
    }

    private boolean isLargeJungleTrunk(Block base) {
        if (base.getType() != Material.JUNGLE_LOG) {
            return false;
        }
        for (int cornerX = -1; cornerX <= 0; cornerX++) {
            for (int cornerZ = -1; cornerZ <= 0; cornerZ++) {
                boolean square = true;
                for (int x = 0; x <= 1 && square; x++) {
                    for (int z = 0; z <= 1; z++) {
                        if (relative(base, cornerX + x, 0, cornerZ + z).getType()
                            != Material.JUNGLE_LOG
                            || relative(base, cornerX + x, 1, cornerZ + z).getType()
                            != Material.JUNGLE_LOG) {
                            square = false;
                            break;
                        }
                    }
                }
                if (square) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int longestHorizontalRun(Set<Block> logs) {
        int longest = 0;
        for (Block log : logs) {
            int xRun = 1;
            for (int x = 1; logs.contains(log.getRelative(x, 0, 0)); x++) {
                xRun++;
            }
            for (int x = -1; logs.contains(log.getRelative(x, 0, 0)); x--) {
                xRun++;
            }
            int zRun = 1;
            for (int z = 1; logs.contains(log.getRelative(0, 0, z)); z++) {
                zRun++;
            }
            for (int z = -1; logs.contains(log.getRelative(0, 0, z)); z--) {
                zRun++;
            }
            longest = Math.max(longest, Math.max(xRun, zRun));
        }
        return longest;
    }

    private record LeafCandidate(Block block, int previousDistance) { }
    private static final class OutsideRegion extends RuntimeException { }
}
