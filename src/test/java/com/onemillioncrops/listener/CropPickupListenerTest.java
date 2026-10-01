package com.onemillioncrops.listener;

import com.onemillioncrops.model.CropDefinition;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

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
}
