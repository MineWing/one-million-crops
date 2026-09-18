package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.service.PlacedSourceTracker;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Iterator;
import java.util.List;

public final class CropPickupListener implements Listener {
    private final OneMillionCropsPlugin plugin;
    private final NamespacedKey eligibleCropKey;
    private final NamespacedKey blockedKey;
    private final NamespacedKey hopperPendingKey;
    private final PlacedSourceTracker placedSources;

    public CropPickupListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.eligibleCropKey = new NamespacedKey(plugin, "eligible_crop");
        this.blockedKey = new NamespacedKey(plugin, "blocked_pickup");
        this.hopperPendingKey = new NamespacedKey(plugin, "hopper_pickup_pending");
        this.placedSources = new PlacedSourceTracker(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        CropDefinition sourceCrop = plugin.configManager().cropBySource(event.getBlockState());
        boolean playerPlaced = placedSources.consume(event.getBlockState().getBlock())
                && plugin.configManager().settings().blockPlayerRedrops();
        for (Item item : event.getItems()) {
            CropDefinition itemCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
            if (playerPlaced || isBlocked(item)) {
                markBlocked(item);
            } else if (sourceCrop != null && itemCrop != null && sourceCrop.id().equals(itemCrop.id())) {
                markEligible(item, sourceCrop);
            } else if (itemCrop != null) {
                markBlocked(item);
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
        int amount = takeMatching(event.getDrops(), crop.item());
        if (amount > 0) {
            plugin.recordAutomatedPickup(crop, amount);
        }
    }

    private static int takeMatching(List<ItemStack> drops, Material item) {
        int total = 0;
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack.getType() == item) {
                total += stack.getAmount();
                iterator.remove();
            }
        }
        return total;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.configManager().settings().blockPlayerRedrops()) {
            return;
        }
        CropDefinition crop = plugin.configManager().cropByItem(event.getItemInHand().getType());
        if (crop != null && crop.sources().contains(event.getBlockPlaced().getType())) {
            placedSources.mark(event.getBlockPlaced());
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (!plugin.configManager().settings().blockPlayerRedrops()
                || plugin.configManager().cropByItem(event.getItem().getType()) == null) {
            return;
        }
        ItemStack item = event.getItem().clone();
        markItemBlocked(item);
        event.setItem(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        String eligible = itemMarker(stack, eligibleCropKey, PersistentDataType.STRING);
        boolean blocked = hasItemMarker(stack, blockedKey, PersistentDataType.BYTE);
        clearItemMarkers(stack);
        item.setItemStack(stack);
        if (blocked) {
            markBlocked(item);
        } else if (eligible != null) {
            item.getPersistentDataContainer().set(eligibleCropKey, PersistentDataType.STRING, eligible);
        } else if (plugin.configManager().settings().blockPlayerRedrops()
                && plugin.configManager().cropByItem(item.getItemStack().getType()) != null
                && consumePlacedSourceAt(item)) {
            markBlocked(item);
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
                markItemBlocked(stack);
            } else {
                markItemEligible(stack, crop);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.configManager().settings().blockPlayerRedrops()
                && plugin.configManager().cropByItem(event.getItemDrop().getItemStack().getType()) != null) {
            markBlocked(event.getItemDrop());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        ItemStack stack = event.getItemStack();
        if (!shouldBlockItemFrameItem(plugin.configManager().settings().blockPlayerRedrops(),
                plugin.configManager().cropByItem(stack.getType()) != null)) {
            return;
        }
        markItemBlocked(stack);
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
        markItemBlocked(stack);
        frame.setItem(stack, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.configManager().settings().blockPlayerRedrops() || event.getKeepInventory()) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            if (plugin.configManager().cropByItem(drop.getType()) != null) {
                markItemBlocked(drop);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        String sourceState = state(event.getEntity());
        String targetState = state(event.getTarget());
        if (!sourceState.equals(targetState)) {
            event.setCancelled(true);
        }
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
        recoverInterruptedHopperPickup(item);
        if (isBlocked(item)) {
            return;
        }
        CropDefinition materialCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
        if (materialCrop == null) {
            return;
        }
        String eligibleId = eligibleId(item);
        CropDefinition crop;
        if (eligibleId != null) {
            crop = plugin.configManager().crop(eligibleId);
            if (crop == null || crop.item() != item.getItemStack().getType()) {
                return;
            }
        } else if (plugin.configManager().settings().allowAutomatedFarms()) {
            crop = materialCrop;
        } else {
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
        recoverInterruptedHopperPickup(item);
        CropDefinition materialCrop = plugin.configManager().cropByItem(item.getItemStack().getType());
        if (materialCrop == null) {
            return;
        }

        ItemStack stack = item.getItemStack();
        String eligibleId = eligibleId(item);
        CropDefinition eligibleCrop = eligibleId == null
                ? null
                : plugin.configManager().crop(eligibleId);
        boolean validEligibleCrop = eligibleCrop != null && eligibleCrop.item() == stack.getType();
        HopperPickupPolicy policy = hopperPickupPolicy(isBlocked(item), validEligibleCrop,
                plugin.configManager().settings().allowAutomatedFarms());
        if (policy == HopperPickupPolicy.BLOCK) {
            markItemBlocked(stack);
            item.setItemStack(stack);
            return;
        }

        CropDefinition crop = validEligibleCrop ? eligibleCrop : materialCrop;
        markItemEligible(stack, crop);
        item.setItemStack(stack);
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
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        boolean blocked = hasItemMarker(stack, blockedKey, PersistentDataType.BYTE);
        String eligibleId = itemMarker(stack, eligibleCropKey, PersistentDataType.STRING);
        if (!blocked && eligibleId == null) {
            return;
        }

        clearItemMarkers(stack);
        if (blocked) {
            return;
        }
        CropDefinition crop = plugin.configManager().crop(eligibleId);
        if (crop != null && crop.item() == stack.getType()) {
            plugin.recordPickup(player, crop, stack.getAmount());
        }
    }

    private void recoverInterruptedHopperPickup(Item item) {
        String cropId = item.getPersistentDataContainer()
                .get(hopperPendingKey, PersistentDataType.STRING);
        if (cropId == null) {
            return;
        }
        item.getPersistentDataContainer().remove(hopperPendingKey);
        CropDefinition crop = plugin.configManager().crop(cropId);
        ItemStack stack = item.getItemStack();
        if (crop != null && crop.item() == stack.getType()) {
            markItemEligible(stack, crop);
            item.setItemStack(stack);
        }
    }

    private void markEligible(Item item, CropDefinition crop) {
        item.getPersistentDataContainer().remove(blockedKey);
        item.getPersistentDataContainer().set(eligibleCropKey, PersistentDataType.STRING, crop.id());
    }

    private void markBlocked(Item item) {
        item.getPersistentDataContainer().remove(eligibleCropKey);
        item.getPersistentDataContainer().set(blockedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void markItemEligible(ItemStack item, CropDefinition crop) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(blockedKey);
        meta.getPersistentDataContainer().set(eligibleCropKey, PersistentDataType.STRING, crop.id());
        item.setItemMeta(meta);
    }

    private void markItemBlocked(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(eligibleCropKey);
        meta.getPersistentDataContainer().set(blockedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    private void clearItemMarkers(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(eligibleCropKey);
        meta.getPersistentDataContainer().remove(blockedKey);
        item.setItemMeta(meta);
    }

    private <T> T itemMarker(ItemStack item, NamespacedKey key, PersistentDataType<?, T> type) {
        return item.getItemMeta().getPersistentDataContainer().get(key, type);
    }

    private <T> boolean hasItemMarker(ItemStack item, NamespacedKey key, PersistentDataType<?, T> type) {
        return item.getItemMeta().getPersistentDataContainer().has(key, type);
    }

    private boolean consumePlacedSourceAt(Item item) {
        Block block = item.getLocation().getBlock();
        if (placedSources.consume(block)) {
            return true;
        }
        // A few crop drops spawn just above the source block's coordinates.
        return placedSources.consume(block.getRelative(0, -1, 0));
    }

    private boolean isBlocked(Item item) {
        return item.getPersistentDataContainer().has(blockedKey, PersistentDataType.BYTE)
                || hasItemMarker(item.getItemStack(), blockedKey, PersistentDataType.BYTE);
    }

    private String eligibleId(Item item) {
        String entityValue = item.getPersistentDataContainer().get(eligibleCropKey, PersistentDataType.STRING);
        return entityValue != null ? entityValue
                : itemMarker(item.getItemStack(), eligibleCropKey, PersistentDataType.STRING);
    }

    private String state(Item item) {
        if (item.getPersistentDataContainer().has(hopperPendingKey, PersistentDataType.STRING)) {
            return "hopper-pending:" + item.getUniqueId();
        }
        if (isBlocked(item)) {
            return "blocked";
        }
        String eligible = eligibleId(item);
        return eligible == null ? "automatic" : "eligible:" + eligible;
    }

    static HopperPickupPolicy hopperPickupPolicy(boolean blocked, boolean validEligibleCrop,
                                                  boolean allowAutomatedFarms) {
        return !blocked && (validEligibleCrop || allowAutomatedFarms)
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
