package com.onemillioncrops.model;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.CaveVinesPlant;

import java.util.EnumSet;
import java.util.Set;

public record CropDefinition(
        String id,
        Material item,
        Set<Material> sources,
        String displayMiniMessage
) {
    /*
     * These blocks use Ageable as a growth timer or structural state rather
     * than as an indication that the block is ready to harvest. Requiring
     * maximum age for them prevents natural vertical growth from ever
     * becoming eligible. Player-placed re-drops are handled separately by
     * PlacedSourceTracker.
     */
    private static final Set<Material> AGE_INDEPENDENT_SOURCES = EnumSet.of(
            Material.SUGAR_CANE,
            Material.CACTUS,
            Material.BAMBOO,
            Material.BAMBOO_SAPLING,
            Material.KELP,
            Material.KELP_PLANT,
            Material.CHORUS_PLANT,
            Material.CHORUS_FLOWER
    );

    public boolean matchesSource(BlockState state, boolean requireMature) {
        if (!sources.contains(state.getType())) {
            return false;
        }
        if (!requireMature) {
            return true;
        }
        // Cave vine age is a growth timer; what matters is whether the vine is carrying berries.
        if (state.getBlockData() instanceof CaveVinesPlant vines) {
            return vines.isBerries();
        }
        if (maturityAgeApplies(state.getType()) && state.getBlockData() instanceof Ageable ageable) {
            return ageAllowsHarvest(state.getType(), ageable.getAge(), ageable.getMaximumAge());
        }
        return true;
    }

    /** Sweet berry bushes give berries from age 2, one step before their maximum age. */
    static boolean ageAllowsHarvest(Material source, int age, int maximumAge) {
        if (source == Material.SWEET_BERRY_BUSH) {
            return age >= 2;
        }
        return age >= maximumAge;
    }

    static boolean maturityAgeApplies(Material source) {
        return !AGE_INDEPENDENT_SOURCES.contains(source);
    }
}
