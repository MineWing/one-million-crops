package com.onemillioncrops.util;

import io.papermc.paper.threadedregions.scheduler.*;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

final class TasksTest {
    @Test
    void routesPlayerWorkToEntityAndSkipsDisconnectedPlayer() {
        List<Consumer<ScheduledTask>> queued = new ArrayList<>();
        EntityScheduler scheduler = proxy(EntityScheduler.class, (name, args) -> {
            assertEquals("run", name);
            assertNull(args[2]);
            queued.add((Consumer<ScheduledTask>) args[1]);
            return null;
        });
        AtomicBoolean online = new AtomicBoolean(true);
        Player player = proxy(Player.class, (name, args) -> switch (name) {
            case "getScheduler" -> scheduler;
            case "isOnline" -> online.get();
            default -> throw new AssertionError(name);
        });
        Server server = proxy(Server.class, (name, args) -> {
            assertEquals("isOwnedByCurrentRegion", name);
            return false;
        });
        AtomicBoolean ran = new AtomicBoolean();
        Tasks.player(plugin(server), player, () -> ran.set(true));
        assertFalse(ran.get());
        online.set(false);
        queued.getFirst().accept(null);
        assertFalse(ran.get());
        online.set(true);
        queued.getFirst().accept(null);
        assertTrue(ran.get());
    }

    @Test
    void runsOwnedPlayerWorkImmediately() {
        Server server = proxy(Server.class, (name, args) -> {
            assertEquals("isOwnedByCurrentRegion", name);
            return true;
        });
        Player player = proxy(Player.class, (name, args) -> {
            assertEquals("isOnline", name);
            return true;
        });
        AtomicBoolean ran = new AtomicBoolean();
        Tasks.player(plugin(server), player, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void usesRegionForBlocksAndConvertsAsyncTicksToMilliseconds() {
        Location location = new Location(null, -16, 64, 32);
        AtomicBoolean regionCalled = new AtomicBoolean();
        RegionScheduler region = proxy(RegionScheduler.class, (name, args) -> {
            assertEquals("runDelayed", name);
            assertSame(location, args[1]);
            assertEquals(1L, args[3]);
            regionCalled.set(true);
            return null;
        });
        AtomicBoolean asyncCalled = new AtomicBoolean();
        AsyncScheduler async = proxy(AsyncScheduler.class, (name, args) -> {
            assertEquals("runDelayed", name);
            assertEquals(1_500L, args[2]);
            assertEquals(TimeUnit.MILLISECONDS, args[3]);
            asyncCalled.set(true);
            return null;
        });
        Server server = proxy(Server.class, (name, args) -> switch (name) {
            case "getRegionScheduler" -> region;
            case "getAsyncScheduler" -> async;
            default -> throw new AssertionError(name);
        });
        Tasks.regionLater(plugin(server), location, () -> {}, 0L);
        Tasks.asyncLater(plugin(server), () -> {}, 30L);
        assertTrue(regionCalled.get());
        assertTrue(asyncCalled.get());
    }

    private static Plugin plugin(Server server) {
        return proxy(Plugin.class, (name, args) -> switch (name) {
            case "getServer" -> server;
            case "isEnabled" -> true;
            default -> throw new AssertionError(name);
        });
    }

    @FunctionalInterface
    private interface Call { Object invoke(String name, Object[] args); }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
}
