package com.onemillioncrops.command;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

final class UtilityCommandTest {
    @Test
    void allGameModeShortcutsChangeOnlyTheCaller() {
        Harness h = new Harness();
        for (var entry : Map.of("gms", GameMode.SURVIVAL, "gmc", GameMode.CREATIVE,
                "gmsp", GameMode.SPECTATOR).entrySet()) {
            h.run(entry.getKey());
            assertEquals(entry.getValue(), h.caller.mode);
            assertEquals(GameMode.SURVIVAL, h.target.mode);
        }
    }

    @Test
    void permissionsAndInvalidArgumentsPreventChanges() {
        Harness h = new Harness();
        h.allowed = false;
        h.run("gmc");
        h.run("tp", "Target");
        assertEquals(GameMode.SURVIVAL, h.caller.mode);
        assertNull(h.caller.teleported);
        assertTrue(h.caller.messages.getLast().contains("permission"));
        h.allowed = true;
        h.run("gmc", "Target");
        h.run("tp", "here", "Target", "extra");
        h.run("tp", "Missing");
        assertEquals(GameMode.SURVIVAL, h.caller.mode);
        assertNull(h.target.teleported);
        assertNull(h.caller.teleported);
    }

    @Test
    void teleportToPlayerReadsDestinationOnItsOwnerThenMovesCaller() {
        Harness h = new Harness();
        h.run("tp", "Target");
        assertNull(h.caller.teleported);
        h.drain();
        assertEquals(h.target.location, h.caller.teleported);
        assertNull(h.target.teleported);
        assertTrue(h.caller.messages.getLast().contains("Teleported to Target"));
    }

    @Test
    void teleportHereMovesTargetToCapturedCallerLocation() {
        Harness h = new Harness();
        Location requested = h.caller.location.clone();
        h.run("tp", "HeRe", "Target");
        h.caller.location.setX(900);
        h.drain();
        assertEquals(requested, h.target.teleported);
        assertNull(h.caller.teleported);
        assertTrue(h.caller.messages.getLast().contains("Brought Target"));
    }

    @Test
    void cancelledTeleportReportsFailure() {
        Harness h = new Harness();
        h.target.succeeds = false;
        h.run("tp", "here", "Target");
        h.drain();
        assertTrue(h.caller.messages.getLast().contains("failed or was cancelled"));
    }

    @Test
    void completionIsPermissionGatedAndFiltersNames() {
        Harness h = new Harness();
        assertEquals(List.of("Target"), h.command.onTabComplete(h.caller.player, command("tp"), "tp",
                new String[]{"here", "ta"}));
        h.allowed = false;
        assertEquals(List.of(), h.command.onTabComplete(h.caller.player, command("tp"), "tp", new String[]{""}));
    }

    private static final class Harness {
        boolean allowed = true;
        Player owner;
        final Queue<Runnable> queue = new ArrayDeque<>();
        final Person caller = new Person("Caller", 1);
        final Person target = new Person("Target", 100);
        final Server server = proxy(Server.class, (name, args) -> switch (name) {
            case "isOwnedByCurrentRegion" -> args[0] == owner;
            case "getPlayerExact" -> args[0].equals("Target") ? target.player : null;
            case "getOnlinePlayers" -> List.of(caller.player, target.player);
            default -> throw new AssertionError(name);
        });
        final Plugin plugin = proxy(Plugin.class, (name, args) -> switch (name) {
            case "isEnabled" -> true;
            case "getServer" -> server;
            default -> throw new AssertionError(name);
        });
        final UtilityCommand command = new UtilityCommand(plugin);

        void run(String name, String... args) {
            owner = caller.player;
            command.onCommand(caller.player, command(name), name, args);
        }

        void drain() {
            while (!queue.isEmpty()) queue.remove().run();
        }

        final class Person {
            final UUID id = UUID.randomUUID();
            final Location location;
            Location teleported;
            GameMode mode = GameMode.SURVIVAL;
            boolean succeeds = true;
            final List<String> messages = new ArrayList<>();
            Player player;

            Person(String username, double x) {
                location = new Location(null, x, 64, x);
                player = proxy(Player.class, (name, args) -> switch (name) {
                    case "getUniqueId" -> id;
                    case "getName" -> username;
                    case "hasPermission" -> allowed;
                    case "isOnline", "canSee" -> true;
                    case "getScheduler" -> proxy(EntityScheduler.class, (method, params) -> {
                        queue.add(() -> {
                            owner = this.player;
                            if (method.equals("execute")) ((Runnable) params[1]).run();
                            else ((java.util.function.Consumer<?>) params[1]).accept(null);
                        });
                        return method.equals("execute") ? true : null;
                    });
                    case "getLocation" -> { assertSame(this.player, owner); yield location; }
                    case "setGameMode" -> { assertSame(this.player, owner); mode = (GameMode) args[0]; yield null; }
                    case "getGameMode" -> mode;
                    case "teleportAsync" -> {
                        assertSame(this.player, owner);
                        teleported = (Location) args[0];
                        yield CompletableFuture.completedFuture(succeeds);
                    }
                    case "sendMessage" -> {
                        assertSame(this.player, owner);
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new AssertionError(name);
                });
            }
        }
    }

    private static Command command(String name) {
        return new Command(name) {
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { return false; }
        };
    }

    @FunctionalInterface
    private interface Call { Object invoke(String name, Object[] args); }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
}
