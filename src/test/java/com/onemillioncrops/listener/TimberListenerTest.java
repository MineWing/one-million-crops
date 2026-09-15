package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TimberListenerTest {
    @Test
    void rejectsBuildingsAndBarePillars() {
        assertFalse(TimberListener.validStructure(3, 10, 1, 3, 0));
        assertFalse(TimberListener.validStructure(8, 0, 1, 8, 0));
        assertFalse(TimberListener.validStructure(20, 30, 7, 15, 5));
        assertFalse(TimberListener.validStructure(20, 30, 6, 5, 15));
        assertTrue(TimberListener.validStructure(4, 10, 1, 4, 0));
        assertTrue(TimberListener.validStructure(20, 30, 6, 10, 10));
        assertFalse(TimberListener.isTreeTrunk(Material.STRIPPED_OAK_LOG));
        assertFalse(TimberListener.isTreeTrunk(Material.OAK_WOOD));
        assertTrue(TimberListener.isTreeTrunk(Material.PALE_OAK_LOG));
        assertTrue(TimberListener.isTreeTrunk(Material.CRIMSON_STEM));
        assertFalse(TimberListener.isAxe(Material.DIAMOND_PICKAXE));
    }

    @Test
    void matchesTheNetherCanopyToTheStem() {
        assertTrue(TimberListener.isNetherFungusFoliage(Material.WARPED_STEM, Material.WARPED_WART_BLOCK));
        assertTrue(TimberListener.isNetherFungusFoliage(Material.CRIMSON_STEM, Material.SHROOMLIGHT));
        assertFalse(TimberListener.isNetherFungusFoliage(Material.WARPED_STEM, Material.NETHER_WART_BLOCK));
    }

    @Test
    void breaksBaseOnceThenCanopyDownAndStopsAtProtection() {
        Block base = block(64), middle = block(65), top = block(66);
        List<Block> broken = new ArrayList<>();
        TimberListener.breakTree(base, Set.of(base, middle, top), block -> {
            broken.add(block);
            return block != top;
        }, () -> true);
        assertEquals(List.of(base, top), broken);
    }

    @Test
    void cancelledBaseLeavesRestOfTreeIntact() {
        Block base = block(64), top = block(65);
        List<Block> broken = new ArrayList<>();
        TimberListener.breakTree(base, Set.of(base, top), block -> {
            broken.add(block);
            return false;
        }, () -> true);
        assertEquals(List.of(base), broken);
    }

    @Test
    void brokenAxeStopsFurtherBreaking() {
        Block base = block(64), top = block(65);
        List<Block> broken = new ArrayList<>();
        TimberListener.breakTree(base, Set.of(base, top), broken::add, () -> false);
        assertEquals(List.of(base), broken);
    }

    @Test
    void breaksEveryBlockExactlyOnceWhenAllowed() {
        Block base = block(64), middle = block(65), top = block(66);
        List<Block> broken = new ArrayList<>();
        TimberListener.breakTree(base, Set.of(base, middle, top), broken::add, () -> true);
        assertEquals(List.of(base, top, middle), broken);
    }

    private static Block block(int y) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getY" -> y;
                    case "hashCode" -> y;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Block at " + y;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
