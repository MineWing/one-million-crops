package com.onemillioncrops.command;

import com.onemillioncrops.data.TravelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class TravelCommandTest {
    @TempDir Path directory;

    @Test
    void defaultHomeIsSavedBeforeImmediateHomeRequestAndSurvivesShutdown() throws Exception {
        var h = new UtilityCommandTest.Harness();
        var file = directory.resolve("travel.db");
        var saved = h.caller.location.clone();
        try (var commands = new TravelCommand(h.plugin, new TravelStore(file))) {
            run(h, commands, "sethome");
            h.caller.location.setX(700);
            run(h, commands, "home");
        }
        h.drain();
        assertEquals(saved, h.caller.teleported);
        try (var store = new TravelStore(file)) {
            assertEquals(saved.getX(), store.get(h.caller.id.toString(), "home").x());
            assertNull(store.get(h.target.id.toString(), "home"));
        }
    }

    @Test
    void spawnAndWarpSetTravelAndDeleteWork() throws Exception {
        var h = new UtilityCommandTest.Harness();
        var file = directory.resolve("travel.db");
        try (var commands = new TravelCommand(h.plugin, new TravelStore(file))) {
            run(h, commands, "setspawn");
            run(h, commands, "spawn");
            run(h, commands, "setwarp", "Market");
            run(h, commands, "warp", "market");
            run(h, commands, "delwarp", "market");
        }
        h.drain();
        assertEquals(h.caller.location, h.caller.teleported);
        try (var store = new TravelStore(file)) {
            assertNotNull(store.get("spawn", "spawn"));
            assertNull(store.get("warps", "market"));
        }
    }

    @Test
    void deniedCommandsAndMissingWorldNeverTeleport() throws Exception {
        var h = new UtilityCommandTest.Harness();
        var file = directory.resolve("travel.db");
        try (var commands = new TravelCommand(h.plugin, new TravelStore(file))) {
            h.allowed = false;
            run(h, commands, "setwarp", "denied");
            h.allowed = true;
            run(h, commands, "sethome", "farm");
            run(h, commands, "home", "farm");
            h.worldLoaded = false;
        }
        h.drain();
        assertNull(h.caller.teleported);
        assertTrue(h.caller.messages.stream().anyMatch(message -> message.contains("world is not loaded")));
        try (var store = new TravelStore(file)) {
            assertNull(store.get("warps", "denied"));
        }
    }

    private static void run(UtilityCommandTest.Harness h, TravelCommand commands, String name, String... args) {
        h.owner = h.caller.player;
        commands.onCommand(h.caller.player, UtilityCommandTest.command(name), name, args);
    }
}
