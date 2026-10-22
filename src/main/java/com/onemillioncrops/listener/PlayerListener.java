package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.gui.CropToggleGuiHolder;
import com.onemillioncrops.gui.ProgressGuiHolder;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {
    private final OneMillionCropsPlugin plugin;

    public PlayerListener(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.scoreboards().showIfEnabled(event.getPlayer());
        plugin.harvestSummary().showCountdown(event.getPlayer());
        plugin.celebrations().playPending(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.scoreboards().remove(event.getPlayer());
        plugin.harvestActionBar().remove(event.getPlayer());
        plugin.harvestSummary().remove(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInsideBerryBush(EntityInsideBlockEvent event) {
        if (shouldCancelBerryBushCollision(plugin.configManager().settings().preventBerryBushDamage(),
                event.getEntity() instanceof Player, event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    static boolean shouldCancelBerryBushCollision(boolean preventDamage, boolean player, Material blockType) {
        return preventDamage && player && blockType == Material.SWEET_BERRY_BUSH;
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        Object holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof ProgressGuiHolder) && !(holder instanceof CropToggleGuiHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            if (holder instanceof ProgressGuiHolder progressHolder) {
                plugin.gui().handleClick(player, event.getRawSlot(), progressHolder);
            } else if (holder instanceof CropToggleGuiHolder toggleHolder
                    && player.hasPermission("onemillion.admin")) {
                plugin.gui().handleToggleClick(player, event.getRawSlot(), toggleHolder);
            }
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof ProgressGuiHolder || holder instanceof CropToggleGuiHolder) {
            event.setCancelled(true);
        }
    }
}
