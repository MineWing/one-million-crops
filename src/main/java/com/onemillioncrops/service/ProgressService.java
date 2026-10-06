package com.onemillioncrops.service;

import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.model.ProgressSnapshot;
import com.onemillioncrops.util.Numbers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProgressService {
    private final Map<String, CropDefinition> crops;
    private final long target;
    private final List<Integer> milestones;
    private final Map<String, Long> totals = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> contributions = new LinkedHashMap<>();
    private final Map<String, Boolean> completed = new LinkedHashMap<>();
    private boolean dirty;

    public ProgressService(Map<String, CropDefinition> crops, long target, List<Integer> milestones,
                           ProgressSnapshot loaded) {
        this.crops = crops;
        this.target = target;
        this.milestones = milestones.stream().filter(value -> value > 0 && value < 100).distinct().sorted().toList();
        for (String cropId : crops.keySet()) {
            long storedAmount = Math.max(0L, loaded.totals().getOrDefault(cropId, 0L));
            long amount = Math.min(storedAmount, target);
            totals.put(cropId, amount);
            // Derive this from the total so changing the configured target cannot leave stale completion state.
            completed.put(cropId, storedAmount >= target);
        }
        for (Map.Entry<UUID, Map<String, Long>> player : loaded.contributions().entrySet()) {
            Map<String, Long> filtered = new LinkedHashMap<>();
            for (String cropId : crops.keySet()) {
                long amount = Math.max(0L, player.getValue().getOrDefault(cropId, 0L));
                if (amount > 0) {
                    filtered.put(cropId, amount);
                }
            }
            if (!filtered.isEmpty()) {
                contributions.put(player.getKey(), filtered);
            }
        }
    }

    public synchronized IncrementResult add(UUID player, String cropId, long requested) {
        return add(cropId, requested, player);
    }

    public synchronized IncrementResult addUnattributed(String cropId, long requested) {
        return add(cropId, requested, null);
    }

    private IncrementResult add(String cropId, long requested, UUID player) {
        if (requested <= 0 || !totals.containsKey(cropId)) {
            return IncrementResult.NONE;
        }
        long oldAmount = totals.get(cropId);
        long actual = Math.min(requested, target - oldAmount);
        if (actual <= 0) {
            return IncrementResult.NONE;
        }
        long newAmount = oldAmount + actual;
        totals.put(cropId, newAmount);
        if (player != null) {
            contributions.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                    .merge(cropId, actual, Numbers::saturatingAdd);
        }

        List<Integer> crossed = new ArrayList<>();
        for (int milestone : milestones) {
            long threshold = ceilPercent(target, milestone);
            if (oldAmount < threshold && newAmount >= threshold) {
                crossed.add(milestone);
            }
        }
        boolean justCompleted = oldAmount < target && newAmount >= target && !completed.getOrDefault(cropId, false);
        if (justCompleted) {
            completed.put(cropId, true);
        }
        dirty = true;
        return new IncrementResult(actual, List.copyOf(crossed), justCompleted, justCompleted && allCompleted());
    }

    private static long ceilPercent(long target, int percent) {
        long whole = (target / 100L) * percent;
        long remainder = target % 100L;
        return whole + (remainder * percent + 99L) / 100L;
    }

    public synchronized void resetAll() {
        totals.replaceAll((ignored, value) -> 0L);
        completed.replaceAll((ignored, value) -> false);
        contributions.clear();
        dirty = false;
    }

    public synchronized void resetCrop(String cropId) {
        if (!totals.containsKey(cropId)) {
            return;
        }
        totals.put(cropId, 0L);
        completed.put(cropId, false);
        contributions.values().forEach(values -> values.remove(cropId));
        dirty = false;
    }

    public synchronized long amount(String cropId) {
        return totals.getOrDefault(cropId, 0L);
    }

    public synchronized long contribution(UUID player, String cropId) {
        return contributions.getOrDefault(player, Map.of()).getOrDefault(cropId, 0L);
    }

    public synchronized int completedCount() {
        return (int) completed.values().stream().filter(Boolean::booleanValue).count();
    }

    public synchronized boolean allCompleted() {
        return !completed.isEmpty() && completed.values().stream().allMatch(Boolean::booleanValue);
    }

    public synchronized Set<UUID> contributors() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(contributions.keySet()));
    }

    public synchronized ProgressSnapshot snapshot() {
        Map<UUID, Map<String, Long>> contributionsCopy = new LinkedHashMap<>();
        contributions.forEach((uuid, values) -> contributionsCopy.put(uuid, Map.copyOf(values)));
        return new ProgressSnapshot(Map.copyOf(totals), Map.copyOf(contributionsCopy), Map.copyOf(completed));
    }

    public synchronized ProgressSnapshot takeDirtySnapshot() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return snapshot();
    }

    /**
     * Returns the current state whether or not it changed, clearing the dirty flag so a queued
     * autosave cannot write the same state again after the caller has persisted it.
     */
    public synchronized ProgressSnapshot takeSnapshot() {
        dirty = false;
        return snapshot();
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public long target() {
        return target;
    }

    public Map<String, CropDefinition> crops() {
        return crops;
    }

    public record IncrementResult(long added, List<Integer> crossedMilestones,
                                  boolean completed, boolean allCompleted) {
        public static final IncrementResult NONE = new IncrementResult(0L, List.of(), false, false);
    }
}
