package com.onemillioncrops.listener;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;
import java.util.function.IntToLongFunction;

/**
 * Credits crops knocked loose by a farm (no player involved) straight from a block's drop
 * list. Only the amount the progress tracker actually accepted is taken out of the drops;
 * anything it refused (maintenance, a finished crop) still falls to the ground.
 */
final class AutomatedDrops {
    private AutomatedDrops() {
    }

    /**
     * @param credit receives the matching amount and returns how many were accepted
     * @return the number of items credited and removed from {@code drops}
     */
    static long credit(List<ItemStack> drops, Material item, IntToLongFunction credit) {
        int available = 0;
        for (ItemStack stack : drops) {
            if (stack.getType() == item) {
                available += stack.getAmount();
            }
        }
        if (available <= 0) {
            return 0;
        }
        long accepted = credit.applyAsLong(available);
        if (accepted > 0) {
            removeUpTo(drops, item, accepted);
        }
        return Math.max(0, accepted);
    }

    private static void removeUpTo(List<ItemStack> drops, Material item, long limit) {
        int[] amounts = drops.stream()
                .filter(stack -> stack.getType() == item)
                .mapToInt(ItemStack::getAmount)
                .toArray();
        int[] remaining = remainingAfterTaking(amounts, limit);
        int index = 0;
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack.getType() != item) {
                continue;
            }
            // Remove emptied stacks outright: a zero-amount stack turns into air.
            if (remaining[index] <= 0) {
                iterator.remove();
            } else {
                stack.setAmount(remaining[index]);
            }
            index++;
        }
    }

    /** Takes up to {@code limit} items from the stacks in order and returns what is left of each. */
    static int[] remainingAfterTaking(int[] amounts, long limit) {
        int[] remaining = amounts.clone();
        long left = Math.max(0, limit);
        for (int index = 0; index < remaining.length && left > 0; index++) {
            int take = (int) Math.min(left, Math.max(0, remaining[index]));
            remaining[index] -= take;
            left -= take;
        }
        return remaining;
    }
}
