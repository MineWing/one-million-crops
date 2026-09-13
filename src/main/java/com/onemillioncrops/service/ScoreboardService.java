package com.onemillioncrops.service;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.config.PluginSettings;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.util.Tasks;
import com.onemillioncrops.util.Text;
import fr.mrmicky.fastboard.adventure.FastBoard;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Packet-only sidebars: no Bukkit scoreboard state is accessed on Paper or Folia. */
public final class ScoreboardService {
    private final OneMillionCropsPlugin plugin;
    private final Map<UUID, PlayerBoard> boards = new ConcurrentHashMap<>();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private volatile List<Component> titleFrames = List.of(Component.text("Crops • Season 2"));
    private volatile boolean running;

    public ScoreboardService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        stop();
        var settings = plugin.configManager().settings();
        titleFrames = plugin.text().compileAnimatedGradientFrames(settings.scoreboardTitleFrames(),
                settings.scoreboardTitleAnimationFrames());
        running = true;
        for (Player player : plugin.getServer().getOnlinePlayers()) showIfEnabled(player);
        plugin.getLogger().info("Packet sidebar enabled for Paper and Folia.");
    }

    public void showIfEnabled(Player player) {
        Tasks.player(plugin, player, () -> showOwned(player));
    }

    private synchronized void showOwned(Player player) {
        UUID id = player.getUniqueId();
        if (!running || hidden.contains(id) || !plugin.configManager().settings().scoreboardEnabled()
                || boards.containsKey(id)) return;
        PlayerBoard session = new PlayerBoard(new FastBoard(player));
        boards.put(id, session);
        render(session, true);
        int period = plugin.configManager().settings().scoreboardAnimationTicks();
        session.task = player.getScheduler().runAtFixedRate(plugin, task -> {
            synchronized (session) {
                if (session.deleted) return;
                session.ticks += period;
                session.dataTicks += period;
                int refresh = plugin.configManager().settings().scoreboardRefreshTicks();
                boolean refreshData = session.dataTicks >= refresh;
                if (refreshData) session.dataTicks %= refresh;
                render(session, refreshData);
            }
        }, () -> boards.remove(id, session), 1L, period);
        if (session.task == null) boards.remove(id, session);
    }

    /** Called by the player's command on their owning entity thread. */
    public synchronized boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (boards.containsKey(id)) {
            hidden.add(id);
            remove(player);
            return false;
        }
        hidden.remove(id);
        showOwned(player);
        return boards.containsKey(id);
    }

    public void updateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            showIfEnabled(player);
            update(player);
        }
    }

    public void update(Player player) {
        Tasks.player(plugin, player, () -> {
            PlayerBoard session = boards.get(player.getUniqueId());
            if (session != null) render(session, true);
        });
    }

    private void render(PlayerBoard session, boolean refreshData) {
        synchronized (session) {
            if (session.deleted) return;
            PluginSettings settings = plugin.configManager().settings();
            List<Component> frames = titleFrames;
            long frame = session.ticks / Math.max(1, settings.scoreboardAnimationTicks());
            session.board.updateTitle(frames.get((int) (frame % frames.size())));
            if (refreshData) session.board.updateLines(lines(session.ticks, settings));
        }
    }

    private List<Component> lines(long ticks, PluginSettings settings) {
        ProgressService progress = plugin.progress();
        var snapshot = progress.snapshot();
        int perPage = settings.scoreboardCropsPerPage();
        List<CropDefinition> crops = new ArrayList<>(progress.crops().values());
        crops.sort(Comparator.comparingLong((CropDefinition crop) -> snapshot.totals().getOrDefault(crop.id(), 0L))
                .reversed());
        int pages = Math.max(1, (crops.size() + perPage - 1) / perPage);
        int page = (int) ((ticks / settings.scoreboardPageTicks()) % pages);
        int start = page * perPage;
        int end = Math.min(crops.size(), start + perPage);
        long totalTarget = saturatingMultiply(progress.target(), crops.size());
        long total = snapshot.totals().values().stream().reduce(0L, ScoreboardService::saturatingAdd);
        long completed = snapshot.completed().values().stream().filter(Boolean::booleanValue).count();
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.text().parse("<gray>Overall Progress</gray>"));
        lines.add(plugin.text().parse(Text.progressBar(total, totalTarget, 14)));
        lines.add(plugin.text().parse("<white>" + Text.percent(total, totalTarget) + "%</white> <dark_gray>•</dark_gray> <gray>"
                + completed + "/" + crops.size() + " done</gray>"));
        lines.add(plugin.text().parse("<gray>Target:</gray> <#FFC2DE>" + Text.number(progress.target()) + " per crop</#FFC2DE>"));
        lines.add(plugin.text().parse("<#FF8FBD><bold>Crops</bold></#FF8FBD> <dark_gray>(" + (page + 1) + "/" + pages + ")</dark_gray>"));
        for (int index = start; index < end; index++) {
            CropDefinition crop = crops.get(index);
            long amount = snapshot.totals().getOrDefault(crop.id(), 0L);
            String marker = amount >= progress.target() ? "<#FF8FBD>✔</#FF8FBD>" : "<dark_gray>•</dark_gray>";
            lines.add(plugin.text().parse(marker + " " + crop.displayMiniMessage() + " <white>" + compact(amount) + "</white>"));
        }
        lines.add(Component.empty());
        lines.add(plugin.text().parse("<gray>/progress for details</gray>"));
        return lines;
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

    public synchronized void remove(Player player) {
        PlayerBoard session = boards.remove(player.getUniqueId());
        if (session != null) session.close();
    }

    public synchronized void stop() {
        running = false;
        boards.values().forEach(PlayerBoard::close);
        boards.clear();
    }

    private static final class PlayerBoard {
        private final FastBoard board;
        private ScheduledTask task;
        private long ticks;
        private int dataTicks;
        private boolean deleted;

        private PlayerBoard(FastBoard board) { this.board = board; }

        private synchronized void close() {
            if (deleted) return;
            deleted = true;
            if (task != null) task.cancel();
            board.delete();
        }
    }
}
