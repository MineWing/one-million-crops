package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ScoreboardService {
    private static final int MAX_LINES = 15;
    private final OneMillionCropsPlugin plugin;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private final Map<UUID, Scoreboard> previous = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private List<Component> titleFrames = List.of(Component.text("One Million Crops"));
    private BukkitTask animationTask;
    private long animationTick;
    private int dataRefreshTicks;

    public ScoreboardService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stopTask();
        animationTick = 0;
        dataRefreshTicks = 0;
        titleFrames = plugin.text().compileAnimatedGradientFrames(
                plugin.configManager().settings().scoreboardTitleFrames(),
                plugin.configManager().settings().scoreboardTitleAnimationFrames());
        int period = plugin.configManager().settings().scoreboardAnimationTicks();
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> animate(period), 1L, period);
        for (Player player : Bukkit.getOnlinePlayers()) {
            showIfEnabled(player);
        }
    }

    public void showIfEnabled(Player player) {
        if (!plugin.configManager().settings().scoreboardEnabled() || hidden.contains(player.getUniqueId())) {
            return;
        }
        previous.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        boards.computeIfAbsent(player.getUniqueId(), ignored -> createBoard());
        update(player);
        player.setScoreboard(boards.get(player.getUniqueId()).scoreboard());
    }

    public boolean toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (boards.containsKey(uuid) && !hidden.contains(uuid)) {
            hidden.add(uuid);
            Scoreboard old = previous.get(uuid);
            player.setScoreboard(old != null ? old : Bukkit.getScoreboardManager().getMainScoreboard());
            return false;
        }
        hidden.remove(uuid);
        showIfEnabled(player);
        return true;
    }

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!hidden.contains(player.getUniqueId())) {
                showIfEnabled(player);
            }
        }
    }

    public void update(Player player) {
        PlayerBoard playerBoard = boards.get(player.getUniqueId());
        if (playerBoard == null || hidden.contains(player.getUniqueId())) {
            return;
        }
        updateTitle(playerBoard);
        updateLines(playerBoard);
    }

    private void animate(int elapsedTicks) {
        animationTick += elapsedTicks;
        dataRefreshTicks += elapsedTicks;
        int refreshPeriod = plugin.configManager().settings().scoreboardRefreshTicks();
        boolean refreshData = dataRefreshTicks >= refreshPeriod;
        if (refreshData) {
            dataRefreshTicks %= refreshPeriod;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hidden.contains(player.getUniqueId())) {
                continue;
            }
            PlayerBoard board = boards.get(player.getUniqueId());
            if (board == null) {
                showIfEnabled(player);
                continue;
            }
            updateTitle(board);
            if (refreshData) {
                updateLines(board);
            }
        }
    }

    private void updateTitle(PlayerBoard board) {
        int animationPeriod = plugin.configManager().settings().scoreboardAnimationTicks();
        long step = animationTick / Math.max(1, animationPeriod);
        Component title = titleFrames.get((int) (step % titleFrames.size()));
        if (title.equals(board.displayedTitle())) {
            return;
        }
        board.objective().displayName(title);
        board.displayedTitle(title);
    }

    private void updateLines(PlayerBoard playerBoard) {
        int perPage = plugin.configManager().settings().scoreboardCropsPerPage();
        List<CropDefinition> crops = new ArrayList<>(plugin.progress().crops().values());
        crops.sort(Comparator.comparingLong((CropDefinition crop) -> plugin.progress().amount(crop.id()))
                .reversed());
        int pages = Math.max(1, (crops.size() + perPage - 1) / perPage);
        int page = (int) ((animationTick / plugin.configManager().settings().scoreboardPageTicks()) % pages);
        int start = page * perPage;
        int end = Math.min(crops.size(), start + perPage);
        long totalTarget = saturatingMultiply(plugin.progress().target(), crops.size());
        long total = crops.stream().mapToLong(crop -> plugin.progress().amount(crop.id()))
                .reduce(0L, ScoreboardService::saturatingAdd);

        List<Component> lines = new ArrayList<>();
        lines.add(plugin.text().parse("<gray>Overall Progress</gray>"));
        lines.add(plugin.text().parse(Text.progressBar(total, totalTarget, 14)));
        lines.add(plugin.text().parse("<white>" + Text.percent(total, totalTarget) + "%</white> <dark_gray>•</dark_gray> <gray>" +
                plugin.progress().completedCount() + "/" + crops.size() + " done</gray>"));
        lines.add(Component.empty());
        lines.add(plugin.text().parse("<yellow><bold>Crops</bold></yellow> <dark_gray>(" + (page + 1) + "/" + pages + ")</dark_gray>"));
        for (int index = start; index < end; index++) {
            CropDefinition crop = crops.get(index);
            long amount = plugin.progress().amount(crop.id());
            String marker = amount >= plugin.progress().target() ? "<green>✔</green>" : "<dark_gray>•</dark_gray>";
            lines.add(plugin.text().parse(marker + " " + crop.displayMiniMessage()
                    + " <white>" + compact(amount) + "</white>"));
        }
        lines.add(Component.empty());
        lines.add(plugin.text().parse("<gray>/progress for details</gray>"));
        applyLines(playerBoard, lines);
    }

    private PlayerBoard createBoard() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Component initialTitle = Component.text("One Million Crops");
        Objective objective = scoreboard.registerNewObjective("millioncrops", Criteria.DUMMY,
                initialTitle);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<Team> lineTeams = new ArrayList<>();
        List<Component> rendered = new ArrayList<>();
        for (int index = 0; index < MAX_LINES; index++) {
            Team team = scoreboard.registerNewTeam(String.format(Locale.ROOT, "omc_%02d", index));
            String entry = uniqueCode(index);
            team.addEntry(entry);
            team.prefix(Component.empty());
            Score score = objective.getScore(entry);
            score.setScore(MAX_LINES - index);
            score.numberFormat(NumberFormat.blank());
            lineTeams.add(team);
            rendered.add(Component.empty());
        }
        return new PlayerBoard(scoreboard, objective, lineTeams, rendered, initialTitle);
    }

    private void applyLines(PlayerBoard board, List<Component> lines) {
        for (int index = 0; index < MAX_LINES; index++) {
            Component line = index < lines.size() ? lines.get(index) : Component.empty();
            if (!line.equals(board.rendered().get(index))) {
                board.lineTeams().get(index).prefix(line);
                board.rendered().set(index, line);
            }
        }
    }

    private static String uniqueCode(int index) {
        return "§" + Integer.toHexString(index & 15);
    }

    private static String compact(long amount) {
        if (amount >= 1_000_000) {
            return String.format(Locale.US, "%.2fM", amount / 1_000_000.0);
        }
        if (amount >= 1_000) {
            return String.format(Locale.US, "%.1fk", amount / 1_000.0);
        }
        return Long.toString(amount);
    }

    private static long saturatingMultiply(long value, int multiplier) {
        return multiplier > 0 && value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public void remove(Player player) {
        boards.remove(player.getUniqueId());
        previous.remove(player.getUniqueId());
    }

    public void stop() {
        stopTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard old = previous.get(player.getUniqueId());
            if (old != null) {
                player.setScoreboard(old);
            }
        }
        boards.clear();
        previous.clear();
    }

    private void stopTask() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
    }

    private static final class PlayerBoard {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<Team> lineTeams;
        private final List<Component> rendered;
        private Component displayedTitle;

        private PlayerBoard(Scoreboard scoreboard, Objective objective,
                            List<Team> lineTeams, List<Component> rendered, Component displayedTitle) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.lineTeams = lineTeams;
            this.rendered = rendered;
            this.displayedTitle = displayedTitle;
        }

        private Scoreboard scoreboard() {
            return scoreboard;
        }

        private Objective objective() {
            return objective;
        }

        private List<Team> lineTeams() {
            return lineTeams;
        }

        private List<Component> rendered() {
            return rendered;
        }

        private Component displayedTitle() {
            return displayedTitle;
        }

        private void displayedTitle(Component displayedTitle) {
            this.displayedTitle = displayedTitle;
        }
    }
}
