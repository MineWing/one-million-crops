package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.gui.PlantWandGuiHolder;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlantWandListener implements Listener {
    static final long MAX_SELECTION_VOLUME = 32_768L;
    static final int MAX_PLANTS_PER_USE = 4_096;
    private static final int MAX_EFFECT_BLOCKS = 240;
    private static final int[] CROP_SLOTS = {10, 11, 12, 13, 14, 15, 16, 22};
    private static final List<PlantableCrop> CROPS = List.of(
            new PlantableCrop("wheat", Material.WHEAT_SEEDS, Material.WHEAT,
                    "<gradient:#F9D423:#FFEF94><bold>Wheat</bold></gradient>"),
            new PlantableCrop("carrot", Material.CARROT, Material.CARROTS,
                    "<gradient:#FF8C00:#FFD166><bold>Carrots</bold></gradient>"),
            new PlantableCrop("potato", Material.POTATO, Material.POTATOES,
                    "<gradient:#D6B36A:#FFF2B2><bold>Potatoes</bold></gradient>"),
            new PlantableCrop("beetroot", Material.BEETROOT_SEEDS, Material.BEETROOTS,
                    "<gradient:#9D174D:#FB7185><bold>Beetroot</bold></gradient>"),
            new PlantableCrop("pumpkin", Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM,
                    "<gradient:#F97316:#FACC15><bold>Pumpkins</bold></gradient>"),
            new PlantableCrop("melon", Material.MELON_SEEDS, Material.MELON_STEM,
                    "<gradient:#22C55E:#FB7185><bold>Melons</bold></gradient>"),
            new PlantableCrop("torchflower", Material.TORCHFLOWER_SEEDS, Material.TORCHFLOWER_CROP,
                    "<gradient:#F59E0B:#FB7185><bold>Torchflowers</bold></gradient>"),
            new PlantableCrop("pitcher", Material.PITCHER_POD, Material.PITCHER_CROP,
                    "<gradient:#74C0FC:#C084FC><bold>Pitcher Plants</bold></gradient>")
    );

    private final OneMillionCropsPlugin plugin;
    private final NamespacedKey wandKey;
    private final NamespacedKey cropKey;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public PlantWandListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "plant_wand");
        this.cropKey = new NamespacedKey(plugin, "plant_wand_crop");
    }

    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.WOODEN_HOE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(plugin.text().parse("<gradient:#8CE99A:#FFD166><bold>Plant Wand</bold></gradient>"));
        meta.lore(lore("gui.plant-wand.wand", Map.of()));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);

        if (player.getInventory().addItem(wand).isEmpty()) {
            plugin.sendActions("plant-wand-received", player, Map.of());
        } else {
            plugin.sendActions("plant-wand-inventory-full", player, Map.of());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("onemillion.plantwand")) {
            plugin.sendActions("no-permission", player, Map.of());
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        BlockPosition position = BlockPosition.of(clicked);
        Selection selection = selections.getOrDefault(player.getUniqueId(), new Selection(null, null));
        if (action == Action.LEFT_CLICK_BLOCK) {
            selections.put(player.getUniqueId(), new Selection(position, null));
            showCornerEffect(player, clicked, 0.8f);
            plugin.sendActions("plant-wand-first-corner", player, position.replacements());
            return;
        }

        selection = new Selection(selection.first(), position);
        selections.put(player.getUniqueId(), selection);
        showCornerEffect(player, clicked, 1.25f);
        plugin.sendActions("plant-wand-second-corner", player, position.replacements());
        openCropMenu(player, selection);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof PlantWandGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId().equals(player.getUniqueId())
                || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        String cropId = clicked.getItemMeta().getPersistentDataContainer()
                .get(cropKey, PersistentDataType.STRING);
        PlantableCrop crop = crop(cropId);
        if (crop == null) {
            return;
        }
        player.closeInventory();
        plant(player, crop);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PlantWandGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.WOODEN_HOE
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private void openCropMenu(Player player, Selection selection) {
        SelectionCheck check = validate(player, selection);
        if (check == null) {
            return;
        }
        List<Block> farmland = findFarmland(check.world(), selection);
        if (farmland.isEmpty()) {
            plugin.sendActions("plant-wand-no-farmland", player, Map.of());
            return;
        }

        PlantWandGuiHolder holder = new PlantWandGuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27,
                plugin.text().parse("<gradient:#8CE99A:#FFD166><bold>Choose a Crop</bold></gradient>"));
        holder.inventory(inventory);
        ItemStack border = item(Material.LIME_STAINED_GLASS_PANE, "<dark_gray>✦</dark_gray>", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, border);
        }
        for (int index = 0; index < CROPS.size(); index++) {
            PlantableCrop crop = CROPS.get(index);
            int available = available(player, crop.seed());
            String availableText = player.getGameMode() == GameMode.CREATIVE
                    ? "<light_purple>Unlimited</light_purple>"
                    : "<white>" + Text.number(available) + "</white>";
            String action = available > 0
                    ? "<#8CE99A>Left-click</#8CE99A> <gray>to plant this crop.</gray>"
                    : "<red>You do not have this crop.</red>";
            ItemStack option = item(crop.seed(), crop.display(), lore("gui.plant-wand.crop-option", Map.of(
                    "farmland", Text.number(farmland.size()),
                    "available", availableText,
                    "action", action
            )));
            ItemMeta meta = option.getItemMeta();
            meta.getPersistentDataContainer().set(cropKey, PersistentDataType.STRING, crop.id());
            option.setItemMeta(meta);
            inventory.setItem(CROP_SLOTS[index], option);
        }
        inventory.setItem(4, item(Material.GOLDEN_HOE, "<#8CE99A><bold>" + Text.number(farmland.size())
                + " Empty Farmland</bold></#8CE99A>", lore("gui.plant-wand.menu-summary", Map.of(
                "maximum", Text.number(MAX_PLANTS_PER_USE)
        ))));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.65f, 1.35f);
    }

    private void plant(Player player, PlantableCrop crop) {
        Selection selection = selections.get(player.getUniqueId());
        SelectionCheck check = validate(player, selection);
        if (check == null) {
            return;
        }
        int available = available(player, crop.seed());
        if (available <= 0) {
            plugin.sendActions("plant-wand-no-crop", player,
                    Map.of("crop", crop.display(), "item", Text.escape(pretty(crop.seed()))));
            return;
        }

        List<Block> farmland = findFarmland(check.world(), selection);
        if (farmland.isEmpty()) {
            plugin.sendActions("plant-wand-no-farmland", player, Map.of());
            return;
        }
        int limit = Math.min(Math.min(available, farmland.size()), MAX_PLANTS_PER_USE);
        List<Location> planted = new ArrayList<>(limit);
        ItemStack placementItem = new ItemStack(crop.seed());
        for (Block soil : farmland) {
            if (planted.size() >= limit) {
                break;
            }
            Block target = soil.getRelative(BlockFace.UP);
            if (!target.isEmpty()) {
                continue;
            }
            BlockState replaced = target.getState();
            target.setType(crop.block(), false);
            BlockPlaceEvent placeEvent = new BlockPlaceEvent(target, replaced, soil, placementItem,
                    player, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(placeEvent);
            if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
                replaced.update(true, false);
                continue;
            }
            planted.add(target.getLocation().add(0.5, 0.35, 0.5));
        }

        if (planted.isEmpty()) {
            plugin.sendActions("plant-wand-plant-blocked", player, Map.of());
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            consume(player.getInventory(), crop.seed(), planted.size());
        }
        plugin.sendActions("plant-wand-planted", player, Map.of(
                "amount", Text.number(planted.size()),
                "farmland", Text.number(farmland.size()),
                "crop", crop.display()
        ));
        playPlantEffects(player, planted);
    }

    private SelectionCheck validate(Player player, Selection selection) {
        if (selection == null || selection.first() == null) {
            plugin.sendActions("plant-wand-first-needed", player, Map.of());
            return null;
        }
        if (selection.second() == null) {
            plugin.sendActions("plant-wand-second-needed", player, Map.of());
            return null;
        }
        if (!selection.first().worldId().equals(selection.second().worldId())) {
            plugin.sendActions("plant-wand-different-worlds", player, Map.of());
            return null;
        }
        World world = Bukkit.getWorld(selection.first().worldId());
        if (world == null || !world.equals(player.getWorld())) {
            plugin.sendActions("plant-wand-different-worlds", player, Map.of());
            return null;
        }
        long volume = selectionVolume(selection.first(), selection.second());
        if (volume > MAX_SELECTION_VOLUME) {
            plugin.sendActions("plant-wand-too-large", player, Map.of(
                    "volume", Text.number(volume),
                    "maximum", Text.number(MAX_SELECTION_VOLUME)
            ));
            return null;
        }
        return new SelectionCheck(world);
    }

    private List<Block> findFarmland(World world, Selection selection) {
        int minX = Math.min(selection.first().x(), selection.second().x());
        int maxX = Math.max(selection.first().x(), selection.second().x());
        int minY = Math.min(selection.first().y(), selection.second().y());
        int maxY = Math.max(selection.first().y(), selection.second().y());
        int minZ = Math.min(selection.first().z(), selection.second().z());
        int maxZ = Math.max(selection.first().z(), selection.second().z());
        List<Block> farmland = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block soil = world.getBlockAt(x, y, z);
                    if (soil.getType() == Material.FARMLAND && soil.getRelative(BlockFace.UP).isEmpty()) {
                        farmland.add(soil);
                    }
                }
            }
        }
        return farmland;
    }

    private int available(Player player, Material material) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return MAX_PLANTS_PER_USE;
        }
        int amount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                amount = Math.min(MAX_PLANTS_PER_USE, amount + stack.getAmount());
            }
        }
        return amount;
    }

    static int consume(PlayerInventory inventory, Material material, int requested) {
        int remaining = Math.max(0, requested);
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
        }
        return requested - remaining;
    }

    private void showCornerEffect(Player player, Block block, float pitch) {
        Location center = block.getLocation().add(0.5, 1.1, 0.5);
        player.spawnParticle(Particle.END_ROD, center, 14, 0.45, 0.45, 0.45, 0.02);
        player.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.65f, pitch);
    }

    private void playPlantEffects(Player player, List<Location> planted) {
        int stride = effectStride(planted.size(), MAX_EFFECT_BLOCKS);
        List<Location> effects = new ArrayList<>();
        for (int index = 0; index < planted.size(); index += stride) {
            effects.add(planted.get(index));
        }
        for (int start = 0; start < effects.size(); start += 24) {
            int from = start;
            int to = Math.min(effects.size(), start + 24);
            long delay = start / 24L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int index = from; index < to; index++) {
                    Location location = effects.get(index);
                    location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location,
                            2, 0.3, 0.2, 0.3, 0.02);
                    location.getWorld().spawnParticle(Particle.COMPOSTER, location,
                            3, 0.35, 0.15, 0.35, 0.01);
                }
            }, delay);
        }
        Location soundLocation = planted.get(planted.size() / 2);
        soundLocation.getWorld().playSound(soundLocation, Sound.BLOCK_GROWING_PLANT_CROP, 0.9f, 1.1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.45f, 1.8f);
    }

    private ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.text().parse(name));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(String key, Map<String, String> replacements) {
        return plugin.configManager().lore(key, replacements).stream()
                .map(plugin.text()::parse)
                .toList();
    }

    static PlantableCrop crop(String id) {
        if (id == null) {
            return null;
        }
        return CROPS.stream().filter(crop -> crop.id().equals(id)).findFirst().orElse(null);
    }

    static long selectionVolume(BlockPosition first, BlockPosition second) {
        long x = Math.abs((long) first.x() - second.x()) + 1L;
        long y = Math.abs((long) first.y() - second.y()) + 1L;
        long z = Math.abs((long) first.z() - second.z()) + 1L;
        return saturatingMultiply(saturatingMultiply(x, y), z);
    }

    static int effectStride(int blocks, int maximumEffects) {
        if (blocks <= 0 || maximumEffects <= 0) {
            return 1;
        }
        return Math.max(1, (blocks + maximumEffects - 1) / maximumEffects);
    }

    private static long saturatingMultiply(long left, long right) {
        return left > 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
    }

    private static String pretty(Material material) {
        String[] words = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        for (int index = 0; index < words.length; index++) {
            words[index] = Character.toUpperCase(words[index].charAt(0)) + words[index].substring(1);
        }
        return String.join(" ", words);
    }

    record PlantableCrop(String id, Material seed, Material block, String display) {
    }

    record BlockPosition(UUID worldId, int x, int y, int z) {
        static BlockPosition of(Block block) {
            return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        Map<String, String> replacements() {
            return Map.of(
                    "x", Integer.toString(x),
                    "y", Integer.toString(y),
                    "z", Integer.toString(z)
            );
        }
    }

    private record Selection(BlockPosition first, BlockPosition second) {
    }

    private record SelectionCheck(World world) {
    }
}
