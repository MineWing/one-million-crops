package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replants cocoa pods broken by water or by moving their supporting jungle logs with a piston.
 * The replacement pod is paid for by one bean from the block's drops.
 */
public final class CocoaAutoReplantListener implements Listener {
    private static final int PENDING_DROP_TICKS = 20;
    private static final int PISTON_REPLANT_ATTEMPTS = 100;
    private static final long PISTON_RETRY_TICKS = 1L;
    private static final int WATER_REPLANT_ATTEMPTS = 300;
    private static final long WATER_RETRY_TICKS = 20L;
    private static final List<BlockFace> SIDES = List.of(
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    );
    private static final Set<Material> JUNGLE_SUPPORTS = EnumSet.of(
            Material.JUNGLE_LOG,
            Material.JUNGLE_WOOD,
            Material.STRIPPED_JUNGLE_LOG,
            Material.STRIPPED_JUNGLE_WOOD
    );

    private final OneMillionCropsPlugin plugin;
    private final Map<BlockPosition, ReplantPlan> pending = new HashMap<>();

    public CocoaAutoReplantListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        prepare(event.getBlocks(), pistonMovement(event.getDirection(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        prepare(event.getBlocks(), pistonMovement(event.getDirection(), false));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWaterBreak(BlockBreakBlockEvent event) {
        if (!isWaterCocoaBreak(event.getSource().getType(), event.getBlock().getType())) {
            return;
        }

        Block pod = event.getBlock();
        if (!(pod.getBlockData() instanceof Cocoa cocoa)
                || !isJungleSupport(pod.getRelative(cocoa.getFacing()).getType())) {
            return;
        }
        if (!consumeReplantBean(event.getDrops())) {
            return;
        }
        creditHarvestedBeans(event.getDrops());

        Cocoa seed = (Cocoa) cocoa.clone();
        seed.setAge(0);
        BlockPosition source = BlockPosition.of(pod);
        ReplantPlan plan = new ReplantPlan(source, source, seed);
        Bukkit.getScheduler().runTask(plugin,
                () -> replant(plan, WATER_REPLANT_ATTEMPTS, WATER_RETRY_TICKS));
    }

    /**
     * Water-harvested beans have no player to credit, so they count straight toward
     * the shared total (like a hopper farm's output would) instead of waiting on
     * someone to walk over and collect them from the ground.
     */
    private void creditHarvestedBeans(List<ItemStack> drops) {
        if (!plugin.configManager().settings().allowAutomatedFarms()) {
            return;
        }
        CropDefinition crop = plugin.configManager().cropByItem(Material.COCOA_BEANS);
        if (crop == null) {
            return;
        }
        int amount = takeBeans(drops);
        if (amount > 0) {
            plugin.recordAutomatedPickup(crop, amount);
        }
    }

    private void prepare(List<Block> movedBlocks, BlockFace movement) {
        for (Block support : movedBlocks) {
            if (!isJungleSupport(support.getType())) {
                continue;
            }
            for (BlockFace side : SIDES) {
                Block pod = support.getRelative(side);
                if (!(pod.getBlockData() instanceof Cocoa cocoa)
                        || !isAttachedToSupport(cocoa.getFacing(), side)) {
                    continue;
                }

                BlockPosition source = BlockPosition.of(pod);
                prepare(pod, source.relative(movement), cocoa);
            }
        }
    }

    private void prepare(Block pod, BlockPosition target, Cocoa cocoa) {
        Cocoa seed = (Cocoa) cocoa.clone();
        seed.setAge(0);
        BlockPosition source = BlockPosition.of(pod);
        ReplantPlan plan = new ReplantPlan(source, target, seed);
        pending.put(source, plan);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> pending.remove(source, plan), PENDING_DROP_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCocoaDrop(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (stack.getType() != Material.COCOA_BEANS) {
            return;
        }

        ReplantPlan plan = pending.remove(BlockPosition.of(item.getLocation().getBlock()));
        if (plan == null) {
            return;
        }

        int remaining = remainingAfterReplantCost(stack.getType(), stack.getAmount());
        if (remaining < 0) {
            return;
        }
        if (remaining == 0) {
            event.setCancelled(true);
        } else {
            stack.setAmount(remaining);
            item.setItemStack(stack);
        }
        Bukkit.getScheduler().runTask(plugin,
                () -> replant(plan, PISTON_REPLANT_ATTEMPTS, PISTON_RETRY_TICKS));
    }

    private void replant(ReplantPlan plan, int attemptsRemaining, long retryTicks) {
        World world = Bukkit.getWorld(plan.source().worldId());
        if (world == null) {
            return;
        }
        if (replantAt(world, plan.target(), plan.seed())
                || replantAt(world, plan.source(), plan.seed())) {
            return;
        }
        if (attemptsRemaining > 1) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> replant(plan, attemptsRemaining - 1, retryTicks), retryTicks);
        }
    }

    private boolean replantAt(World world, BlockPosition position, Cocoa seed) {
        Block target = world.getBlockAt(position.x(), position.y(), position.z());
        if (!target.isEmpty()) {
            return false;
        }
        Block support = target.getRelative(seed.getFacing());
        if (!isJungleSupport(support.getType())) {
            return false;
        }
        target.setBlockData(seed, false);
        return true;
    }

    static BlockFace pistonMovement(BlockFace pistonDirection, boolean extending) {
        return extending ? pistonDirection : pistonDirection.getOppositeFace();
    }

    static boolean isJungleSupport(Material material) {
        return material != null && JUNGLE_SUPPORTS.contains(material);
    }

    static boolean isWater(Material material) {
        return material == Material.WATER;
    }

    static boolean isWaterCocoaBreak(Material source, Material broken) {
        return isWater(source) && broken == Material.COCOA;
    }

    static long waterReplantWindowTicks() {
        return WATER_REPLANT_ATTEMPTS * WATER_RETRY_TICKS;
    }

    static boolean consumeReplantBean(List<ItemStack> drops) {
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack.getType() != Material.COCOA_BEANS || stack.getAmount() <= 0) {
                continue;
            }
            if (stack.getAmount() == 1) {
                iterator.remove();
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            return true;
        }
        return false;
    }

    static int takeBeans(List<ItemStack> drops) {
        int total = 0;
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack.getType() == Material.COCOA_BEANS) {
                total += stack.getAmount();
                iterator.remove();
            }
        }
        return total;
    }

    static boolean isAttachedToSupport(BlockFace cocoaFacing, BlockFace sideFromSupport) {
        return cocoaFacing == sideFromSupport.getOppositeFace();
    }

    static int remainingAfterReplantCost(Material material, int amount) {
        return material == Material.COCOA_BEANS && amount > 0 ? amount - 1 : -1;
    }

    private record ReplantPlan(BlockPosition source, BlockPosition target, Cocoa seed) {
    }

    private record BlockPosition(UUID worldId, int x, int y, int z) {
        static BlockPosition of(Block block) {
            return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        BlockPosition relative(BlockFace face) {
            return new BlockPosition(worldId, x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }
}
