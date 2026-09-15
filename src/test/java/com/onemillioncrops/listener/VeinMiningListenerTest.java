package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VeinMiningListenerTest {
    @Test
    void joinsDiagonalAndMixedDeepslateOresButNotDifferentOres() {
        Grid grid = new Grid();
        Block base = grid.ore(0, 0, Material.DIAMOND_ORE);
        Block diagonal = grid.ore(1, 1, Material.DEEPSLATE_DIAMOND_ORE);
        grid.ore(2, 1, Material.IRON_ORE);
        grid.ore(5, 5, Material.DIAMOND_ORE);
        assertEquals(Set.of(base, diagonal), VeinMiningListener.connectedVein(base, ignored -> true));
    }

    @Test
    void rejectsOversizedVeinsAndNeverReadsUnownedBlockState() {
        Grid grid = new Grid();
        for (int x = 0; x < 65; x++) grid.ore(x, 0, Material.COAL_ORE);
        assertTrue(VeinMiningListener.connectedVein(grid.block(0, 64, 0), ignored -> true).isEmpty());
        Grid boundary = new Grid();
        Block base = boundary.ore(0, 0, Material.DIAMOND_ORE);
        boundary.forbiddenX = 1;
        assertTrue(VeinMiningListener.connectedVein(base, block -> block.getX() < 1).isEmpty());
    }

    @Test
    void supportsRivetSpecialBlocksAndRequiresAPickaxe() {
        assertEquals("ANCIENT_DEBRIS", VeinMiningListener.oreKey(Material.ANCIENT_DEBRIS));
        assertEquals("GLOWSTONE", VeinMiningListener.oreKey(Material.GLOWSTONE));
        assertEquals("NETHER_QUARTZ_ORE", VeinMiningListener.oreKey(Material.NETHER_QUARTZ_ORE));
        assertNull(VeinMiningListener.oreKey(Material.STONE));
        assertTrue(VeinMiningListener.isPickaxe(Material.DIAMOND_PICKAXE));
        assertFalse(VeinMiningListener.isPickaxe(Material.DIAMOND_AXE));
    }

    @Test
    void stopsAtProtectedBlockOrBrokenPickaxe() {
        Grid grid = new Grid();
        Block first = grid.ore(0, 0, Material.COAL_ORE);
        Block second = grid.ore(1, 0, Material.COAL_ORE);
        Block third = grid.ore(2, 0, Material.COAL_ORE);
        Set<Block> vein = new LinkedHashSet<>(List.of(first, second, third));
        List<Block> attempts = new ArrayList<>();
        VeinMiningListener.mine(vein, block -> { attempts.add(block); return block != second; }, () -> true);
        assertEquals(List.of(first, second), attempts);
        attempts.clear();
        VeinMiningListener.mine(vein, attempts::add, attempts::isEmpty);
        assertEquals(List.of(first), attempts);
    }

    private record Position(int x, int y, int z) { }

    private static final class Grid {
        final Map<Position, Material> materials = new HashMap<>();
        final Map<Position, Block> blocks = new HashMap<>();
        int forbiddenX = Integer.MAX_VALUE;
        final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    default -> throw new AssertionError(method.getName());
                });

        Block ore(int x, int z, Material material) {
            materials.put(new Position(x, 64, z), material);
            return block(x, 64, z);
        }

        Block block(int x, int y, int z) {
            Position position = new Position(x, y, z);
            return blocks.computeIfAbsent(position, key -> (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                    new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getX" -> x;
                        case "getY" -> y;
                        case "getZ" -> z;
                        case "getWorld" -> world;
                        case "getType" -> {
                            assertTrue(x < forbiddenX, "Read outside owned region");
                            yield materials.getOrDefault(position, Material.STONE);
                        }
                        case "getRelative" -> block(x + (int) args[0], y + (int) args[1], z + (int) args[2]);
                        case "hashCode" -> position.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> position.toString();
                        default -> throw new AssertionError(method.getName());
                    }));
        }
    }
}
