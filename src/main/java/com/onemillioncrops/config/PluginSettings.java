package com.onemillioncrops.config;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PluginSettings(
        long target,
        ParticipantMode participantMode,
        Set<UUID> participantAllowlist,
        boolean allowAutomatedFarms,
        boolean blockPlayerRedrops,
        boolean requireMatureCrops,
        int autosaveSeconds,
        String databaseFile,
        boolean backupBeforeReset,
        boolean scoreboardEnabled,
        int scoreboardAnimationTicks,
        int scoreboardRefreshTicks,
        int scoreboardPageTicks,
        int scoreboardCropsPerPage,
        int scoreboardTitleAnimationFrames,
        List<String> scoreboardTitleFrames,
        int guiAnimationTicks,
        boolean guiPickupSound,
        List<Integer> milestones,
        int celebrationFireworks,
        int fireworkGapTicks,
        String completionSound,
        float completionVolume,
        float completionPitch
) {
    public enum ParticipantMode {
        EVERYONE,
        ALLOWLIST
    }

    public PluginSettings withAllowAutomatedFarms(boolean enabled) {
        return new PluginSettings(target, participantMode, participantAllowlist, enabled, blockPlayerRedrops,
                requireMatureCrops, autosaveSeconds, databaseFile, backupBeforeReset, scoreboardEnabled,
                scoreboardAnimationTicks, scoreboardRefreshTicks, scoreboardPageTicks, scoreboardCropsPerPage,
                scoreboardTitleAnimationFrames, scoreboardTitleFrames, guiAnimationTicks, guiPickupSound,
                milestones, celebrationFireworks, fireworkGapTicks, completionSound, completionVolume,
                completionPitch);
    }

    public boolean mayContribute(UUID uuid) {
        return participantMode == ParticipantMode.EVERYONE || participantAllowlist.contains(uuid);
    }
}
