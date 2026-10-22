package com.onemillioncrops.listener;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.listener.CropMarkers.Marker;
import com.onemillioncrops.listener.PendingHarvestSources.Candidate;
import com.onemillioncrops.listener.PendingHarvestSources.Position;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.service.PlacedSourceTracker;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which crop items count. Only an item carrying an eligible marker is ever credited,
 * and that marker is only given out where a harvest is seen: a player breaking or picking a
 * grown crop, water or a piston breaking one, or one destroyed by physics, an explosion or a
 * mob. Crops coming out of anything else (a player, a mob, a container) are not a harvest.
 */
public final class CropPickupListener implements Listener {
    private final OneMillionCropsPlugin plugin;
    private final CropMarkers markers;
    private final PlacedSourceTracker placedSources;
    private final PendingHarvestSources pendingHarvests = new PendingHarvestSources(Bukkit::getCurrentTick);
    private Map<String, CropDefinition> sourceMaterialsFor;
    private Set<Material> sourceMaterials = Set.of();

    public CropPickupListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.markers = new CropMarkers(plugin);
        this.placedSources = new PlacedSourceTracker(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        boolean source = isCropSource(event.getBlockState().getType());
        if (!source && event.getItems().stream()
                .noneMatch(item -> plugin.configManager().cropByItem(item.getItemStack().getType()) != null)) {
            return;
        }
        CropDefinition sourceCrop = source ? plugin.configManager().cropBySource(event.getBlockState()) : null;
        boolean playerPlaced = placedSources.consume(event.getBlockState().getBlock())
                && plugin.configManager().settings().blockPlayerRedrops();
        for (Item item : event.getItems()) {
            CropDefinition itemCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
            if (playerPlaced || markers.effective(item).blocked()) {
                markers.markBlocked(item);
            } else if (sourceCrop != null && itemCrop != null && sourceCrop.id().equals(itemCrop.id())) {
                markers.markEligible(item, sourceCrop.id());
            } else if (itemCrop != null && markers.effective(item).eligibleId() == null) {
                // A crop from some other block is not a harvest. One that already carries an
                // eligible marker (spilled from a container) keeps it until it is collected.
                markers.markBlocked(item);
            }
        }
    }

    /**
     * Flowing water and pistons break crops without a player ever touching them. Drops of a
     * grown crop are marked eligible on the stack, and water-harvested ones are credited
     * straight away when farms may credit the total by themselves; whatever is not credited
     * (automation off, ALLOWLIST mode, maintenance, a finished crop) waits to be collected.
     * Cocoa handles its own water break in {@link CocoaAutoReplantListener} because it also
     * has to pay a bean to replant itself.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreakBlock(BlockBreakBlockEvent event) {
        Block block = event.getBlock();
        Material source = event.getSource().getType();
        if (!isCropSource(block.getType()) || CocoaAutoReplantListener.isWaterCocoaBreak(source, block.getType())) {
            return;
        }
        boolean playerPlaced = placedSources.consume(block) && plugin.configManager().settings().blockPlayerRedrops();
        CropDefinition crop = plugin.configManager().cropBySource(block.getState());
        if (crop == null) {
            return;
        }
        if (!playerPlaced && CocoaAutoReplantListener.isWater(source)
                && plugin.configManager().settings().creditsAutomatedFarms()) {
            AutomatedDrops.credit(event.getDrops(), crop.item(),
                    amount -> plugin.recordAutomatedPickup(crop, amount));
        }
        markers.markHarvest(event.getDrops(), crop, playerPlaced);
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
        if (plugin.configManager().settings().blockPlayerRedrops() && isCropSource(placed.getType())) {
            placedSources.mark(placed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        // This runs for every block broken on the server, so skip the state snapshot unless
        // the block could be a crop source at all.
        if (!isCropSource(block.getType())) {
            return;
        }
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
        if (!isCropSource(newState.getType())) {
            return;
        }
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

    /**
     * Physics, pistons pulling a support away, trampling and mobs such as farmer villagers
     * destroy blocks with no drop event. The upper pieces of sugar cane, bamboo, kelp, cactus
     * and chorus come off this way when their base is broken, which is how those crops are
     * harvested, so their drops are recognised by where they spawn.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDestroy(BlockDestroyEvent event) {
        if (event.willDrop()) {
            recordPendingHarvests(List.of(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (destroysBlocks(event.getExplosionResult())) {
            recordPendingHarvests(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (destroysBlocks(event.getExplosionResult())) {
            recordPendingHarvests(event.blockList());
        }
    }

    /** Foxes pick ripe berries and drop all but the one they keep in their mouth. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoxHarvest(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Fox) {
            recordPendingHarvests(List.of(event.getBlock()));
        }
    }

    private void recordPendingHarvests(Collection<Block> blocks) {
        boolean blockPlayerRedrops = plugin.configManager().settings().blockPlayerRedrops();
        List<Candidate> destroyed = new ArrayList<>();
        for (Block block : blocks) {
            if (!isCropSource(block.getType())) {
                continue;
            }
            CropDefinition crop = plugin.configManager().cropBySource(block.getState());
            if (crop != null) {
                destroyed.add(new Candidate(Position.of(block), crop.id(), crop.item(),
                        blockPlayerRedrops && placedSources.contains(block)));
            }
        }
        pendingHarvests.record(destroyed);
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

    /**
     * Item entities without a marker never count. One spawning where a crop was just destroyed
     * (see {@link #onBlockDestroy}) is that crop's drop and becomes eligible; one spawning on
     * a player-placed source is blocked, which also clears that source's marker.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (plugin.configManager().cropByItem(stack.getType()) == null) {
            return;
        }
        if (markers.moveStackMarkerToEntity(item).isPresent() || markers.read(item).isPresent()) {
            // Already decided, e.g. by onBlockDrops. Looking for a placed source here would
            // consume the marker of an unrelated placed block underneath.
            return;
        }
        boolean blockPlayerRedrops = plugin.configManager().settings().blockPlayerRedrops();
        Block block = item.getLocation().getBlock();
        if (blockPlayerRedrops && placedSources.consume(block)) {
            markers.markBlocked(item);
            return;
        }
        // Destroyed blocks drop inside their own space, so only the exact block is checked:
        // also checking below would let a chest above a crop spill its contents as a harvest.
        String cropId = pendingHarvests.claim(Position.of(block), stack.getType(), stack.getAmount());
        if (cropId != null) {
            markers.markEligible(item, cropId);
            return;
        }
        // A few crop drops spawn just above the source block's coordinates.
        if (blockPlayerRedrops && placedSources.consume(block.getRelative(BlockFace.DOWN))) {
            markers.markBlocked(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRightClickHarvest(PlayerHarvestBlockEvent event) {
        CropDefinition crop = plugin.configManager().cropBySource(event.getHarvestedBlock().getState());
        if (crop == null) {
            return;
        }
        markers.markHarvest(event.getItemsHarvested(), crop, placedSources.contains(event.getHarvestedBlock()));
    }

    /**
     * A dropped crop is blocked. With block-player-redrops turned off the server has chosen to
     * allow drop-and-repickup, so the drop is made eligible instead; unmarked it would not count.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        CropDefinition crop = plugin.configManager().cropByItem(event.getItemDrop().getItemStack().getType());
        if (crop == null) {
            return;
        }
        if (plugin.configManager().settings().blockPlayerRedrops()) {
            markers.markBlocked(event.getItemDrop());
        } else {
            markers.markEligible(event.getItemDrop(), crop.id());
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
        if (event.getKeepInventory()) {
            return;
        }
        boolean blockPlayerRedrops = plugin.configManager().settings().blockPlayerRedrops();
        for (ItemStack drop : event.getDrops()) {
            CropDefinition crop = plugin.configManager().cropByItem(drop.getType());
            if (crop == null) {
                continue;
            }
            // Like onPlayerDrop, only a server that allows redrops lets death drops count again.
            if (blockPlayerRedrops) {
                markers.markBlocked(drop);
            } else {
                markers.markEligible(drop, crop.id());
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
        CropDefinition crop = creditableCrop(item.getItemStack().getType(),
                plugin.configManager().crop(markers.read(item).creditableCropId()));
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
        if (plugin.configManager().cropByItem(item.getItemStack().getType()) == null) {
            return;
        }

        // The hopper copies the entity's stack after this event, so the marker has to be in
        // the stack for the inserted part to carry it into the container.
        ItemStack stack = item.getItemStack();
        Marker stackMarker = hopperStackMarker(stack.getType(),
                plugin.configManager().crop(markers.effective(item).creditableCropId()));
        if (stackMarker.blocked()) {
            markers.markBlocked(stack);
        } else {
            markers.markEligible(stack, stackMarker.eligibleId());
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

    /** Whether any enabled crop uses this block as a source, cached until the crops are reloaded. */
    private boolean isCropSource(Material material) {
        Map<String, CropDefinition> crops = plugin.configManager().crops();
        if (crops != sourceMaterialsFor) {
            sourceMaterials = sourceMaterials(crops.values());
            sourceMaterialsFor = crops;
        }
        return sourceMaterials.contains(material);
    }

    private String state(Item item) {
        Marker marker = markers.effective(item);
        if (marker.blocked()) {
            return "blocked";
        }
        return marker.eligibleId() == null ? "automatic" : "eligible:" + marker.eligibleId();
    }

    /**
     * The crop an item counts as: the crop its eligible marker names, as long as that crop is
     * still enabled and the item is its crop item. Anything else, unmarked items included,
     * never counts.
     */
    static CropDefinition creditableCrop(Material material, CropDefinition markedCrop) {
        return markedCrop != null && markedCrop.item() == material ? markedCrop : null;
    }

    /** What a hopper writes into the stack it takes: the crop if the item may count, else blocked. */
    static Marker hopperStackMarker(Material material, CropDefinition markedCrop) {
        CropDefinition crop = creditableCrop(material, markedCrop);
        return crop == null ? new Marker(true, null) : new Marker(false, crop.id());
    }

    static boolean destroysBlocks(ExplosionResult result) {
        return result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY;
    }

    /** The entity's marker after a player pickup moves the stack's marker onto it. */
    static Marker playerPickupMarker(Marker entity, Marker stack) {
        return entity.combine(stack);
    }

    static Set<Material> sourceMaterials(Collection<CropDefinition> crops) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        crops.forEach(crop -> materials.addAll(crop.sources()));
        return materials;
    }

    static boolean shouldBlockItemFrameItem(boolean blockPlayerRedrops, boolean cropItem) {
        return blockPlayerRedrops && cropItem;
    }
}
