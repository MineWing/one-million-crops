package com.onemillioncrops.service;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Stores player-placed crop source coordinates in the owning chunk's persistent data. */
public final class PlacedSourceTracker {
    private final NamespacedKey positionsKey;

    public PlacedSourceTracker(JavaPlugin plugin) {
        positionsKey = new NamespacedKey(plugin, "player_placed_sources");
    }

    public void mark(Block block) {
        Chunk chunk = block.getChunk();
        int packed = pack(block);
        int[] positions = positions(chunk);
        if (Arrays.stream(positions).anyMatch(value -> value == packed)) {
            return;
        }
        int[] expanded = Arrays.copyOf(positions, positions.length + 1);
        expanded[positions.length] = packed;
        chunk.getPersistentDataContainer().set(positionsKey, PersistentDataType.INTEGER_ARRAY, expanded);
    }

    public boolean contains(Block block) {
        int packed = pack(block);
        return Arrays.stream(positions(block.getChunk())).anyMatch(value -> value == packed);
    }

    public boolean consume(Block block) {
        Chunk chunk = block.getChunk();
        int packed = pack(block);
        int[] positions = positions(chunk);
        int found = -1;
        for (int index = 0; index < positions.length; index++) {
            if (positions[index] == packed) {
                found = index;
                break;
            }
        }
        if (found < 0) {
            return false;
        }
        if (positions.length == 1) {
            chunk.getPersistentDataContainer().remove(positionsKey);
        } else {
            int[] reduced = new int[positions.length - 1];
            System.arraycopy(positions, 0, reduced, 0, found);
            System.arraycopy(positions, found + 1, reduced, found, positions.length - found - 1);
            chunk.getPersistentDataContainer().set(positionsKey, PersistentDataType.INTEGER_ARRAY, reduced);
        }
        return true;
    }

    /**
     * Carries the markers of blocks that all move one step in {@code direction} at once,
     * such as a piston push. Each block resolves its own chunk, so moves across a chunk
     * border keep their marker.
     *
     * @return the destinations that are now marked
     */
    public List<Block> move(Collection<Block> blocks, BlockFace direction) {
        List<Block> destinations = relocate(blocks, this::consume, block -> block.getRelative(direction));
        destinations.forEach(this::mark);
        return destinations;
    }

    /**
     * Consumes every tracked source before anything is marked, so that in a chain of pushed
     * blocks a marker that has just arrived is never consumed by the block moving out of
     * that position.
     *
     * @return the destinations of the sources that were tracked, still to be marked
     */
    static <T> List<T> relocate(Collection<T> sources, Predicate<T> consume, UnaryOperator<T> destination) {
        List<T> destinations = new ArrayList<>();
        for (T source : sources) {
            if (consume.test(source)) {
                destinations.add(destination.apply(source));
            }
        }
        return destinations;
    }

    private int[] positions(Chunk chunk) {
        int[] stored = chunk.getPersistentDataContainer().get(positionsKey, PersistentDataType.INTEGER_ARRAY);
        return stored == null ? new int[0] : stored;
    }

    static int pack(Block block) {
        return (block.getY() << 8) | ((block.getZ() & 15) << 4) | (block.getX() & 15);
    }
}
