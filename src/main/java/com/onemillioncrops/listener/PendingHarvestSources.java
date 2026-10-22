package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Remembers crop sources that were just destroyed with no player harvest and no drop event,
 * such as physics, explosions or a mob, so that the drops spawning there a moment later can
 * be marked eligible. The game spawns those drops inside the destroyed block in the same tick.
 * <p>
 * Each position may claim one stack, drawn from an item budget shared by the blocks of one
 * crop destroyed together, because an explosion merges their drops into a single stack at one
 * of them. The budget stops a container or mob spilling at the same spot from riding along.
 */
final class PendingHarvestSources {
    /** Drops spawn in the same tick as the destroy, so a little slack costs nothing. */
    static final int LIFETIME_TICKS = 3;
    /** More than any vanilla crop block drops without a tool; a melon drops at most 7 slices. */
    static final int ITEMS_PER_SOURCE = 9;

    private final IntSupplier currentTick;
    private final Map<Position, Source> sources = new HashMap<>();
    private int lastPruneTick;

    PendingHarvestSources(IntSupplier currentTick) {
        this.currentTick = currentTick;
        this.lastPruneTick = currentTick.getAsInt();
    }

    /**
     * Records blocks destroyed together. A crop with any player-placed block among them is
     * left out, because the merged drops of placed and grown blocks cannot be told apart.
     */
    void record(Collection<Candidate> destroyed) {
        if (destroyed.isEmpty()) {
            return;
        }
        prune();
        Map<String, List<Candidate>> byCrop = new LinkedHashMap<>();
        Set<String> tainted = new HashSet<>();
        for (Candidate candidate : destroyed) {
            if (candidate.playerPlaced()) {
                tainted.add(candidate.cropId());
            } else {
                byCrop.computeIfAbsent(candidate.cropId(), id -> new ArrayList<>()).add(candidate);
            }
        }
        byCrop.keySet().removeAll(tainted);
        int expiresAt = currentTick.getAsInt() + LIFETIME_TICKS;
        byCrop.forEach((cropId, candidates) -> {
            Budget budget = new Budget(candidates.size() * ITEMS_PER_SOURCE);
            for (Candidate candidate : candidates) {
                sources.put(candidate.position(), new Source(cropId, candidate.item(), expiresAt, budget));
            }
        });
    }

    /**
     * Claims a stack spawning at {@code position} for the crop destroyed there.
     *
     * @return the crop the stack may count as, or null when nothing matching is pending
     */
    String claim(Position position, Material item, int amount) {
        prune();
        Source source = sources.get(position);
        if (source == null || expired(source) || source.claimed || source.item != item
                || amount <= 0 || amount > source.budget.remaining) {
            return null;
        }
        source.claimed = true;
        source.budget.remaining -= amount;
        return source.cropId;
    }

    int size() {
        return sources.size();
    }

    private void prune() {
        int now = currentTick.getAsInt();
        if (now == lastPruneTick) {
            return;
        }
        lastPruneTick = now;
        sources.values().removeIf(this::expired);
    }

    private boolean expired(Source source) {
        // Compared by difference so that the tick counter wrapping around does no harm.
        return currentTick.getAsInt() - source.expiresAt > 0;
    }

    record Candidate(Position position, String cropId, Material item, boolean playerPlaced) {
    }

    record Position(UUID worldId, int x, int y, int z) {
        static Position of(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class Budget {
        private int remaining;

        private Budget(int remaining) {
            this.remaining = remaining;
        }
    }

    private static final class Source {
        private final String cropId;
        private final Material item;
        private final int expiresAt;
        private final Budget budget;
        private boolean claimed;

        private Source(String cropId, Material item, int expiresAt, Budget budget) {
            this.cropId = cropId;
            this.item = item;
            this.expiresAt = expiresAt;
            this.budget = budget;
        }
    }
}
