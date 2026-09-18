package com.onemillioncrops.service;

import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.model.ProgressSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressServiceTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void clampsAtTargetAndCompletesOnlyOnce() {
        ProgressService service = service(100);
        var first = service.add(PLAYER, "wheat", 150);
        var second = service.add(PLAYER, "wheat", 1);

        assertEquals(100, first.added());
        assertTrue(first.completed());
        assertEquals(0, second.added());
        assertFalse(second.completed());
        assertEquals(100, service.contribution(PLAYER, "wheat"));
    }

    @Test
    void reportsEveryCrossedMilestone() {
        ProgressService service = service(1_000_000);
        var result = service.add(PLAYER, "wheat", 800_000);

        assertEquals(List.of(25, 50, 75), result.crossedMilestones());
    }

    @Test
    void resetCropClearsTotalsAndContributions() {
        ProgressService service = service(100);
        service.add(PLAYER, "wheat", 40);
        service.resetCrop("wheat");

        assertEquals(0, service.amount("wheat"));
        assertEquals(0, service.contribution(PLAYER, "wheat"));
    }

    @Test
    void supportsLongMaxTargetWithoutOverflow() {
        ProgressService service = service(Long.MAX_VALUE);
        var first = service.add(PLAYER, "wheat", Long.MAX_VALUE - 10);
        var result = service.add(PLAYER, "wheat", 64);

        assertEquals(List.of(25, 50, 75, 90), first.crossedMilestones());
        assertEquals(10, result.added());
        assertEquals(Long.MAX_VALUE, service.amount("wheat"));
        assertTrue(result.completed());
        assertEquals(List.of(), result.crossedMilestones());
    }

    @Test
    void addsAutomatedHarvestWithoutCreditingAPlayer() {
        ProgressService service = service(100);

        var result = service.addUnattributed("wheat", 32);

        assertEquals(32, result.added());
        assertEquals(32, service.amount("wheat"));
        assertTrue(service.contributors().isEmpty());
        assertEquals(0, service.contribution(PLAYER, "wheat"));
    }

    private static ProgressService service(long target) {
        Map<String, CropDefinition> crops = new LinkedHashMap<>();
        crops.put("wheat", new CropDefinition("wheat", Material.WHEAT, Set.of(Material.WHEAT), "Wheat"));
        return new ProgressService(crops, target, List.of(25, 50, 75, 90),
                new ProgressSnapshot(Map.of(), Map.of(), Map.of()));
    }
}
