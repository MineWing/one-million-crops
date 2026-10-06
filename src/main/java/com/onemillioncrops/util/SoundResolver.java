package com.onemillioncrops.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SoundResolver {
    /** Legacy enum-style names resolve by scanning the whole registry, so successful lookups are remembered. */
    private static final Map<String, Sound> LEGACY_SOUNDS = new ConcurrentHashMap<>();

    private SoundResolver() {
    }

    public static Sound resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String lower = configured.toLowerCase(Locale.ROOT);
        NamespacedKey direct = NamespacedKey.fromString(lower.contains(":") ? lower : "minecraft:" + lower);
        Sound sound = direct == null ? null : Registry.SOUNDS.get(direct);
        if (sound != null) {
            return sound;
        }

        String legacyName = configured.toUpperCase(Locale.ROOT);
        Sound cached = LEGACY_SOUNDS.get(legacyName);
        if (cached != null) {
            return cached;
        }
        Sound legacy = Registry.SOUNDS.keyStream()
                .filter(key -> legacyName(key).equals(legacyName))
                .findFirst()
                .map(Registry.SOUNDS::get)
                .orElse(null);
        if (legacy != null) {
            LEGACY_SOUNDS.put(legacyName, legacy);
        }
        return legacy;
    }

    private static String legacyName(NamespacedKey key) {
        return key.getKey().toUpperCase(Locale.ROOT).replace('.', '_').replace('/', '_');
    }
}
