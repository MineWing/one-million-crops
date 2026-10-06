package com.onemillioncrops.placeholder;

import com.onemillioncrops.service.ProgressService;
import com.onemillioncrops.util.Numbers;
import com.onemillioncrops.util.Text;

import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

public final class PlaceholderValues {
    private PlaceholderValues() {
    }

    public static String resolve(ProgressService progress, UUID playerId, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        long total = total(progress);
        long goal = Numbers.saturatingMultiply(progress.target(), progress.crops().size());

        return switch (key) {
            case "total" -> Long.toString(total);
            case "total_formatted" -> Text.number(total);
            case "target" -> Long.toString(progress.target());
            case "target_formatted" -> Text.number(progress.target());
            case "goal" -> Long.toString(goal);
            case "goal_formatted" -> Text.number(goal);
            case "remaining" -> Long.toString(Math.max(0L, goal - total));
            case "remaining_formatted" -> Text.number(Math.max(0L, goal - total));
            case "percent" -> Text.percent(total, goal);
            case "completed_crops" -> Integer.toString(progress.completedCount());
            case "crop_count" -> Integer.toString(progress.crops().size());
            case "all_completed" -> Boolean.toString(progress.allCompleted());
            case "player_total" -> Long.toString(playerTotal(progress, playerId));
            case "player_total_formatted" -> Text.number(playerTotal(progress, playerId));
            default -> resolveCrop(progress, playerId, key);
        };
    }

    private static String resolveCrop(ProgressService progress, UUID playerId, String key) {
        if (!key.startsWith("crop_")) {
            return null;
        }
        String request = key.substring("crop_".length());
        String cropId = progress.crops().keySet().stream()
                .filter(id -> request.equals(id) || request.startsWith(id + "_"))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (cropId == null) {
            return null;
        }

        String statistic = request.length() == cropId.length()
                ? "amount"
                : request.substring(cropId.length() + 1);
        long amount = progress.amount(cropId);
        long remaining = Math.max(0L, progress.target() - amount);
        long playerAmount = playerId == null ? 0L : progress.contribution(playerId, cropId);

        return switch (statistic) {
            case "amount" -> Long.toString(amount);
            case "amount_formatted" -> Text.number(amount);
            case "target" -> Long.toString(progress.target());
            case "target_formatted" -> Text.number(progress.target());
            case "remaining" -> Long.toString(remaining);
            case "remaining_formatted" -> Text.number(remaining);
            case "percent" -> Text.percent(amount, progress.target());
            case "completed" -> Boolean.toString(amount >= progress.target());
            case "player_amount" -> Long.toString(playerAmount);
            case "player_amount_formatted" -> Text.number(playerAmount);
            default -> null;
        };
    }

    private static long total(ProgressService progress) {
        long total = 0L;
        for (String cropId : progress.crops().keySet()) {
            total = Numbers.saturatingAdd(total, progress.amount(cropId));
        }
        return total;
    }

    private static long playerTotal(ProgressService progress, UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        long total = 0L;
        for (String cropId : progress.crops().keySet()) {
            total = Numbers.saturatingAdd(total, progress.contribution(playerId, cropId));
        }
        return total;
    }
}
