package com.onemillioncrops.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class TravelStoreTest {
    @TempDir Path directory;

    @Test
    void persistsWorldCoordinatesAndRotationAcrossRestart() throws Exception {
        Path path = directory.resolve("travel.db");
        var point = new TravelStore.Point(UUID.randomUUID(), -120.25, 80, 65.5, 123.5f, -42f);
        try (var store = new TravelStore(path)) {
            store.set("spawn", "spawn", point);
            store.set("warps", "Market", point);
        }
        try (var store = new TravelStore(path)) {
            assertEquals(point, store.get("spawn", "spawn"));
            assertEquals(point, store.get("warps", "MARKET"));
            assertEquals(List.of("market"), store.names("warps"));
        }
    }

    @Test
    void isolatesHomesAndDeletesOnlyTheRequestedLocation() throws Exception {
        try (var store = new TravelStore(directory.resolve("travel.db"))) {
            var point = new TravelStore.Point(UUID.randomUUID(), 1, 64, 2, 0, 0);
            store.set("alice", "home", point);
            store.set("bob", "home", point);
            store.set("warps", "home", point);
            assertTrue(store.delete("alice", "HOME"));
            assertFalse(store.delete("alice", "home"));
            assertNull(store.get("alice", "home"));
            assertEquals(point, store.get("bob", "home"));
            assertEquals(point, store.get("warps", "home"));
            assertTrue(store.names("alice").isEmpty());
        }
    }

    @Test
    void overwriteUpdatesDestinationWithoutDuplicatingNames() throws Exception {
        try (var store = new TravelStore(directory.resolve("travel.db"))) {
            var first = new TravelStore.Point(UUID.randomUUID(), 0, 64, 0, 0, 0);
            var second = new TravelStore.Point(UUID.randomUUID(), 100, 90, -400, 30, 10);
            store.set("alice", "Farm", first);
            store.set("alice", "farm", second);
            assertEquals(second, store.get("alice", "FARM"));
            assertEquals(List.of("farm"), store.names("alice"));
        }
    }

    @Test
    void rejectsInvalidNames() {
        for (String name : List.of("", "../other", "<red>", "a.b", "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> TravelStore.normalize(name));
        }
        assertEquals("my_farm-2", TravelStore.normalize("My_Farm-2"));
    }
}
