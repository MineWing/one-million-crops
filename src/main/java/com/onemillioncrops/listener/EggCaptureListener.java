package com.onemillioncrops.listener;

import com.onemillioncrops.OneMillionCropsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

import java.util.List;

/** Snapshot-based mob capture adapted from Rivet's EggCapture. */
public final class EggCaptureListener implements Listener {
    private final OneMillionCropsPlugin plugin;

    public EggCaptureListener(OneMillionCropsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEggHit(ProjectileHitEvent event) {
        if (!plugin.configManager().utilityFeatures().eggCapture()
                || !(event.getEntity() instanceof Egg egg)
                || !(egg.getShooter() instanceof Player player)
                || !(event.getHitEntity() instanceof Mob mob)
                || !plugin.getServer().isOwnedByCurrentRegion(mob)
                || !plugin.getServer().isOwnedByCurrentRegion(player)
                || !player.hasPermission("onemillion.eggcapture")
                || !mob.isValid() || mob.isDead() || mob.isInvulnerable()
                || mob.hasMetadata("NPC") || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) return;

        Material material = plugin.getServer().getItemFactory().getSpawnEgg(mob.getType());
        EntitySnapshot snapshot = mob.createSnapshot();
        if (material == null || snapshot == null) return;
        ItemStack capturedEgg = new ItemStack(material);
        if (!(capturedEgg.getItemMeta() instanceof SpawnEggMeta meta)) return;
        describeCapture(meta, snapshot, mob.name());
        capturedEgg.setItemMeta(meta);

        // Finish within this region tick. No frozen mob or delayed inventory transfer survives shutdown.
        // Spawn first so another plugin cancelling item spawns cannot erase the creature.
        Item drop = mob.getWorld().dropItem(mob.getLocation(), capturedEgg);
        if (!drop.isValid()) return;
        event.setCancelled(true);
        egg.remove();
        var location = mob.getLocation();
        mob.remove();
        location.getWorld().spawnParticle(Particle.REVERSE_PORTAL, location.clone().add(0, .5, 0),
                30, .5, .5, .5, .1);
        location.getWorld().playSound(location, Sound.ENTITY_ALLAY_ITEM_GIVEN, .8f, 1.3f);
    }
    static void describeCapture(SpawnEggMeta meta, EntitySnapshot snapshot, Component name) {
        meta.setSpawnedEntity(snapshot);
        meta.displayName(Component.text("Captured ", NamedTextColor.LIGHT_PURPLE).append(name));
        meta.lore(List.of(Component.text("Contains the original creature.", NamedTextColor.GRAY)));
    }

}
