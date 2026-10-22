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
    // What changed since the last snapshot was taken, so a routine save writes only those rows.
    private final Set<String> changedTotals = new LinkedHashSet<>();
    private final Map<UUID, Set<String>> changedContributions = new LinkedHashMap<>();
    /** Set after a failed save: the rows it was writing are no longer tracked, so write everything. */
    private boolean fullSaveNeeded;

    public ProgressService(Map<String, CropDefinition> crops, long target, List<Integer> milestones,
                           ProgressSnapshot loaded) {
        this.crops = crops;
        this.target = target;
        this.milestones = milestones.stream().filter(value -> value > 0 && value < 100).distinct().sorted().toList();
        for (String cropId : crops.keySet()) {
            // Keep the stored total as is, even above the target: lowering the target to test and then
            // raising it again must give back the old total. amount() caps it for display instead.
            long storedAmount = Math.max(0L, loaded.totals().getOrDefault(cropId, 0L));
            totals.put(cropId, storedAmount);
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
        if (oldAmount >= target) {
            return IncrementResult.NONE;
        }
        long actual = Math.min(requested, target - oldAmount);
        long newAmount = oldAmount + actual;
        totals.put(cropId, newAmount);
        changedTotals.add(cropId);
        if (player != null) {
            contributions.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                    .merge(cropId, actual, Numbers::saturatingAdd);
            changedContributions.computeIfAbsent(player, ignored -> new LinkedHashSet<>()).add(cropId);
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
        clearChanges();
    }

    public synchronized void resetCrop(String cropId) {
        if (!totals.containsKey(cropId)) {
            return;
        }
        totals.put(cropId, 0L);
        completed.put(cropId, false);
        contributions.values().forEach(values -> values.remove(cropId));
        changedTotals.remove(cropId);
        changedContributions.values().forEach(values -> values.remove(cropId));
        changedContributions.values().removeIf(Set::isEmpty);
    }

    /** The crop's total capped at the target; the stored total can be higher after the target is lowered. */
    public synchronized long amount(String cropId) {
        return Math.min(totals.getOrDefault(cropId, 0L), target);
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

    /**
     * Returns only the totals and contributions changed since the last snapshot was taken, or null when
     * nothing changed. After {@link #markDirty()} it returns the full state instead.
     */
    public synchronized ProgressSnapshot takeDirtySnapshot() {
        if (!isDirty()) {
            return null;
        }
        ProgressSnapshot result = fullSaveNeeded ? snapshot() : changes();
        clearChanges();
        return result;
    }

    /**
     * Returns the full current state whether or not it changed, clearing the tracked changes so a queued
     * autosave cannot write the same state again after the caller has persisted it.
     */
    public synchronized ProgressSnapshot takeSnapshot() {
        clearChanges();
        return snapshot();
    }

    /** Makes the next dirty snapshot a full one. Call it when a save fails, so nothing it carried is lost. */
    public synchronized void markDirty() {
        fullSaveNeeded = true;
    }

    public synchronized boolean isDirty() {
        return fullSaveNeeded || !changedTotals.isEmpty() || !changedContributions.isEmpty();
    }

    private ProgressSnapshot changes() {
        Map<String, Long> changedTotalValues = new LinkedHashMap<>();
        Map<String, Boolean> changedCompleted = new LinkedHashMap<>();
        for (String cropId : changedTotals) {
            changedTotalValues.put(cropId, totals.get(cropId));
            changedCompleted.put(cropId, completed.getOrDefault(cropId, false));
        }
        Map<UUID, Map<String, Long>> changedContributionValues = new LinkedHashMap<>();
        changedContributions.forEach((player, cropIds) -> {
            Map<String, Long> current = contributions.getOrDefault(player, Map.of());
            Map<String, Long> values = new LinkedHashMap<>();
            for (String cropId : cropIds) {
                Long amount = current.get(cropId);
                if (amount != null) {
                    values.put(cropId, amount);
                }
            }
            if (!values.isEmpty()) {
                changedContributionValues.put(player, Map.copyOf(values));
            }
        });
        return new ProgressSnapshot(Map.copyOf(changedTotalValues), Map.copyOf(changedContributionValues),
                Map.copyOf(changedCompleted));
    }

    private void clearChanges() {
        changedTotals.clear();
        changedContributions.clear();
        fullSaveNeeded = false;
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
