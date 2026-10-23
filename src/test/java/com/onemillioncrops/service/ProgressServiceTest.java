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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void takeSnapshotReturnsStateAndClearsDirtyFlag() {
        ProgressService service = service(100);
        assertEquals(0L, service.takeSnapshot().totals().get("wheat"));

        service.add(PLAYER, "wheat", 5);
        var snapshot = service.takeSnapshot();

        assertEquals(5L, snapshot.totals().get("wheat"));
        assertFalse(service.isDirty());
        assertNull(service.takeDirtySnapshot());
    }

    @Test
    void loweringTargetCapsDisplayButKeepsStoredTotal() {
        ProgressService lowered = service(1_000, stored(5_000));

        assertEquals(1_000, lowered.amount("wheat"));
        assertEquals(1, lowered.completedCount());
        assertEquals(5_000L, lowered.snapshot().totals().get("wheat"));
        assertEquals(5_000L, lowered.takeSnapshot().totals().get("wheat"));
    }

    @Test
    void addCreditsNothingWhenStoredTotalIsAlreadyPastTarget() {
        ProgressService lowered = service(1_000, stored(5_000));

        var result = lowered.add(PLAYER, "wheat", 64);

        assertEquals(ProgressService.IncrementResult.NONE, result);
        assertEquals(5_000L, lowered.snapshot().totals().get("wheat"));
        assertEquals(0, lowered.contribution(PLAYER, "wheat"));
        assertFalse(lowered.isDirty());
    }

    @Test
    void raisingTargetAgainRestoresTheOldTotal() {
        ProgressService lowered = service(1_000, stored(5_000));
        ProgressService raised = service(10_000, lowered.takeSnapshot());

        assertEquals(5_000, raised.amount("wheat"));
        assertEquals(0, raised.completedCount());

        var result = raised.add(PLAYER, "wheat", 6_000);
        assertEquals(5_000, result.added());
        assertTrue(result.completed());
        assertEquals(10_000, raised.amount("wheat"));
    }

    @Test
    void dirtySnapshotCarriesOnlyChangedTotalsAndContributions() {
        UUID other = UUID.randomUUID();
        ProgressService service = twoCropService(new ProgressSnapshot(
                Map.of("wheat", 10L, "carrot", 20L),
                Map.of(PLAYER, Map.of("wheat", 10L, "carrot", 5L), other, Map.of("carrot", 15L)),
                Map.of()));

        service.add(PLAYER, "carrot", 3);
        var changes = service.takeDirtySnapshot();

        assertEquals(Map.of("carrot", 23L), changes.totals());
        assertEquals(Map.of("carrot", false), changes.completed());
        assertEquals(Map.of(PLAYER, Map.of("carrot", 8L)), changes.contributions());
        assertFalse(service.isDirty());
        assertNull(service.takeDirtySnapshot());
    }

    @Test
    void markDirtyMakesTheNextDirtySnapshotFull() {
        ProgressService service = twoCropService(new ProgressSnapshot(
                Map.of("wheat", 10L, "carrot", 20L), Map.of(PLAYER, Map.of("wheat", 10L)), Map.of()));
        service.add(PLAYER, "carrot", 1);
        service.takeDirtySnapshot();

        // The save of that change failed.
        service.markDirty();

        var full = service.takeDirtySnapshot();
        assertEquals(Map.of("wheat", 10L, "carrot", 21L), full.totals());
        assertEquals(Map.of(PLAYER, Map.of("wheat", 10L, "carrot", 1L)), full.contributions());
        assertNull(service.takeDirtySnapshot());
    }

    @Test
    void resetCropDropsOnlyThatCropsPendingChanges() {
        ProgressService service = twoCropService(new ProgressSnapshot(Map.of(), Map.of(), Map.of()));
        service.add(PLAYER, "wheat", 4);
        service.add(PLAYER, "carrot", 6);

        service.resetCrop("wheat");

        var changes = service.takeDirtySnapshot();
        assertEquals(Map.of("carrot", 6L), changes.totals());
        assertEquals(Map.of(PLAYER, Map.of("carrot", 6L)), changes.contributions());
    }

    private static ProgressService twoCropService(ProgressSnapshot loaded) {
        Map<String, CropDefinition> crops = new LinkedHashMap<>();
        crops.put("wheat", new CropDefinition("wheat", Material.WHEAT, Set.of(Material.WHEAT), "Wheat"));
        crops.put("carrot", new CropDefinition("carrot", Material.CARROT, Set.of(Material.CARROTS), "Carrot"));
        return new ProgressService(crops, 100, List.of(25, 50, 75, 90), loaded);
    }

    private static ProgressSnapshot stored(long wheat) {
        return new ProgressSnapshot(Map.of("wheat", wheat), Map.of(), Map.of("wheat", true));
    }

    private static ProgressService service(long target) {
        return service(target, new ProgressSnapshot(Map.of(), Map.of(), Map.of()));
    }

    private static ProgressService service(long target, ProgressSnapshot loaded) {
        Map<String, CropDefinition> crops = new LinkedHashMap<>();
        crops.put("wheat", new CropDefinition("wheat", Material.WHEAT, Set.of(Material.WHEAT), "Wheat"));
        return new ProgressService(crops, target, List.of(25, 50, 75, 90), loaded);
    }
}
