package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.gui.CropToggleGuiHolder;
import com.onemillioncrops.gui.ProgressGuiHolder;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Numbers;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class GuiService {
    private static final int[] CROP_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int[] BORDER_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 17, 18, 26, 27, 35, 36, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53
    };
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 48;
    private static final int SUMMARY_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final Material[] ANIMATION = {
            Material.LIME_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE
    };

    private final OneMillionCropsPlugin plugin;
    private final ItemStack[] borderPanes;
    private BukkitTask animationTask;
    private int animationFrame;

    public GuiService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.borderPanes = new ItemStack[ANIMATION.length];
        for (int index = 0; index < ANIMATION.length; index++) {
            borderPanes[index] = item(ANIMATION[index], "<dark_gray>✦</dark_gray>", null, false);
        }
    }

    public void start() {
        stop();
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            animationFrame++;
            for (Player player : Bukkit.getOnlinePlayers()) {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder(false);
                if (holder instanceof ProgressGuiHolder || holder instanceof CropToggleGuiHolder) {
                    animateBorder(holder.getInventory());
                }
            }
        }, 1L, plugin.configManager().settings().guiAnimationTicks());
    }

    public void open(Player player, int requestedPage) {
        List<CropDefinition> crops = new ArrayList<>(plugin.progress().crops().values());
        int pages = pageCount(crops.size());
        int page = Math.clamp(requestedPage, 0, pages - 1);
        ProgressGuiHolder holder = new ProgressGuiHolder(page);
        Inventory inventory = createInventory(holder, "One Million Crops", page, pages);
        holder.inventory(inventory);

        fillCropSlots(inventory, crops, page, crop -> cropItem(player, crop));
        addNavigation(inventory, page, pages);
        inventory.setItem(SUMMARY_SLOT, overallItem());
        show(player, inventory);
    }

    public void openCrop(Player player, String cropId) {
        int index = 0;
        for (CropDefinition crop : plugin.progress().crops().values()) {
            if (crop.id().equals(cropId)) {
                open(player, index / CROP_SLOTS.length);
                return;
            }
            index++;
        }
        open(player, 0);
    }

    public void openCropToggles(Player player, int requestedPage) {
        List<CropDefinition> crops = new ArrayList<>(plugin.configManager().configuredCrops().values());
        int pages = pageCount(crops.size());
        int page = Math.clamp(requestedPage, 0, pages - 1);
        CropToggleGuiHolder holder = new CropToggleGuiHolder(page);
        Inventory inventory = createInventory(holder, "Crop Toggles", page, pages);
        holder.inventory(inventory);

        fillCropSlots(inventory, crops, page, this::cropToggleItem);
        addNavigation(inventory, page, pages);
        inventory.setItem(SUMMARY_SLOT, toggleSummaryItem());
        show(player, inventory);
    }

    public void refreshOpen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder(false);
            if (holder instanceof ProgressGuiHolder progressHolder) {
                refreshInventory(player, progressHolder);
            } else if (holder instanceof CropToggleGuiHolder toggleHolder) {
                refreshToggleInventory(toggleHolder);
            }
        }
    }

    private void refreshInventory(Player player, ProgressGuiHolder holder) {
        Inventory inventory = holder.getInventory();
        clearCropSlots(inventory);
        fillCropSlots(inventory, new ArrayList<>(plugin.progress().crops().values()), holder.page(),
                crop -> cropItem(player, crop));
        inventory.setItem(SUMMARY_SLOT, overallItem());
    }

    private void refreshToggleInventory(CropToggleGuiHolder holder) {
        Inventory inventory = holder.getInventory();
        clearCropSlots(inventory);
        fillCropSlots(inventory, new ArrayList<>(plugin.configManager().configuredCrops().values()),
                holder.page(), this::cropToggleItem);
        inventory.setItem(SUMMARY_SLOT, toggleSummaryItem());
    }

    public void handleClick(Player player, int rawSlot, ProgressGuiHolder holder) {
        if (rawSlot == PREVIOUS_SLOT && holder.page() > 0) {
            open(player, holder.page() - 1);
        } else if (rawSlot == NEXT_SLOT) {
            open(player, holder.page() + 1);
        } else if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    public void handleToggleClick(Player player, int rawSlot, CropToggleGuiHolder holder) {
        if (rawSlot == PREVIOUS_SLOT && holder.page() > 0) {
            openCropToggles(player, holder.page() - 1);
            return;
        }
        if (rawSlot == NEXT_SLOT) {
            openCropToggles(player, holder.page() + 1);
            return;
        }
        if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        int pageSlot = cropSlotIndex(rawSlot);
        if (pageSlot < 0) {
            return;
        }
        List<CropDefinition> crops = new ArrayList<>(plugin.configManager().configuredCrops().values());
        int cropIndex = holder.page() * CROP_SLOTS.length + pageSlot;
        if (cropIndex < crops.size()) {
            plugin.toggleCrop(player, crops.get(cropIndex).id());
        }
    }

    private Inventory createInventory(InventoryHolder holder, String title, int page, int pages) {
        return Bukkit.createInventory(holder, 54, plugin.text().parse(
                "<gradient:#55ff55:#ffd54a><bold>" + title + "</bold></gradient> <dark_gray>•</dark_gray> <gray>" +
                        (page + 1) + "/" + pages));
    }

    private void addNavigation(Inventory inventory, int page, int pages) {
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, simpleItem(Material.ARROW, "<yellow><bold>Previous Page</bold>",
                    "gui.navigation.previous"));
        }
        if (page + 1 < pages) {
            inventory.setItem(NEXT_SLOT, simpleItem(Material.ARROW, "<yellow><bold>Next Page</bold>",
                    "gui.navigation.next"));
        }
        inventory.setItem(CLOSE_SLOT, simpleItem(Material.BARRIER, "<red><bold>Close</bold>",
                "gui.navigation.close"));
    }

    private void show(Player player, Inventory inventory) {
        animateBorder(inventory);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.4f);
    }

    private static int pageCount(int crops) {
        return Math.max(1, (crops + CROP_SLOTS.length - 1) / CROP_SLOTS.length);
    }

    private static void fillCropSlots(Inventory inventory, List<CropDefinition> crops, int page,
                                      Function<CropDefinition, ItemStack> item) {
        int start = page * CROP_SLOTS.length;
        for (int index = start; index < Math.min(crops.size(), start + CROP_SLOTS.length); index++) {
            inventory.setItem(CROP_SLOTS[index - start], item.apply(crops.get(index)));
        }
    }

    private static void clearCropSlots(Inventory inventory) {
        for (int slot : CROP_SLOTS) {
            inventory.setItem(slot, null);
        }
    }

    private static int cropSlotIndex(int rawSlot) {
        for (int index = 0; index < CROP_SLOTS.length; index++) {
            if (CROP_SLOTS[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    private ItemStack cropToggleItem(CropDefinition crop) {
        boolean enabled = plugin.configManager().isCropEnabled(crop.id());
        return item(crop.item(), crop.displayMiniMessage(), lore("gui.crop-toggle.crop", Map.of(
                "status", enabled
                        ? "<green><bold>✔ ENABLED</bold></green>"
                        : "<red><bold>✘ DISABLED</bold></red>",
                "action", enabled ? "stop counting" : "start counting"
        )), enabled);
    }

    private ItemStack toggleSummaryItem() {
        int configured = plugin.configManager().configuredCrops().size();
        int enabled = plugin.configManager().crops().size();
        return item(Material.COMPARATOR, "<gradient:#55ff55:#ffd54a><bold>Crop Controls</bold></gradient>",
                lore("gui.crop-toggle.summary", Map.of(
                        "enabled", Integer.toString(enabled),
                        "configured", Integer.toString(configured)
                )), false);
    }

    private ItemStack cropItem(Player player, CropDefinition crop) {
        long amount = plugin.progress().amount(crop.id());
        long target = plugin.progress().target();
        long own = plugin.progress().contribution(player.getUniqueId(), crop.id());
        boolean done = amount >= target;
        return item(crop.item(), crop.displayMiniMessage(), lore("gui.progress.crop", Map.of(
                "bar", Text.progressBar(amount, target, 20),
                "amount", Text.number(amount),
                "target", Text.number(target),
                "percent", Text.percent(amount, target),
                "remaining", Text.number(Math.max(0, target - amount)),
                "contribution", Text.number(own),
                "status", done
                        ? "<gradient:#55ff55:#ffd54a><bold>✦ CHALLENGE COMPLETE ✦</bold></gradient>"
                        : "<dark_gray>│ Every collected item counts as one.</dark_gray>"
        )), done);
    }

    private ItemStack overallItem() {
        int totalCrops = plugin.progress().crops().size();
        int completed = plugin.progress().completedCount();
        long target = Numbers.saturatingMultiply(plugin.progress().target(), totalCrops);
        long amount = plugin.progress().crops().keySet().stream().mapToLong(plugin.progress()::amount)
                .reduce(0L, Numbers::saturatingAdd);
        return item(Material.NETHER_STAR, "<gradient:#55ff55:#ffd54a><bold>Team Progress</bold></gradient>",
                lore("gui.progress.overall", Map.of(
                        "bar", Text.progressBar(amount, target, 20),
                        "percent", Text.percent(amount, target),
                        "completed", Integer.toString(completed),
                        "total", Integer.toString(totalCrops)
                )), false);
    }

    private ItemStack simpleItem(Material material, String name, String loreKey) {
        return item(material, name, lore(loreKey, Map.of()), false);
    }

    private ItemStack item(Material material, String name, List<Component> lore, boolean glowing) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.text().parse(name));
        if (lore != null) {
            meta.lore(lore);
        }
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(String key, Map<String, String> replacements) {
        return plugin.configManager().lore(key, replacements).stream()
                .map(plugin.text()::parse)
                .toList();
    }

    private void animateBorder(Inventory inventory) {
        for (int index = 0; index < BORDER_SLOTS.length; index++) {
            int slot = BORDER_SLOTS[index];
            if (slot == PREVIOUS_SLOT || slot == CLOSE_SLOT || slot == SUMMARY_SLOT || slot == NEXT_SLOT) {
                continue;
            }
            inventory.setItem(slot, borderPanes[(animationFrame + index / 3) % borderPanes.length]);
        }
    }

    public void stop() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
    }
}
