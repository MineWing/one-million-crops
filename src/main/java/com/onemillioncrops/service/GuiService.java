package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.gui.CropToggleGuiHolder;
import com.onemillioncrops.gui.ProgressGuiHolder;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private static final Material[] ANIMATION = {
            Material.LIME_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE
    };

    private final OneMillionCropsPlugin plugin;
    private BukkitTask animationTask;
    private int animationFrame;

    public GuiService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            animationFrame++;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ProgressGuiHolder holder) {
                    animateBorder(holder.getInventory());
                } else if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof CropToggleGuiHolder holder) {
                    animateBorder(holder.getInventory());
                }
            }
        }, 1L, plugin.configManager().settings().guiAnimationTicks());
    }

    public void open(Player player, int requestedPage) {
        List<CropDefinition> crops = new ArrayList<>(plugin.progress().crops().values());
        int pages = Math.max(1, (crops.size() + CROP_SLOTS.length - 1) / CROP_SLOTS.length);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        ProgressGuiHolder holder = new ProgressGuiHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.text().parse(
                "<gradient:#55ff55:#ffd54a><bold>One Million Crops</bold></gradient> <dark_gray>•</dark_gray> <gray>" +
                        (page + 1) + "/" + pages));
        holder.inventory(inventory);

        int start = page * CROP_SLOTS.length;
        for (int index = start; index < Math.min(crops.size(), start + CROP_SLOTS.length); index++) {
            inventory.setItem(CROP_SLOTS[index - start], cropItem(player, crops.get(index)));
        }
        if (page > 0) {
            inventory.setItem(45, simpleItem(Material.ARROW, "<yellow><bold>Previous Page</bold>",
                    "gui.navigation.previous"));
        }
        if (page + 1 < pages) {
            inventory.setItem(53, simpleItem(Material.ARROW, "<yellow><bold>Next Page</bold>",
                    "gui.navigation.next"));
        }
        inventory.setItem(49, overallItem());
        inventory.setItem(48, simpleItem(Material.BARRIER, "<red><bold>Close</bold>",
                "gui.navigation.close"));
        animateBorder(inventory);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.4f);
    }

    public void openCrop(Player player, String cropId) {
        List<CropDefinition> crops = new ArrayList<>(plugin.progress().crops().values());
        int index = -1;
        for (int current = 0; current < crops.size(); current++) {
            if (crops.get(current).id().equals(cropId)) {
                index = current;
                break;
            }
        }
        open(player, Math.max(0, index) / CROP_SLOTS.length);
    }

    public void openCropToggles(Player player, int requestedPage) {
        List<CropDefinition> crops = new ArrayList<>(plugin.configManager().configuredCrops().values());
        int pages = Math.max(1, (crops.size() + CROP_SLOTS.length - 1) / CROP_SLOTS.length);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        CropToggleGuiHolder holder = new CropToggleGuiHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.text().parse(
                "<gradient:#55ff55:#ffd54a><bold>Crop Toggles</bold></gradient> <dark_gray>•</dark_gray> <gray>" +
                        (page + 1) + "/" + pages));
        holder.inventory(inventory);

        populateCropToggles(inventory, crops, page);
        if (page > 0) {
            inventory.setItem(45, simpleItem(Material.ARROW, "<yellow><bold>Previous Page</bold>",
                    "gui.navigation.previous"));
        }
        if (page + 1 < pages) {
            inventory.setItem(53, simpleItem(Material.ARROW, "<yellow><bold>Next Page</bold>",
                    "gui.navigation.next"));
        }
        inventory.setItem(48, simpleItem(Material.BARRIER, "<red><bold>Close</bold>",
                "gui.navigation.close"));
        inventory.setItem(49, toggleSummaryItem());
        animateBorder(inventory);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.4f);
    }

    public void refreshOpen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ProgressGuiHolder holder) {
                refreshInventory(player, holder);
            } else if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof CropToggleGuiHolder holder) {
                refreshToggleInventory(holder);
            }
        }
    }

    private void refreshInventory(Player player, ProgressGuiHolder holder) {
        Inventory inventory = holder.getInventory();
        for (int slot : CROP_SLOTS) {
            inventory.setItem(slot, null);
        }
        List<CropDefinition> crops = new ArrayList<>(plugin.progress().crops().values());
        int start = holder.page() * CROP_SLOTS.length;
        for (int index = start; index < Math.min(crops.size(), start + CROP_SLOTS.length); index++) {
            inventory.setItem(CROP_SLOTS[index - start], cropItem(player, crops.get(index)));
        }
        inventory.setItem(49, overallItem());
    }

    public void handleClick(Player player, int rawSlot, ProgressGuiHolder holder) {
        if (rawSlot == 45 && holder.page() > 0) {
            open(player, holder.page() - 1);
        } else if (rawSlot == 53) {
            open(player, holder.page() + 1);
        } else if (rawSlot == 48) {
            player.closeInventory();
        }
    }

    public void handleToggleClick(Player player, int rawSlot, CropToggleGuiHolder holder) {
        if (rawSlot == 45 && holder.page() > 0) {
            openCropToggles(player, holder.page() - 1);
            return;
        }
        if (rawSlot == 53) {
            openCropToggles(player, holder.page() + 1);
            return;
        }
        if (rawSlot == 48) {
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

    private void refreshToggleInventory(CropToggleGuiHolder holder) {
        Inventory inventory = holder.getInventory();
        for (int slot : CROP_SLOTS) {
            inventory.setItem(slot, null);
        }
        populateCropToggles(inventory,
                new ArrayList<>(plugin.configManager().configuredCrops().values()), holder.page());
        inventory.setItem(49, toggleSummaryItem());
    }

    private void populateCropToggles(Inventory inventory, List<CropDefinition> crops, int page) {
        int start = page * CROP_SLOTS.length;
        for (int index = start; index < Math.min(crops.size(), start + CROP_SLOTS.length); index++) {
            inventory.setItem(CROP_SLOTS[index - start], cropToggleItem(crops.get(index)));
        }
    }

    private ItemStack cropToggleItem(CropDefinition crop) {
        boolean enabled = plugin.configManager().isCropEnabled(crop.id());
        ItemStack stack = new ItemStack(crop.item());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text().parse(crop.displayMiniMessage()));
        meta.lore(lore("gui.crop-toggle.crop", Map.of(
                "status", enabled
                        ? "<green><bold>✔ ENABLED</bold></green>"
                        : "<red><bold>✘ DISABLED</bold></red>",
                "action", enabled ? "stop counting" : "start counting"
        )));
        if (enabled) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack toggleSummaryItem() {
        int configured = plugin.configManager().configuredCrops().size();
        int enabled = plugin.configManager().crops().size();
        ItemStack item = simpleItem(Material.COMPARATOR,
                "<gradient:#55ff55:#ffd54a><bold>Crop Controls</bold></gradient>");
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore("gui.crop-toggle.summary", Map.of(
                "enabled", Integer.toString(enabled),
                "configured", Integer.toString(configured)
        )));
        item.setItemMeta(meta);
        return item;
    }

    private static int cropSlotIndex(int rawSlot) {
        for (int index = 0; index < CROP_SLOTS.length; index++) {
            if (CROP_SLOTS[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    private ItemStack cropItem(Player player, CropDefinition crop) {
        long amount = plugin.progress().amount(crop.id());
        long target = plugin.progress().target();
        long own = plugin.progress().contribution(player.getUniqueId(), crop.id());
        boolean done = amount >= target;
        ItemStack stack = new ItemStack(crop.item());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text().parse(crop.displayMiniMessage()));
        meta.lore(lore("gui.progress.crop", Map.of(
                "bar", Text.progressBar(amount, target, 20),
                "amount", Text.number(amount),
                "target", Text.number(target),
                "percent", Text.percent(amount, target),
                "remaining", Text.number(Math.max(0, target - amount)),
                "contribution", Text.number(own),
                "status", done
                        ? "<gradient:#55ff55:#ffd54a><bold>✦ CHALLENGE COMPLETE ✦</bold></gradient>"
                        : "<dark_gray>│ Every collected item counts as one.</dark_gray>"
        )));
        if (done) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack overallItem() {
        int totalCrops = plugin.progress().crops().size();
        int completed = plugin.progress().completedCount();
        long target = saturatingMultiply(plugin.progress().target(), totalCrops);
        long amount = plugin.progress().crops().keySet().stream().mapToLong(plugin.progress()::amount)
                .reduce(0L, GuiService::saturatingAdd);
        ItemStack item = simpleItem(Material.NETHER_STAR, "<gradient:#55ff55:#ffd54a><bold>Team Progress</bold></gradient>");
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore("gui.progress.overall", Map.of(
                "bar", Text.progressBar(amount, target, 20),
                "percent", Text.percent(amount, target),
                "completed", Integer.toString(completed),
                "total", Integer.toString(totalCrops)
        )));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simpleItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.text().parse(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simpleItem(Material material, String name, String loreKey) {
        ItemStack item = simpleItem(material, name);
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore(loreKey, Map.of()));
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
            if (slot == 45 || slot == 48 || slot == 49 || slot == 53) {
                continue;
            }
            Material material = ANIMATION[(animationFrame + index / 3) % ANIMATION.length];
            ItemStack pane = simpleItem(material, "<dark_gray>✦</dark_gray>");
            inventory.setItem(slot, pane);
        }
    }

    public void stop() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
    }

    private static long saturatingMultiply(long value, int multiplier) {
        return multiplier > 0 && value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
