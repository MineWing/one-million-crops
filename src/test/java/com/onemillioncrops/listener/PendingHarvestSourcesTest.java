package com.onemillioncrops.listener;

import com.onemillioncrops.listener.PendingHarvestSources.Candidate;
import com.onemillioncrops.listener.PendingHarvestSources.Position;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingHarvestSourcesTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final Position CANE = new Position(WORLD, 0, 65, 0);

    private final AtomicInteger tick = new AtomicInteger(100);
    private final PendingHarvestSources pending = new PendingHarvestSources(tick::get);

    @Test
    void marksTheDropOfAnUpperSugarCanePiece() {
        pending.record(List.of(cane(CANE)));

        assertEquals("sugar_cane", pending.claim(CANE, Material.SUGAR_CANE, 1));
    }

    @Test
    void ignoresDropsOfAnotherItemOrPosition() {
        pending.record(List.of(cane(CANE)));

        assertNull(pending.claim(CANE, Material.WHEAT, 1));
        assertNull(pending.claim(new Position(WORLD, 0, 64, 0), Material.SUGAR_CANE, 1));
        assertNull(pending.claim(new Position(UUID.randomUUID(), 0, 65, 0), Material.SUGAR_CANE, 1));
    }

    @Test
    void expiresAfterAFewTicks() {
        pending.record(List.of(cane(CANE)));
        tick.addAndGet(PendingHarvestSources.LIFETIME_TICKS);
        Position other = new Position(WORLD, 1, 65, 0);
        pending.record(List.of(cane(other)));
        tick.incrementAndGet();

        assertNull(pending.claim(CANE, Material.SUGAR_CANE, 1));
        assertEquals("sugar_cane", pending.claim(other, Material.SUGAR_CANE, 1));
        assertEquals(1, pending.size());
    }

    @Test
    void survivesTheTickCounterWrappingAround() {
        tick.set(Integer.MAX_VALUE);
        pending.record(List.of(cane(CANE)));
        tick.incrementAndGet();

        assertEquals("sugar_cane", pending.claim(CANE, Material.SUGAR_CANE, 1));
    }

    @Test
    void letsEachPositionClaimOneStack() {
        // A container or mob spilling more stacks where the crop stood gets nothing more.
        pending.record(List.of(cane(CANE)));

        assertEquals("sugar_cane", pending.claim(CANE, Material.SUGAR_CANE, 1));
        assertNull(pending.claim(CANE, Material.SUGAR_CANE, 1));
    }

    @Test
    void refusesAStackLargerThanTheCropCouldDrop() {
        pending.record(List.of(cane(CANE)));

        assertNull(pending.claim(CANE, Material.SUGAR_CANE, 64));
        assertEquals("sugar_cane", pending.claim(CANE, Material.SUGAR_CANE, 1));
    }

    @Test
    void sharesOneBudgetForAnExplosionsMergedDrops() {
        List<Candidate> blast = List.of(
                cane(new Position(WORLD, 0, 65, 0)),
                cane(new Position(WORLD, 0, 66, 0)),
                cane(new Position(WORLD, 0, 67, 0)),
                cane(new Position(WORLD, 0, 68, 0)));
        pending.record(blast);

        // The explosion drops all four pieces as one stack at one of the positions.
        assertEquals("sugar_cane", pending.claim(new Position(WORLD, 0, 67, 0), Material.SUGAR_CANE, 4));
        int left = 4 * PendingHarvestSources.ITEMS_PER_SOURCE - 4;
        assertNull(pending.claim(new Position(WORLD, 0, 65, 0), Material.SUGAR_CANE, left + 1));
        assertEquals("sugar_cane", pending.claim(new Position(WORLD, 0, 65, 0), Material.SUGAR_CANE, left));
    }

    @Test
    void leavesOutACropWithAPlayerPlacedBlockInTheSameExplosion() {
        Position placed = new Position(WORLD, 1, 65, 0);
        Position wheat = new Position(WORLD, 2, 65, 0);
        pending.record(List.of(
                cane(CANE),
                new Candidate(placed, "sugar_cane", Material.SUGAR_CANE, true),
                new Candidate(wheat, "wheat", Material.WHEAT, false)));

        assertNull(pending.claim(CANE, Material.SUGAR_CANE, 1));
        assertNull(pending.claim(placed, Material.SUGAR_CANE, 1));
        assertEquals("wheat", pending.claim(wheat, Material.WHEAT, 1));
    }

    @Test
    void neverRecordsAPlayerPlacedSource() {
        pending.record(List.of(new Candidate(CANE, "sugar_cane", Material.SUGAR_CANE, true)));

        assertNull(pending.claim(CANE, Material.SUGAR_CANE, 1));
        assertEquals(0, pending.size());
    }

    private static Candidate cane(Position position) {
        return new Candidate(position, "sugar_cane", Material.SUGAR_CANE, false);
    }
}
