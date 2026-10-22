package com.onemillioncrops.data;

import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.model.ProgressSnapshot;
import com.onemillioncrops.service.ProgressService;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressDatabaseTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @TempDir
    Path temporaryDirectory;

    private ProgressDatabase database;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new ProgressDatabase(temporaryDirectory, Logger.getAnonymousLogger(), "progress.db");
        database.open();
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    @Test
    void saveLeavesRowsTheSnapshotDoesNotCarry() throws Exception {
        UUID other = UUID.randomUUID();
        save(new ProgressSnapshot(
                Map.of("wheat", 25L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 25L, "carrot", 10L), other, Map.of("wheat", 5L)),
                Map.of("wheat", false, "carrot", false)));

        save(new ProgressSnapshot(Map.of("carrot", 12L), Map.of(PLAYER, Map.of("carrot", 12L)),
                Map.of("carrot", false)));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 25L, "carrot", 12L), loaded.totals());
        assertEquals(Map.of("wheat", 25L, "carrot", 12L), loaded.contributions().get(PLAYER));
        assertEquals(Map.of("wheat", 5L), loaded.contributions().get(other));
    }

    @Test
    void saveRetainsProgressForDisabledCrops() throws Exception {
        save(new ProgressSnapshot(
                Map.of("wheat", 25L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 25L, "carrot", 10L)),
                Map.of("wheat", false, "carrot", false)));

        // wheat is disabled, so the live ProgressService only knows about carrot.
        save(new ProgressSnapshot(
                Map.of("carrot", 20L),
                Map.of(PLAYER, Map.of("carrot", 20L)),
                Map.of("carrot", false)));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 25L, "carrot", 20L), loaded.totals());
        assertEquals(Map.of("wheat", 25L, "carrot", 20L), loaded.contributions().get(PLAYER));
    }

    @Test
    void saveReplacesExistingRowsWithTheSnapshotValues() throws Exception {
        save(new ProgressSnapshot(
                Map.of("wheat", 25L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 25L, "carrot", 10L)),
                Map.of("wheat", false, "carrot", false)));

        save(new ProgressSnapshot(
                Map.of("wheat", 100L, "carrot", 8L),
                Map.of(PLAYER, Map.of("wheat", 30L, "carrot", 8L)),
                Map.of("wheat", true, "carrot", false)));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 100L, "carrot", 8L), loaded.totals());
        assertEquals(Map.of("wheat", true, "carrot", false), loaded.completed());
        assertEquals(Map.of("wheat", 30L, "carrot", 8L), loaded.contributions().get(PLAYER));
    }

    @Test
    void routineSaveWritesOnlyChangedRows() throws Exception {
        // A row for a crop the service does not manage, such as a disabled one.
        save(new ProgressSnapshot(Map.of("beetroot", 7L), Map.of(PLAYER, Map.of("beetroot", 7L)),
                Map.of("beetroot", false)));
        ProgressService service = service(database.load());
        service.add(PLAYER, "wheat", 10);
        service.add(PLAYER, "carrot", 5);
        save(service.takeDirtySnapshot());

        service.add(PLAYER, "carrot", 2);
        ProgressSnapshot changes = service.takeDirtySnapshot();
        assertEquals(Set.of("carrot"), changes.totals().keySet());
        // Change wheat behind the service's back: a routine save of carrot must not touch it.
        save(new ProgressSnapshot(Map.of("wheat", 99L), Map.of(), Map.of("wheat", false)));
        save(changes);

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("beetroot", 7L, "wheat", 99L, "carrot", 7L), loaded.totals());
        assertEquals(Map.of("beetroot", 7L, "wheat", 10L, "carrot", 7L), loaded.contributions().get(PLAYER));
    }

    @Test
    void failedRoutineSaveIsFollowedByAFullSave() throws Exception {
        ProgressService service = service(database.load());
        service.add(PLAYER, "wheat", 10);
        service.add(PLAYER, "carrot", 5);
        save(service.takeDirtySnapshot());

        service.add(PLAYER, "wheat", 7);
        ProgressSnapshot lost = service.takeDirtySnapshot();
        database.close();
        assertThrows(SQLException.class, () -> save(lost));
        service.markDirty();
        database.open();

        // Only carrot changes now; the wheat change from the failed save must still reach the database.
        service.add(PLAYER, "carrot", 3);
        save(service.takeDirtySnapshot());

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 17L, "carrot", 8L), loaded.totals());
        assertEquals(Map.of("wheat", 17L, "carrot", 8L), loaded.contributions().get(PLAYER));
    }

    @Test
    void runtimeFailureInsideTransactionRollsBackPartialWrites() throws Exception {
        save(snapshot(10, Map.of(PLAYER, Map.of("wheat", 10L))));

        assertThrows(IllegalStateException.class, () -> database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM crop_progress");
                statement.executeUpdate("DELETE FROM contributions");
            }
            throw new IllegalStateException("simulated failure after the deletes");
        }));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 10L), loaded.totals());
        assertEquals(Map.of("wheat", 10L), loaded.contributions().get(PLAYER));
    }

    @Test
    void completionAndPendingFinaleArePersistedTogether() throws Exception {
        ProgressSnapshot completed = new ProgressSnapshot(
                Map.of("wheat", 100L),
                Map.of(PLAYER, Map.of("wheat", 100L)),
                Map.of("wheat", true));

        database.saveCompletion(completed, "wheat", Set.of(PLAYER), true);

        assertEquals(100, database.load().totals().get("wheat"));
        assertEquals(List.of(new ProgressDatabase.PendingCelebration("wheat", true)),
                database.pendingCelebrations(PLAYER));
    }

    @Test
    void resetCropClearsProgressContributionsAndPendingCelebrations() throws Exception {
        ProgressSnapshot completed = new ProgressSnapshot(
                Map.of("wheat", 100L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 100L, "carrot", 10L)),
                Map.of("wheat", true, "carrot", false));
        database.saveCompletion(completed, "wheat", Set.of(PLAYER), false);

        database.resetCrop("wheat");

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("carrot", 10L), loaded.totals());
        assertEquals(Map.of("carrot", 10L), loaded.contributions().get(PLAYER));
        assertTrue(database.pendingCelebrations(PLAYER).isEmpty());
    }

    @Test
    void failedCompletionRollsBackProgressAndPendingInsert() throws Exception {
        save(snapshot(10, Map.of(PLAYER, Map.of("wheat", 10L))));
        ProgressSnapshot replacement = snapshot(100, Map.of(PLAYER, Map.of("wheat", 100L)));

        assertThrows(SQLException.class,
                () -> database.saveCompletion(replacement, null, Set.of(PLAYER), true));

        assertEquals(10, database.load().totals().get("wheat"));
        assertTrue(database.pendingCelebrations(PLAYER).isEmpty());
    }

    @Test
    void harvestPersonalBestOnlyIncreasesAndIsClearedByFullReset() throws Exception {
        database.saveHarvestPersonalBests(Map.of(PLAYER, 100L));
        database.saveHarvestPersonalBests(Map.of(PLAYER, 90L));

        assertEquals(Map.of(PLAYER, 100L), database.harvestPersonalBests());

        database.saveHarvestPersonalBests(Map.of(PLAYER, 125L));
        assertEquals(Map.of(PLAYER, 125L), database.harvestPersonalBests());

        database.resetAll();
        assertTrue(database.harvestPersonalBests().isEmpty());
    }

    private void save(ProgressSnapshot snapshot) throws Exception {
        database.save(snapshot);
    }

    private static ProgressService service(ProgressSnapshot loaded) {
        Map<String, CropDefinition> crops = new LinkedHashMap<>();
        crops.put("wheat", new CropDefinition("wheat", Material.WHEAT, Set.of(Material.WHEAT), "Wheat"));
        crops.put("carrot", new CropDefinition("carrot", Material.CARROT, Set.of(Material.CARROTS), "Carrot"));
        return new ProgressService(crops, 1_000, List.of(), loaded);
    }

    private static ProgressSnapshot snapshot(long amount, Map<UUID, Map<String, Long>> contributions) {
        return new ProgressSnapshot(Map.of("wheat", amount), contributions, Map.of("wheat", false));
    }
}
