package com.onemillioncrops.data;

import com.onemillioncrops.model.ProgressSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
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
    void managedSaveRemovesContributionsMissingFromSnapshot() throws Exception {
        save(snapshot(25, Map.of(PLAYER, Map.of("wheat", 25L))));
        database.save(snapshot(0, Map.of()), Set.of("wheat"));

        ProgressSnapshot loaded = database.load();
        assertEquals(0, loaded.totals().get("wheat"));
        assertTrue(loaded.contributions().isEmpty());
    }

    @Test
    void managedSaveRetainsProgressForDisabledCrops() throws Exception {
        save(new ProgressSnapshot(
                Map.of("wheat", 25L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 25L, "carrot", 10L)),
                Map.of("wheat", false, "carrot", false)));

        database.save(new ProgressSnapshot(
                        Map.of("carrot", 20L),
                        Map.of(PLAYER, Map.of("carrot", 20L)),
                        Map.of("carrot", false)),
                Set.of("carrot"));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 25L, "carrot", 20L), loaded.totals());
        assertEquals(Map.of("wheat", 25L, "carrot", 20L), loaded.contributions().get(PLAYER));
    }

    @Test
    void managedSaveReplacesSnapshotCropsOutsideTheManagedSet() throws Exception {
        save(new ProgressSnapshot(
                Map.of("wheat", 25L, "carrot", 10L),
                Map.of(PLAYER, Map.of("wheat", 25L, "carrot", 10L)),
                Map.of("wheat", false, "carrot", false)));

        // wheat already has rows but is not managed, as when the live crop list and config disagree.
        database.save(new ProgressSnapshot(
                        Map.of("wheat", 30L, "carrot", 20L),
                        Map.of(PLAYER, Map.of("wheat", 30L, "carrot", 20L)),
                        Map.of("wheat", false, "carrot", false)),
                Set.of("carrot"));

        ProgressSnapshot loaded = database.load();
        assertEquals(Map.of("wheat", 30L, "carrot", 20L), loaded.totals());
        assertEquals(Map.of("wheat", 30L, "carrot", 20L), loaded.contributions().get(PLAYER));
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
        database.save(snapshot, snapshot.totals().keySet());
    }

    private static ProgressSnapshot snapshot(long amount, Map<UUID, Map<String, Long>> contributions) {
        return new ProgressSnapshot(Map.of("wheat", amount), contributions, Map.of("wheat", false));
    }
}
