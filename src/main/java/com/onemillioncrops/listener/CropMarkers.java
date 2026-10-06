package com.onemillioncrops.listener;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Reads and writes the two pickup markers. {@code eligible_crop} names the crop an item
 * may be credited as and {@code blocked_pickup} means it must never count. Item entities
 * carry them in their own data container; stacks inside hoppers and chests carry them in
 * their item data. A stack must never enter a player inventory with a marker still on it,
 * because container clicks credit marked stacks a second time.
 */
final class CropMarkers {
    private final NamespacedKey eligibleKey;
    private final NamespacedKey blockedKey;

    CropMarkers(Plugin plugin) {
        this.eligibleKey = new NamespacedKey(plugin, "eligible_crop");
        this.blockedKey = new NamespacedKey(plugin, "blocked_pickup");
    }

    Marker read(Item item) {
        PersistentDataContainer data = item.getPersistentDataContainer();
        return new Marker(data.has(blockedKey, PersistentDataType.BYTE),
                data.get(eligibleKey, PersistentDataType.STRING));
    }

    Marker read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Marker.NONE;
        }
        PersistentDataContainerView data = stack.getPersistentDataContainer();
        return new Marker(data.has(blockedKey, PersistentDataType.BYTE),
                data.get(eligibleKey, PersistentDataType.STRING));
    }

    /** The entity's own marker, falling back to one still sitting in its stack. */
    Marker effective(Item item) {
        return read(item).combine(read(item.getItemStack()));
    }

    void write(Item item, Marker marker) {
        if (marker.blocked()) {
            markBlocked(item);
        } else if (marker.eligibleId() != null) {
            markEligible(item, marker.eligibleId());
        }
    }

    void markEligible(Item item, String cropId) {
        item.getPersistentDataContainer().remove(blockedKey);
        item.getPersistentDataContainer().set(eligibleKey, PersistentDataType.STRING, cropId);
    }

    void markBlocked(Item item) {
        item.getPersistentDataContainer().remove(eligibleKey);
        item.getPersistentDataContainer().set(blockedKey, PersistentDataType.BYTE, (byte) 1);
    }

    void markEligible(ItemStack stack, String cropId) {
        stack.editPersistentDataContainer(data -> {
            data.remove(blockedKey);
            data.set(eligibleKey, PersistentDataType.STRING, cropId);
        });
    }

    void markBlocked(ItemStack stack) {
        stack.editPersistentDataContainer(data -> {
            data.remove(eligibleKey);
            data.set(blockedKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Strips both markers from the stack and returns what was there. */
    Marker clear(ItemStack stack) {
        Marker marker = read(stack);
        if (marker.isPresent()) {
            stack.editPersistentDataContainer(data -> {
                data.remove(eligibleKey);
                data.remove(blockedKey);
            });
        }
        return marker;
    }

    /**
     * Moves a marker out of the item entity's stack onto the entity itself, so the stack
     * reaches whoever picks it up clean. A blocked marker on either side wins.
     *
     * @return the marker that was moved, or {@link Marker#NONE}
     */
    Marker moveStackMarkerToEntity(Item item) {
        ItemStack stack = item.getItemStack().clone();
        Marker moved = clear(stack);
        if (moved.isPresent()) {
            write(item, read(item).combine(moved));
            item.setItemStack(stack);
        }
        return moved;
    }

    record Marker(boolean blocked, String eligibleId) {
        static final Marker NONE = new Marker(false, null);

        boolean isPresent() {
            return blocked || eligibleId != null;
        }

        /** The crop this marker lets the item count as, or null when it must not count. */
        String creditableCropId() {
            return blocked ? null : eligibleId;
        }

        /** Blocked on either side wins; otherwise this marker's crop is preferred. */
        Marker combine(Marker other) {
            return new Marker(blocked || other.blocked, eligibleId != null ? eligibleId : other.eligibleId);
        }
    }
}
