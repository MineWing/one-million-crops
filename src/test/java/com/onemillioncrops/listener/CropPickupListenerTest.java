package com.onemillioncrops.listener;

import com.onemillioncrops.listener.CropMarkers.Marker;
import com.onemillioncrops.model.CropDefinition;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropPickupListenerTest {
    private static final CropDefinition WHEAT = new CropDefinition("wheat", Material.WHEAT,
            Set.of(Material.WHEAT), "Wheat");
    private static final CropDefinition MELON = new CropDefinition("melon", Material.MELON_SLICE,
            Set.of(Material.MELON), "Melon");
    private static final CropDefinition RED_MUSHROOM = new CropDefinition("red_mushroom", Material.RED_MUSHROOM,
            Set.of(Material.RED_MUSHROOM, Material.RED_MUSHROOM_BLOCK), "Red Mushroom");

    @Test
    void mergesIneligibleItemsWhetherBlockedOrUnmarked() {
        Marker blocked = new Marker(true, null);

        assertTrue(CropPickupListener.mayMerge(blocked, Marker.NONE));
        assertTrue(CropPickupListener.mayMerge(Marker.NONE, blocked));
        assertTrue(CropPickupListener.mayMerge(new Marker(true, "wheat"), Marker.NONE));
    }

    @Test
    void mergesEligibleItemsOnlyWithTheSameEligibleCrop() {
        Marker wheat = new Marker(false, "wheat");

        assertTrue(CropPickupListener.mayMerge(wheat, new Marker(false, "wheat")));
        assertFalse(CropPickupListener.mayMerge(wheat, new Marker(false, "carrot")));
        assertFalse(CropPickupListener.mayMerge(wheat, Marker.NONE));
        assertFalse(CropPickupListener.mayMerge(new Marker(true, null), wheat));
    }

    @Test
    void neverCountsAnUnmarkedCrop() {
        // An allay's throw, a broken chest minecart or a killed donkey all spawn unmarked items.
        assertNull(CropPickupListener.creditableCrop(Material.WHEAT, null));
    }

    @Test
    void countsAnEligibleCropOnlyAsItsOwnItem() {
        assertEquals(WHEAT, CropPickupListener.creditableCrop(Material.WHEAT, WHEAT));
        assertNull(CropPickupListener.creditableCrop(Material.CARROT, WHEAT));
        assertNull(CropPickupListener.creditableCrop(Material.WHEAT, MELON));
    }

    @Test
    void neverCountsABlockedCropEvenWithAnEligibleMarker() {
        Marker blockedAndEligible = new Marker(true, "wheat");

        assertNull(blockedAndEligible.creditableCropId());
    }

    @Test
    void keepsEligibleFarmDropsEligibleInHoppers() {
        assertEquals(new Marker(false, "wheat"), CropPickupListener.hopperStackMarker(Material.WHEAT, WHEAT));
    }

    @Test
    void blocksAnythingIneligibleAHopperPicksUp() {
        Marker blocked = new Marker(true, null);

        // Unmarked items are never made eligible by their material any more.
        assertEquals(blocked, CropPickupListener.hopperStackMarker(Material.WHEAT, null));
        assertEquals(blocked, CropPickupListener.hopperStackMarker(Material.CARROT, WHEAT));
    }

    @Test
    void recordsHarvestsOnlyForExplosionsThatDestroyBlocks() {
        assertTrue(CropPickupListener.destroysBlocks(ExplosionResult.DESTROY));
        assertTrue(CropPickupListener.destroysBlocks(ExplosionResult.DESTROY_WITH_DECAY));
        assertFalse(CropPickupListener.destroysBlocks(ExplosionResult.KEEP));
        assertFalse(CropPickupListener.destroysBlocks(ExplosionResult.TRIGGER_BLOCK));
    }

    @Test
    void blocksCropsTransferredThroughItemFrames() {
        assertTrue(CropPickupListener.shouldBlockItemFrameItem(true, true));
    }

    @Test
    void leavesNonCropsAndExplicitlyAllowedRedropsAlone() {
        assertFalse(CropPickupListener.shouldBlockItemFrameItem(true, false));
        assertFalse(CropPickupListener.shouldBlockItemFrameItem(false, true));
    }

    @Test
    void creditsAPartialHopperLeftoverOnceWhenAPlayerPicksItUp() {
        // The hopper marks the stack before inserting it, so its leftover keeps the marker.
        Marker leftoverStack = new Marker(false, "wheat");

        // On pickup the marker moves to the entity and the stack enters the inventory clean.
        Marker entity = CropPickupListener.playerPickupMarker(Marker.NONE, leftoverStack);
        Marker inventoryStack = Marker.NONE;

        int credits = 0;
        if (CropPickupListener.creditableCrop(Material.WHEAT, crop(entity.creditableCropId())) != null) {
            credits++;
        }
        if (inventoryStack.creditableCropId() != null) {
            credits++;
        }
        assertEquals(1, credits);
    }

    @Test
    void keepsABlockedMarkerWhenMovingItOffTheStack() {
        Marker eligible = new Marker(false, "wheat");
        Marker blocked = new Marker(true, null);

        assertTrue(CropPickupListener.playerPickupMarker(eligible, blocked).blocked());
        assertTrue(CropPickupListener.playerPickupMarker(blocked, eligible).blocked());
        assertNull(CropPickupListener.playerPickupMarker(blocked, eligible).creditableCropId());
        assertEquals("wheat", CropPickupListener.playerPickupMarker(Marker.NONE, eligible).eligibleId());
    }

    @Test
    void collectsSourceBlocksThatAreNotTheCropItem() {
        Set<Material> sources = CropPickupListener.sourceMaterials(List.of(WHEAT, MELON, RED_MUSHROOM));

        assertTrue(sources.contains(Material.MELON));
        assertTrue(sources.contains(Material.RED_MUSHROOM_BLOCK));
        assertTrue(sources.contains(Material.RED_MUSHROOM));
        assertTrue(sources.contains(Material.WHEAT));
        assertFalse(sources.contains(Material.MELON_SLICE));
        assertFalse(sources.contains(Material.STONE));
    }

    private static CropDefinition crop(String id) {
        return WHEAT.id().equals(id) ? WHEAT : null;
    }
}
