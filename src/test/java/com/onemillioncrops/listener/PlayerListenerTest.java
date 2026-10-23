package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListenerTest {
    @Test
    void protectsPlayersInsideSweetBerryBushes() {
        assertTrue(PlayerListener.shouldCancelBerryBushCollision(true, true, Material.SWEET_BERRY_BUSH));
    }

    @Test
    void leavesBerryBushesAloneWhenProtectionIsDisabled() {
        assertFalse(PlayerListener.shouldCancelBerryBushCollision(false, true, Material.SWEET_BERRY_BUSH));
    }

    @Test
    void leavesOtherBlocksAndEntitiesUnchanged() {
        assertFalse(PlayerListener.shouldCancelBerryBushCollision(true, true, Material.COBWEB));
        assertFalse(PlayerListener.shouldCancelBerryBushCollision(true, false, Material.SWEET_BERRY_BUSH));
    }
}
