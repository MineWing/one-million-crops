package com.onemillioncrops.listener;

import com.onemillioncrops.listener.CropMarkers.Marker;
import com.onemillioncrops.model.CropDefinition;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.onemillioncrops.listener.CropPickupListener.HopperPickupPolicy.BLOCK;
import static com.onemillioncrops.listener.CropPickupListener.HopperPickupPolicy.DEFER_UNTIL_PLAYER;
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
    void handPickupCountsFarmDropsWithAutoModeDisabled() {
        assertEquals(WHEAT, CropPickupListener.playerPickupCrop(WHEAT, null, null));
    }

    @Test
    void rejectsInvalidMarkersAndNonCropPickups() {
        assertNull(CropPickupListener.playerPickupCrop(WHEAT, "missing", null));
        var carrot = new CropDefinition("carrot", Material.CARROT, Set.of(Material.CARROTS), "Carrot");
        assertNull(CropPickupListener.playerPickupCrop(WHEAT, "carrot", carrot));
        assertNull(CropPickupListener.playerPickupCrop(null, null, null));
        assertEquals(WHEAT, CropPickupListener.playerPickupCrop(WHEAT, "wheat", WHEAT));
    }

    @Test
    void preservesFarmDropsInHoppersWithAutoModeDisabled() {
        assertEquals(DEFER_UNTIL_PLAYER, CropPickupListener.hopperPickupPolicy(false));
    }

    @Test
    void blocksPreviouslyCountedHopperPickups() {
        assertEquals(BLOCK, CropPickupListener.hopperPickupPolicy(true));
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
        if (CropPickupListener.playerPickupCrop(WHEAT, entity.creditableCropId(), WHEAT) != null) {
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
    void tracksPlacedBlocksThatAreNotTheCropItem() {
        List<CropDefinition> crops = List.of(WHEAT, MELON, RED_MUSHROOM);

        assertTrue(CropPickupListener.tracksPlacement(true, crops, Material.MELON));
        assertTrue(CropPickupListener.tracksPlacement(true, crops, Material.RED_MUSHROOM_BLOCK));
        assertTrue(CropPickupListener.tracksPlacement(true, crops, Material.WHEAT));
        assertFalse(CropPickupListener.tracksPlacement(true, crops, Material.STONE));
        assertFalse(CropPickupListener.tracksPlacement(false, crops, Material.MELON));
    }
}
