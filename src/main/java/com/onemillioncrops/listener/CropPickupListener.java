package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.listener.CropMarkers.Marker;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.service.PlacedSourceTracker;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

public final class CropPickupListener implements Listener {
    private final OneMillionCropsPlugin plugin;
    private final CropMarkers markers;
    private final PlacedSourceTracker placedSources;

    public CropPickupListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.markers = new CropMarkers(plugin);
        this.placedSources = new PlacedSourceTracker(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        CropDefinition sourceCrop = plugin.configManager().cropBySource(event.getBlockState());
        boolean playerPlaced = placedSources.consume(event.getBlockState().getBlock())
                && plugin.configManager().settings().blockPlayerRedrops();
        for (Item item : event.getItems()) {
            CropDefinition itemCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
            if (playerPlaced || markers.effective(item).blocked()) {
                markers.markBlocked(item);
            } else if (sourceCrop != null && itemCrop != null && sourceCrop.id().equals(itemCrop.id())) {
                markers.markEligible(item, sourceCrop.id());
            } else if (itemCrop != null) {
                markers.markBlocked(item);
            }
        }
    }

    /**
     * Flowing water can knock a mature crop loose without a player ever touching it.
     * Cocoa handles its own water break in {@link CocoaAutoReplantListener} because it also
     * has to pay a bean to replant itself; every other crop just gets credited here instead
     * of waiting for someone to walk over and collect the drops.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWaterHarvest(BlockBreakBlockEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.COCOA
                || !CocoaAutoReplantListener.isWater(event.getSource().getType())
                || !plugin.configManager().settings().allowAutomatedFarms()) {
            return;
        }
        CropDefinition crop = plugin.configManager().cropBySource(block.getState());
        if (crop == null) {
            return;
        }
        if (placedSources.consume(block) && plugin.configManager().settings().blockPlayerRedrops()) {
            return;
        }
        AutomatedDrops.credit(event.getDrops(), crop.item(),
                amount -> plugin.recordAutomatedPickup(crop, amount));
    }

    /**
     * Marks any player-placed block that counts as a crop source, whatever item placed it.
     * A crafted melon block or a silk-touched mushroom block is placed from an item that is
     * not the crop itself, so the decision has to be made from the placed block. Planted
     * seeds are marked too; {@link #releaseGrownSource} lifts the marker once they grow.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (tracksPlacement(plugin.configManager().settings().blockPlayerRedrops(),
                plugin.configManager().crops().values(), placed.getType())) {
            placedSources.mark(placed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        CropDefinition crop = plugin.configManager().cropBySource(block.getState(), false);
        if (crop == null || !placedSources.contains(block)) {
            return;
        }
        Block grownAbove = block.getRelative(BlockFace.UP);
        if (crop.sources().contains(grownAbove.getType()) && !placedSources.contains(grownAbove)) {
            // Migration path for vertical crops which grew before this fix was installed.
            placedSources.consume(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        releaseGrownSource(event.getBlock(), event.getNewState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        releaseGrownSource(event.getBlock(), event.getNewState());
    }

    private void releaseGrownSource(Block grownBlock, BlockState newState) {
        CropDefinition crop = plugin.configManager().cropBySource(newState, true);
        if (crop == null || placedSources.consume(grownBlock)) {
            return;
        }

        // Vertical crops create a new block above the originally placed source.
        // Sugar cane uses BlockGrowEvent while bamboo uses BlockSpreadEvent, so
        // both paths converge here to release the planted base marker.
        Block source = grownBlock.getRelative(BlockFace.DOWN);
        while (source.getY() >= source.getWorld().getMinHeight()
                && crop.sources().contains(source.getType())) {
            if (placedSources.consume(source)) {
                return;
            }
            source = source.getRelative(BlockFace.DOWN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        movePlacedSources(event.getBlocks(), CocoaAutoReplantListener.pistonMovement(event.getDirection(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        movePlacedSources(event.getBlocks(), CocoaAutoReplantListener.pistonMovement(event.getDirection(), false));
    }

    private void movePlacedSources(List<Block> blocks, BlockFace movement) {
        // The piston also lists the blocks it breaks. Those keep their marker, because their
        // drops spawn in place and onItemSpawn looks the marker up there.
        List<Block> moving = blocks.stream()
                .filter(block -> block.getPistonMoveReaction() != PistonMoveReaction.BREAK)
                .toList();
        List<Block> destinations = placedSources.move(moving, movement);
        if (destinations.isEmpty()) {
            return;
        }
        // A broken block's drop landing on a destination would consume the marker that just
        // arrived there, so put the moved markers back once the push has finished.
        Bukkit.getScheduler().runTask(plugin, () -> destinations.stream()
                .filter(destination -> !destination.isEmpty())
                .forEach(placedSources::mark));
    }

    /** Endermen lift a block without dropping anything, so no drop ever consumes its marker. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndermanTake(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Enderman && event.getTo().isAir()) {
            placedSources.consume(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (!plugin.configManager().settings().blockPlayerRedrops()
                || plugin.configManager().cropByItem(event.getItem().getType()) == null) {
            return;
        }
        ItemStack item = event.getItem().clone();
        markers.markBlocked(item);
        event.setItem(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (plugin.configManager().cropByItem(item.getItemStack().getType()) == null) {
            return;
        }
        if (markers.moveStackMarkerToEntity(item).isPresent() || markers.read(item).isPresent()) {
            // Already decided, e.g. by onBlockDrops. Looking for a placed source here would
            // consume the marker of an unrelated placed block underneath.
            return;
        }
        if (plugin.configManager().settings().blockPlayerRedrops() && consumePlacedSourceAt(item)) {
            markers.markBlocked(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRightClickHarvest(PlayerHarvestBlockEvent event) {
        CropDefinition crop = plugin.configManager().cropBySource(event.getHarvestedBlock().getState());
        if (crop == null) {
            return;
        }
        boolean blocked = placedSources.contains(event.getHarvestedBlock());
        for (ItemStack stack : event.getItemsHarvested()) {
            if (stack.getType() != crop.item()) {
                continue;
            }
            if (blocked) {
                markers.markBlocked(stack);
            } else {
                markers.markEligible(stack, crop.id());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.configManager().settings().blockPlayerRedrops()
                && plugin.configManager().cropByItem(event.getItemDrop().getItemStack().getType()) != null) {
            markers.markBlocked(event.getItemDrop());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        ItemStack stack = event.getItemStack();
        if (!shouldBlockItemFrameItem(plugin.configManager().settings().blockPlayerRedrops(),
                plugin.configManager().cropByItem(stack.getType()) != null)) {
            return;
        }
        markers.markBlocked(stack);
        event.setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        ItemStack stack = frame.getItem();
        if (!shouldBlockItemFrameItem(plugin.configManager().settings().blockPlayerRedrops(),
                plugin.configManager().cropByItem(stack.getType()) != null)) {
            return;
        }
        markers.markBlocked(stack);
        frame.setItem(stack, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.configManager().settings().blockPlayerRedrops() || event.getKeepInventory()) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            if (plugin.configManager().cropByItem(drop.getType()) != null) {
                markers.markBlocked(drop);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (!state(event.getEntity()).equals(state(event.getTarget()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Runs before {@link #onPickup} so that a player only ever receives clean stacks. A
     * marker left in the stack (a hopper's leftover, for example) moves onto the entity,
     * where onPickup credits it once; otherwise the marked stack would be credited again by
     * the next inventory click.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPickupMarkers(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (plugin.configManager().cropByItem(stack.getType()) == null) {
            return;
        }
        ItemStack clean = stack.clone();
        markers.write(item, playerPickupMarker(markers.read(item), markers.clear(clean)));
        // While this event runs Paper has shrunk the entity's stack to what the player can
        // hold, and only restores the rest if the stack object is left alone. Replacing it
        // means handing back the full amount, which also keeps onPickup's arithmetic right.
        clean.setAmount(stack.getAmount() + event.getRemaining());
        item.setItemStack(clean);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.configManager().settings().mayContribute(player.getUniqueId())) {
            return;
        }
        Item item = event.getItem();
        Marker marker = markers.read(item);
        if (marker.blocked()) {
            return;
        }
        CropDefinition materialCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
        if (materialCrop == null) {
            return;
        }
        CropDefinition crop = playerPickupCrop(materialCrop, marker.eligibleId(),
                plugin.configManager().crop(marker.eligibleId()));
        if (crop == null) {
            return;
        }

        int pickedUp = item.getItemStack().getAmount() - event.getRemaining();
        if (pickedUp <= 0) {
            return;
        }
        plugin.recordPickup(player, crop, pickedUp);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        CropDefinition materialCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
        if (materialCrop == null) {
            return;
        }

        // The hopper copies the entity's stack after this event, so the marker has to be in
        // the stack for the inserted part to carry it into the container.
        ItemStack stack = item.getItemStack();
        Marker marker = markers.effective(item);
        if (hopperPickupPolicy(marker.blocked()) == HopperPickupPolicy.BLOCK) {
            markers.markBlocked(stack);
        } else {
            CropDefinition eligibleCrop = plugin.configManager().crop(marker.eligibleId());
            boolean validEligibleCrop = eligibleCrop != null && eligibleCrop.item() == stack.getType();
            markers.markEligible(stack, (validEligibleCrop ? eligibleCrop : materialCrop).id());
        }
        item.setItemStack(stack);

        // Whatever the hopper had no room for stays on the ground; move its marker back off
        // the stack so it is never carried into a player's inventory.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (item.isValid()) {
                markers.moveStackMarkerToEntity(item);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && plugin.configManager().settings().mayContribute(player.getUniqueId())) {
            scheduleContainerHarvest(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && plugin.configManager().settings().mayContribute(player.getUniqueId())) {
            scheduleContainerHarvest(player);
        }
    }

    private void scheduleContainerHarvest(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> consumeContainerHarvests(player));
    }

    private void consumeContainerHarvests(Player player) {
        if (!player.isOnline()) {
            return;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            consumeContainerHarvest(player, stack);
        }
        consumeContainerHarvest(player, player.getItemOnCursor());
    }

    private void consumeContainerHarvest(Player player, ItemStack stack) {
        String cropId = markers.clear(stack).creditableCropId();
        if (cropId == null) {
            return;
        }
        CropDefinition crop = plugin.configManager().crop(cropId);
        if (crop != null && crop.item() == stack.getType()) {
            plugin.recordPickup(player, crop, stack.getAmount());
        }
    }

    private boolean consumePlacedSourceAt(Item item) {
        Block block = item.getLocation().getBlock();
        if (placedSources.consume(block)) {
            return true;
        }
        // A few crop drops spawn just above the source block's coordinates.
        return placedSources.consume(block.getRelative(BlockFace.DOWN));
    }

    private String state(Item item) {
        Marker marker = markers.effective(item);
        if (marker.blocked()) {
            return "blocked";
        }
        return marker.eligibleId() == null ? "automatic" : "eligible:" + marker.eligibleId();
    }

    static CropDefinition playerPickupCrop(CropDefinition materialCrop, String eligibleId,
                                           CropDefinition eligibleCrop) {
        if (materialCrop == null) {
            return null;
        }
        if (eligibleId != null) {
            return eligibleCrop != null && eligibleCrop.item() == materialCrop.item() ? eligibleCrop : null;
        }
        return materialCrop;
    }

    /** The entity's marker after a player pickup moves the stack's marker onto it. */
    static Marker playerPickupMarker(Marker entity, Marker stack) {
        return entity.combine(stack);
    }

    static boolean tracksPlacement(boolean blockPlayerRedrops, Collection<CropDefinition> crops, Material placed) {
        return blockPlayerRedrops && crops.stream().anyMatch(crop -> crop.sources().contains(placed));
    }

    static HopperPickupPolicy hopperPickupPolicy(boolean blocked) {
        return !blocked
                ? HopperPickupPolicy.DEFER_UNTIL_PLAYER
                : HopperPickupPolicy.BLOCK;
    }

    static boolean shouldBlockItemFrameItem(boolean blockPlayerRedrops, boolean cropItem) {
        return blockPlayerRedrops && cropItem;
    }

    enum HopperPickupPolicy {
        DEFER_UNTIL_PLAYER,
        BLOCK
    }
}
