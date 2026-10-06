package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CropWandListener implements Listener {
    private final OneMillionCropsPlugin plugin;
    private final NamespacedKey wandKey;
    private final CropMarkers markers;

    public CropWandListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "crop_wand");
        this.markers = new CropMarkers(plugin);
    }

    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(plugin.text().parse("<#8CE99A><bold>Crop Wand</bold></#8CE99A>"));
        meta.lore(plugin.configManager().lore("gui.crop-wand.lore").stream()
                .map(plugin.text()::parse)
                .toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);

        if (player.getInventory().addItem(wand).isEmpty()) {
            plugin.sendActions("crop-wand-received", player, Map.of());
        } else {
            plugin.sendActions("crop-wand-inventory-full", player, Map.of());
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
        Player player = event.getPlayer();
        if (!player.hasPermission("onemillion.wand")) {
            event.setCancelled(true);
            plugin.sendActions("no-permission", player, Map.of());
            return;
        }
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Container container) || !isSupportedContainer(state)) {
            event.setCancelled(true);
            plugin.sendActions("crop-wand-container-only", player, Map.of());
            return;
        }
        event.setCancelled(true);
        if (!plugin.configManager().settings().mayContribute(player.getUniqueId())) {
            plugin.sendActions("no-permission", player, Map.of());
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            inspect(player, container.getInventory());
        } else {
            deposit(player, container.getInventory());
            if (player.isSneaking()) {
                removeSeeds(player, container.getInventory());
            }
        }
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD
                && item.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private boolean isSupportedContainer(BlockState state) {
        return state instanceof Chest || state instanceof Barrel || state instanceof Hopper
                || state instanceof ShulkerBox;
    }

    private void inspect(Player player, Inventory inventory) {
        StorageContents contents = scan(inventory);
        if (contents.eligible().isEmpty()) {
            plugin.sendActions("crop-wand-inspect-empty", player, Map.of());
        } else {
            plugin.sendActions("crop-wand-inspect-header", player,
                    Map.of("total", Text.number(total(contents.eligible()))));
            sendCropLines(player, "crop-wand-inspect-line", contents.eligible());
        }
        sendIgnored(player, contents.ignored());
    }

    private void deposit(Player player, Inventory inventory) {
        StorageContents contents = scan(inventory);
        Map<CropDefinition, Integer> deposited = new LinkedHashMap<>();
        for (Map.Entry<CropDefinition, Integer> entry : contents.eligible().entrySet()) {
            CropDefinition crop = entry.getKey();
            int requested = depositLimit(entry.getValue(), plugin.progress().amount(crop.id()),
                    plugin.progress().target());
            if (requested <= 0) {
                continue;
            }
            List<RemovedStack> removed = removeEligible(inventory, crop, requested);
            int removedAmount = removed.stream().mapToInt(RemovedStack::amount).sum();
            int accepted = (int) plugin.recordPickup(player, crop, removedAmount);
            if (accepted < removedAmount) {
                restore(inventory, removed, removedAmount - accepted);
            }
            if (accepted > 0) {
                deposited.put(crop, accepted);
            }
        }

        if (deposited.isEmpty()) {
            plugin.sendActions("crop-wand-deposit-empty", player, Map.of());
        } else {
            plugin.sendActions("crop-wand-deposit-header", player,
                    Map.of("total", Text.number(total(deposited))));
            sendCropLines(player, "crop-wand-deposit-line", deposited);
        }
        sendIgnored(player, contents.ignored());
    }

    private void removeSeeds(Player player, Inventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !isSeed(stack.getType())) {
                continue;
            }
            removed += stack.getAmount();
            inventory.setItem(slot, null);
        }

        if (removed > 0) {
            plugin.sendActions("crop-wand-seeds-removed", player,
                    Map.of("amount", Text.number(removed)));
        } else {
            plugin.sendActions("crop-wand-seeds-empty", player, Map.of());
        }
    }

    private StorageContents scan(Inventory inventory) {
        Map<CropDefinition, Integer> eligible = new LinkedHashMap<>();
        int ignored = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            CropDefinition materialCrop = plugin.configManager().cropByItem(stack.getType());
            if (materialCrop == null) {
                continue;
            }
            CropDefinition eligibleCrop = eligibleCrop(stack);
            if (eligibleCrop == null) {
                ignored += stack.getAmount();
            } else {
                eligible.merge(eligibleCrop, stack.getAmount(), Integer::sum);
            }
        }
        return new StorageContents(eligible, ignored);
    }

    private CropDefinition eligibleCrop(ItemStack stack) {
        CropDefinition crop = plugin.configManager().crop(markers.read(stack).creditableCropId());
        return crop != null && crop.item() == stack.getType() ? crop : null;
    }

    private List<RemovedStack> removeEligible(Inventory inventory, CropDefinition crop, int amount) {
        List<RemovedStack> removed = new ArrayList<>();
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !crop.equals(eligibleCrop(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            ItemStack original = stack.clone();
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() == 0 ? null : stack);
            removed.add(new RemovedStack(slot, original, take));
            remaining -= take;
        }
        return removed;
    }

    private void restore(Inventory inventory, List<RemovedStack> removed, int amount) {
        int remaining = amount;
        for (int index = removed.size() - 1; index >= 0 && remaining > 0; index--) {
            RemovedStack removal = removed.get(index);
            int restore = Math.min(remaining, removal.amount());
            ItemStack current = inventory.getItem(removal.slot());
            if (current == null) {
                ItemStack restored = removal.original().clone();
                restored.setAmount(restore);
                inventory.setItem(removal.slot(), restored);
            } else {
                current.setAmount(current.getAmount() + restore);
                inventory.setItem(removal.slot(), current);
            }
            remaining -= restore;
        }
    }

    private void sendCropLines(Player player, String event, Map<CropDefinition, Integer> amounts) {
        for (Map.Entry<CropDefinition, Integer> entry : amounts.entrySet()) {
            plugin.sendActions(event, player, Map.of(
                    "crop", entry.getKey().displayMiniMessage(),
                    "amount", Text.number(entry.getValue())
            ));
        }
    }

    private void sendIgnored(Player player, int ignored) {
        if (ignored > 0) {
            plugin.sendActions("crop-wand-ignored", player, Map.of("amount", Text.number(ignored)));
        }
    }

    private static long total(Map<CropDefinition, Integer> amounts) {
        return amounts.values().stream().mapToLong(Integer::longValue).sum();
    }

    static int depositLimit(int available, long current, long target) {
        return (int) Math.min(Math.max(0, available), Math.max(0L, target - current));
    }

    static boolean isSeed(Material material) {
        return material != null && material.name().endsWith("_SEEDS");
    }

    private record StorageContents(Map<CropDefinition, Integer> eligible, int ignored) {
    }

    private record RemovedStack(int slot, ItemStack original, int amount) {
    }
}
