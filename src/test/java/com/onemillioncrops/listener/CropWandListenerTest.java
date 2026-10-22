package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CropWandListenerTest {
    @Test
    void capsDepositsAtTheRemainingCropGoal() {
        assertEquals(40, CropWandListener.depositLimit(64, 60, 100));
        assertEquals(64, CropWandListener.depositLimit(64, 10, 100));
    }

    @Test
    void leavesItemsWhenTheGoalIsCompleteOrInputsAreInvalid() {
        assertEquals(0, CropWandListener.depositLimit(64, 100, 100));
        assertEquals(0, CropWandListener.depositLimit(-1, 0, 100));
    }

    @Test
    void clearsOnlyTheCommonFarmSeeds() {
        assertTrue(CropWandListener.isClearableSeed(Material.WHEAT_SEEDS));
        assertTrue(CropWandListener.isClearableSeed(Material.BEETROOT_SEEDS));
        assertTrue(CropWandListener.isClearableSeed(Material.MELON_SEEDS));
        assertTrue(CropWandListener.isClearableSeed(Material.PUMPKIN_SEEDS));
        assertEquals(4, CropWandListener.CLEARABLE_SEEDS.size());
    }

    @Test
    void keepsRareSeedsAndPlantableCrops() {
        assertFalse(CropWandListener.isClearableSeed(Material.TORCHFLOWER_SEEDS));
        assertFalse(CropWandListener.isClearableSeed(Material.PITCHER_POD));
        assertFalse(CropWandListener.isClearableSeed(Material.WHEAT));
        assertFalse(CropWandListener.isClearableSeed(Material.CARROT));
        assertFalse(CropWandListener.isClearableSeed(Material.NETHER_WART));
        assertFalse(CropWandListener.isClearableSeed(null));
    }
}
