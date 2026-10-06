package com.onemillioncrops.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StartupBannerTest {
    @Test
    void bannerParsesAndArtRowsLineUp() {
        List<String> plain = StartupBanner.lines("1.2.3", 18, 2, 1_000_000L).stream()
                .map(line -> PlainTextComponentSerializer.plainText()
                        .serialize(MiniMessage.miniMessage().deserialize(line)))
                .toList();

        List<String> art = plain.subList(1, 7);
        assertTrue(art.stream().noneMatch(String::isBlank));
        assertEquals(1, art.stream().mapToInt(String::length).distinct().count());
        assertTrue(plain.stream().anyMatch(line -> line.contains("v1.2.3")));
        assertTrue(plain.stream().anyMatch(line -> line.contains("18 crops") && line.contains("1,000,000")));
    }
}
