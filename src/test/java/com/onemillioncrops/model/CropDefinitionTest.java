package com.onemillioncrops.model;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CropDefinitionTest {
    @Test
    void verticalCropGrowthTimersAreNotTreatedAsHarvestMaturity() {
        assertFalse(CropDefinition.maturityAgeApplies(Material.SUGAR_CANE));
        assertFalse(CropDefinition.maturityAgeApplies(Material.CACTUS));
        assertFalse(CropDefinition.maturityAgeApplies(Material.BAMBOO));
        assertFalse(CropDefinition.maturityAgeApplies(Material.KELP));
    }

    @Test
    void ordinaryAgeableCropsStillRequireMaturity() {
        assertTrue(CropDefinition.maturityAgeApplies(Material.WHEAT));
        assertTrue(CropDefinition.maturityAgeApplies(Material.CARROTS));
        assertTrue(CropDefinition.maturityAgeApplies(Material.POTATOES));
        assertTrue(CropDefinition.maturityAgeApplies(Material.NETHER_WART));
    }

    @Test
    void sweetBerriesCountFromTheirFirstHarvestableAge() {
        assertFalse(CropDefinition.ageAllowsHarvest(Material.SWEET_BERRY_BUSH, 1, 3));
        assertTrue(CropDefinition.ageAllowsHarvest(Material.SWEET_BERRY_BUSH, 2, 3));
        assertTrue(CropDefinition.ageAllowsHarvest(Material.SWEET_BERRY_BUSH, 3, 3));
    }

    @Test
    void otherCropsNeedTheirMaximumAge() {
        assertFalse(CropDefinition.ageAllowsHarvest(Material.WHEAT, 6, 7));
        assertTrue(CropDefinition.ageAllowsHarvest(Material.WHEAT, 7, 7));
    }
}
