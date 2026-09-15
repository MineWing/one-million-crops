package com.onemillioncrops.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EggCaptureListenerTest {
    @Test
    void storesOriginalSnapshotAndTreatsMobNameAsLiteralText() {
        EntitySnapshot snapshot = (EntitySnapshot) Proxy.newProxyInstance(EntitySnapshot.class.getClassLoader(),
                new Class<?>[]{EntitySnapshot.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        Map<String, Object> fields = new HashMap<>();
        SpawnEggMeta meta = (SpawnEggMeta) Proxy.newProxyInstance(SpawnEggMeta.class.getClassLoader(),
                new Class<?>[]{SpawnEggMeta.class}, (proxy, method, args) -> {
                    fields.put(method.getName(), args[0]);
                    return null;
                });
        Component name = Component.text("<red>Bessie</red>");
        EggCaptureListener.describeCapture(meta, snapshot, name);
        assertSame(snapshot, fields.get("setSpawnedEntity"));
        Component displayName = (Component) fields.get("displayName");
        assertEquals(name, displayName.children().getFirst());
    }
}
