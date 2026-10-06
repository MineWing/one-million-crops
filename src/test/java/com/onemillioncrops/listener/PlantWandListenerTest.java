package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlantWandListenerTest {
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void calculatesInclusiveSelectionVolume() {
        var first = new PlantWandListener.BlockPosition(WORLD, 10, 64, -5);
        var second = new PlantWandListener.BlockPosition(WORLD, 19, 65, 4);

        assertEquals(200L, PlantWandListener.selectionVolume(first, second));
        assertEquals(1L, PlantWandListener.selectionVolume(first, first));
    }

    @Test
    void capsTheWidestHorizontalAxisRegardlessOfVolume() {
        var origin = new PlantWandListener.BlockPosition(WORLD, 0, 64, 0);
        var widestAllowed = new PlantWandListener.BlockPosition(WORLD, 127, 64, -127);
        var line = new PlantWandListener.BlockPosition(WORLD, 0, 64, 32_767);
        var tall = new PlantWandListener.BlockPosition(WORLD, 0, 319, 0);

        assertEquals(1L, PlantWandListener.horizontalSpan(origin, origin));
        assertEquals(128L, PlantWandListener.horizontalSpan(origin, widestAllowed));
        assertEquals(129L, PlantWandListener.horizontalSpan(origin,
                new PlantWandListener.BlockPosition(WORLD, -128, 64, 0)));
        assertTrue(PlantWandListener.selectionVolume(origin, line) <= PlantWandListener.MAX_SELECTION_VOLUME);
        assertEquals(32_768L, PlantWandListener.horizontalSpan(origin, line));
        assertEquals(1L, PlantWandListener.horizontalSpan(origin, tall));
        assertEquals((long) Integer.MAX_VALUE * 2L + 2L, PlantWandListener.horizontalSpan(
                new PlantWandListener.BlockPosition(WORLD, Integer.MIN_VALUE, 0, 0),
                new PlantWandListener.BlockPosition(WORLD, Integer.MAX_VALUE, 0, 0)));
    }

    @Test
    void requiresThePlayerToStandNearTheSelectionCentre() {
        var selection = new PlantWandListener.Selection(
                new PlantWandListener.BlockPosition(WORLD, 0, 64, 0),
                new PlantWandListener.BlockPosition(WORLD, 9, 64, 9));

        assertTrue(PlantWandListener.nearSelection(selection, 5.0, 5.0));
        assertTrue(PlantWandListener.nearSelection(selection, 5.0 + 128.0, 5.0));
        assertFalse(PlantWandListener.nearSelection(selection, 5.0 + 129.0, 5.0));
        assertFalse(PlantWandListener.nearSelection(selection, 100.0, 100.0));
        assertFalse(PlantWandListener.nearSelection(new PlantWandListener.Selection(
                new PlantWandListener.BlockPosition(WORLD, 0, 64, 0),
                new PlantWandListener.BlockPosition(WORLD, 20_000, 64, 0)), 0.0, 0.0));
    }

    @Test
    void skipsColumnsInUnloadedChunksInsteadOfLoadingThem() {
        Block loaded = soil(Material.FARMLAND, true);
        Block unloaded = soil(Material.FARMLAND, true);
        World world = world(Map.of(15, loaded, 16, unloaded), Set.of(0));
        var selection = new PlantWandListener.Selection(
                new PlantWandListener.BlockPosition(WORLD, 15, 64, 0),
                new PlantWandListener.BlockPosition(WORLD, 16, 64, 0));

        assertEquals(List.of(loaded), PlantWandListener.findSoil(world, selection, Material.FARMLAND));
    }

    @Test
    void mapsEveryFarmlandCropToItsPlantingItemAndBlock() {
        assertEquals(Material.FARMLAND, PlantWandListener.crop("wheat").soil());
        assertCrop("wheat", Material.WHEAT_SEEDS, Material.WHEAT);
        assertCrop("carrot", Material.CARROT, Material.CARROTS);
        assertCrop("potato", Material.POTATO, Material.POTATOES);
        assertCrop("beetroot", Material.BEETROOT_SEEDS, Material.BEETROOTS);
        assertCrop("pumpkin", Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM);
        assertCrop("melon", Material.MELON_SEEDS, Material.MELON_STEM);
        assertCrop("torchflower", Material.TORCHFLOWER_SEEDS, Material.TORCHFLOWER_CROP);
        assertCrop("pitcher", Material.PITCHER_POD, Material.PITCHER_CROP);
        assertNull(PlantWandListener.crop("kelp"));
    }

    @Test
    void plantsNetherWartOnlyOnEmptySoulSand() {
        var wart = PlantWandListener.crop("nether_wart");
        assertCrop("nether_wart", Material.NETHER_WART, Material.NETHER_WART);
        assertEquals(Material.SOUL_SAND, wart.soil());
        Block farmland = soil(Material.FARMLAND, true);
        Block soulSand = soil(Material.SOUL_SAND, true);
        Block occupiedSoulSand = soil(Material.SOUL_SAND, false);
        Block soulSoil = soil(Material.SOUL_SOIL, true);
        World world = world(Map.of(0, farmland, 1, soulSand, 2, occupiedSoulSand, 3, soulSoil), Set.of(0));
        var selection = new PlantWandListener.Selection(
                new PlantWandListener.BlockPosition(WORLD, 0, 64, 0),
                new PlantWandListener.BlockPosition(WORLD, 3, 64, 0));

        assertEquals(List.of(soulSand), PlantWandListener.findSoil(world, selection, wart.soil()));
        assertEquals(List.of(farmland), PlantWandListener.findSoil(world, selection,
                PlantWandListener.crop("wheat").soil()));
        assertEquals(List.of(farmland, soulSand), PlantWandListener.findSoil(world, selection,
                Material.FARMLAND, Material.SOUL_SAND));
    }

    /** A one-row world keyed by block X, with only the listed chunk X coordinates loaded. */
    private static World world(Map<Integer, Block> blocksByX, Set<Integer> loadedChunkXs) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> loadedChunkXs.contains((int) args[0]);
                    case "getBlockAt" -> blocksByX.get((int) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Block soil(Material material, boolean airAbove) {
        Block above = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> airAbove);
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> material;
                    case "getRelative" -> above;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void capsParticleLocationsWithoutDroppingSmallSelections() {
        assertEquals(1, PlantWandListener.effectStride(20, 240));
        assertEquals(2, PlantWandListener.effectStride(241, 240));
        assertEquals(5, PlantWandListener.effectStride(1_000, 240));
    }

    private static void assertCrop(String id, Material seed, Material block) {
        var crop = PlantWandListener.crop(id);
        assertEquals(seed, crop.seed());
        assertEquals(block, crop.block());
    }
}
