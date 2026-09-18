package com.onemillioncrops;

import com.onemillioncrops.command.MainCommand;
import com.onemillioncrops.command.ProgressCommand;
import com.onemillioncrops.config.ConfigManager;
import com.onemillioncrops.data.ProgressDatabase;
import com.onemillioncrops.listener.CocoaAutoReplantListener;
import com.onemillioncrops.listener.CropPickupListener;
import com.onemillioncrops.listener.CropWandListener;
import com.onemillioncrops.listener.PlantWandListener;
import com.onemillioncrops.listener.PlayerListener;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.model.ProgressSnapshot;
import com.onemillioncrops.placeholder.PlaceholderApiHook;
import com.onemillioncrops.service.CelebrationService;
import com.onemillioncrops.service.GuiService;
import com.onemillioncrops.service.HarvestActionBarService;
import com.onemillioncrops.service.HarvestSummaryService;
import com.onemillioncrops.service.ProgressService;
import com.onemillioncrops.service.ScoreboardService;
import com.onemillioncrops.service.SummaryActionService;
import com.onemillioncrops.web.WebDashboardService;
import com.onemillioncrops.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class OneMillionCropsPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private Text text;
    private ProgressDatabase database;
    private ProgressService progress;
    private ScoreboardService scoreboards;
    private GuiService gui;
    private HarvestActionBarService harvestActionBar;
    private HarvestSummaryService harvestSummary;
    private SummaryActionService actions;
    private CelebrationService celebrations;
    private WebDashboardService dashboard;
    private CropWandListener cropWand;
    private PlantWandListener plantWand;
    private BukkitTask autosaveTask;
    private BukkitTask visualRefreshTask;
    private Runnable unregisterPlaceholders = () -> { };
    private volatile boolean maintenance;
    private volatile boolean resetDatabaseStarted;
    private volatile boolean shuttingDown;
    private final Object persistenceLock = new Object();
    private final Object stateLock = new Object();
    private final AtomicBoolean persistenceRunning = new AtomicBoolean();
    private final AtomicBoolean operationRunning = new AtomicBoolean();
    private final ConcurrentLinkedQueue<CompletionWork> pendingCompletions = new ConcurrentLinkedQueue<>();

    @Override
    public void onEnable() {
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            text = new Text(configManager);
            database = new ProgressDatabase(this, configManager.settings().databaseFile());
            database.open();
            progress = createProgress(database.load());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "OneMillionCrops could not start", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scoreboards = new ScoreboardService(this);
        actions = new SummaryActionService(this);
        gui = new GuiService(this);
        harvestActionBar = new HarvestActionBarService(this);
        harvestSummary = new HarvestSummaryService(this);
        celebrations = new CelebrationService(this);
        dashboard = new WebDashboardService(this);

        getServer().getPluginManager().registerEvents(new CocoaAutoReplantListener(this), this);
        getServer().getPluginManager().registerEvents(new CropPickupListener(this), this);
        cropWand = new CropWandListener(this);
        getServer().getPluginManager().registerEvents(cropWand, this);
        plantWand = new PlantWandListener(this);
        getServer().getPluginManager().registerEvents(plantWand, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        registerCommands();
        registerPlaceholders();
        scoreboards.start();
        gui.start();
        harvestSummary.start();
        startAutosave();
        dashboard.start();

        getLogger().info("OneMillionCrops enabled with " + configManager.crops().size() +
                " crops and a target of " + Text.number(progress.target()) + " each.");
    }

    private ProgressService createProgress(ProgressSnapshot snapshot) {
        return new ProgressService(configManager.crops(), configManager.settings().target(),
                configManager.settings().milestones(), snapshot);
    }

    private void registerCommands() {
        MainCommand main = new MainCommand(this);
        Objects.requireNonNull(getCommand("1mill")).setExecutor(main);
        Objects.requireNonNull(getCommand("1mill")).setTabCompleter(main);
        ProgressCommand progressCommand = new ProgressCommand(this);
        Objects.requireNonNull(getCommand("progress")).setExecutor(progressCommand);
        Objects.requireNonNull(getCommand("progress")).setTabCompleter(progressCommand);
    }

    private void registerPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found; placeholder support is disabled.");
            return;
        }
        unregisterPlaceholders = PlaceholderApiHook.register(this);
    }

    public long recordPickup(Player player, CropDefinition crop, int amount) {
        if (maintenance) {
            return 0;
        }
        ProgressService.IncrementResult result = addProgress(player.getUniqueId(), crop, amount);
        if (result.added() <= 0) {
            return 0;
        }
        dashboard.recordPickup(player, crop, result.added());
        harvestActionBar.record(player, crop, result.added());
        harvestSummary.record(player, result.added());
        if (configManager.settings().guiPickupSound()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.25f, 1.6f);
        }
        finishProgressUpdate(crop, result);
        return result.added();
    }

    public void recordAutomatedPickup(CropDefinition crop, int amount) {
        if (maintenance) {
            return;
        }
        ProgressService.IncrementResult result = addProgress(null, crop, amount);
        if (result.added() <= 0) {
            return;
        }
        dashboard.recordAutomatedPickup(crop, result.added());
        finishProgressUpdate(crop, result);
    }

    private ProgressService.IncrementResult addProgress(java.util.UUID player, CropDefinition crop, int amount) {
        synchronized (stateLock) {
            ProgressService.IncrementResult result = player == null
                    ? progress.addUnattributed(crop.id(), amount)
                    : progress.add(player, crop.id(), amount);
            if (result.completed()) {
                Set<java.util.UUID> recipients = new LinkedHashSet<>(progress.contributors());
                recipients.addAll(configManager.settings().participantAllowlist());
                pendingCompletions.add(new CompletionWork(progress.snapshot(), crop, result.allCompleted(),
                        Set.copyOf(recipients)));
            }
            return result;
        }
    }

    private void finishProgressUpdate(CropDefinition crop, ProgressService.IncrementResult result) {
        for (int milestone : result.crossedMilestones()) {
            celebrations.milestone(crop, milestone);
        }
        if (result.completed()) {
            requestSave();
        }
        requestVisualRefresh();
    }

    public void reloadPlugin(CommandSender sender) {
        if (!beginOperation(sender, true)) {
            return;
        }
        configManager.ensureResources();
        String databaseFile = configManager.settings().databaseFile();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                ConfigManager.LoadedConfiguration loaded = configManager.read();
                ProgressSnapshot snapshot;
                synchronized (persistenceLock) {
                    flushAllNow(true);
                    snapshot = database.load();
                }
                runSyncIfActive(() -> {
                    configManager.apply(loaded);
                    if (!databaseFile.equals(loaded.settings().databaseFile())) {
                        getLogger().warning("storage.database-file changes take effect after a full server restart.");
                    }
                    progress = createProgress(snapshot);
                    scoreboards.start();
                    gui.start();
                    harvestSummary.start();
                    startAutosave();
                    dashboard.restart();
                    endOperation();
                    sendActions("reloaded", sender, Map.of());
                });
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not reload OneMillionCrops", exception);
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("reload-failed", sender, Map.of());
                });
            }
        });
    }

    public void toggleCrop(Player player, String cropId) {
        CropDefinition crop = configManager.configuredCrops().get(cropId);
        if (crop == null) {
            sendActions("unknown-crop", player, Map.of("crop", Text.escape(cropId)));
            return;
        }
        boolean enable = !configManager.isCropEnabled(cropId);
        if (!enable && configManager.crops().size() <= 1) {
            sendActions("crop-toggle-last", player, Map.of());
            return;
        }
        if (!beginOperation(player, true)) {
            return;
        }
        sendActions("crop-toggle-updating", player, Map.of("crop", crop.displayMiniMessage()));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                ConfigManager.LoadedConfiguration loaded;
                ProgressSnapshot snapshot;
                synchronized (persistenceLock) {
                    flushAllNow(true);
                    snapshot = database.load();
                    loaded = configManager.setCropEnabled(cropId, enable);
                }
                runSyncIfActive(() -> {
                    configManager.apply(loaded);
                    synchronized (stateLock) {
                        progress = createProgress(snapshot);
                    }
                    endOperation();
                    scoreboards.updateAll();
                    gui.refreshOpen();
                    dashboard.refreshNow();
                    sendActions(enable ? "crop-enabled" : "crop-disabled", player,
                            Map.of("crop", crop.displayMiniMessage()));
                });
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not toggle crop " + cropId, exception);
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("crop-toggle-failed", player, Map.of());
                    gui.refreshOpen();
                });
            }
        });
    }

    public void toggleAutoHarvest(CommandSender sender) {
        if (!beginOperation(sender, false)) {
            return;
        }
        boolean enable = !configManager.settings().allowAutomatedFarms();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                ConfigManager.LoadedConfiguration loaded = configManager.setAllowAutomatedFarms(enable);
                runSyncIfActive(() -> {
                    configManager.apply(loaded);
                    endOperation();
                    sendActions(enable ? "automode-enabled" : "automode-disabled", sender, Map.of());
                });
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not toggle automated farm crediting", exception);
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("automode-toggle-failed", sender, Map.of());
                });
            }
        });
    }

    public void backup(CommandSender sender) {
        if (!beginOperation(sender, false)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Path path;
                synchronized (persistenceLock) {
                    flushAllNow(true);
                    path = database.backup();
                }
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("backup-complete", sender, Map.of("file", path.getFileName().toString()));
                });
            } catch (SQLException | IOException exception) {
                getLogger().log(Level.SEVERE, "Could not back up progress", exception);
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("backup-failed", sender, Map.of());
                });
            }
        });
    }

    public void resetAll(CommandSender sender) {
        runReset(sender, null);
    }

    public void resetCrop(CommandSender sender, CropDefinition crop) {
        runReset(sender, crop);
    }

    private void runReset(CommandSender sender, CropDefinition crop) {
        if (!beginOperation(sender, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                synchronized (persistenceLock) {
                    resetDatabaseStarted = true;
                    ProgressSnapshot beforeReset;
                    synchronized (stateLock) {
                        pendingCompletions.clear();
                        beforeReset = progress.snapshot();
                    }
                    database.save(beforeReset, configManager.crops().keySet());
                    if (configManager.settings().backupBeforeReset()) {
                        database.backup();
                    }
                    if (crop == null) {
                        database.resetAll();
                    } else {
                        database.resetCrop(crop.id());
                    }
                }
                runSyncIfActive(() -> {
                    if (crop == null) {
                        progress.resetAll();
                        harvestSummary.resetPersonalBests();
                        sendActions("reset-complete", sender, Map.of());
                    } else {
                        progress.resetCrop(crop.id());
                        sendActions("crop-reset-complete", sender, Map.of("crop", crop.displayMiniMessage()));
                    }
                    endOperation();
                    scoreboards.updateAll();
                    gui.refreshOpen();
                    dashboard.recordReset(crop);
                });
            } catch (SQLException | IOException exception) {
                getLogger().log(Level.SEVERE, "Could not reset progress", exception);
                runSyncIfActive(() -> {
                    endOperation();
                    sendActions("reset-failed", sender, Map.of());
                });
            }
        });
    }

    private void startAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        long period = configManager.settings().autosaveSeconds() * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, this::requestSave, period, period);
    }

    private void requestSave() {
        if (maintenance || shuttingDown || !persistenceRunning.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, this::drainSaves);
    }

    private void drainSaves() {
        boolean failed = false;
        try {
            while (!maintenance && !shuttingDown) {
                synchronized (persistenceLock) {
                    CompletionWork completion;
                    ProgressSnapshot snapshot = null;
                    synchronized (stateLock) {
                        completion = pendingCompletions.peek();
                        if (completion == null) {
                            snapshot = progress.takeDirtySnapshot();
                        }
                    }
                    if (completion != null) {
                        database.saveCompletion(completion.snapshot(), completion.crop().id(),
                                completion.recipients(), completion.grandFinale());
                        synchronized (stateLock) {
                            pendingCompletions.remove(completion);
                        }
                        scheduleCompletion(completion);
                        continue;
                    }
                    if (snapshot == null) {
                        break;
                    }
                    database.save(snapshot, configManager.crops().keySet());
                }
            }
        } catch (SQLException exception) {
            failed = true;
            progress.markDirty();
            getLogger().log(Level.SEVERE, "Could not save crop progress; it will be retried", exception);
        } finally {
            persistenceRunning.set(false);
            if (!failed && !maintenance && !shuttingDown
                    && (!pendingCompletions.isEmpty() || progress.isDirty())) {
                requestSave();
            }
        }
    }

    private void flushAllNow(boolean announceCompletions) throws SQLException {
        ProgressSnapshot current;
        synchronized (stateLock) {
            current = progress.takeDirtySnapshot();
            if (current == null) {
                current = progress.snapshot();
            }
        }
        database.save(current, configManager.crops().keySet());
        CompletionWork completion;
        while (true) {
            synchronized (stateLock) {
                completion = pendingCompletions.peek();
            }
            if (completion == null) {
                break;
            }
            database.saveCompletion(completion.snapshot(), completion.crop().id(),
                    completion.recipients(), completion.grandFinale());
            synchronized (stateLock) {
                pendingCompletions.remove(completion);
            }
            if (announceCompletions) {
                scheduleCompletion(completion);
            }
        }
    }

    private void scheduleCompletion(CompletionWork completion) {
        runSyncIfActive(() -> celebrations.completedPersisted(completion.crop(), completion.grandFinale()));
    }

    private boolean beginOperation(CommandSender sender, boolean pauseCounting) {
        if (!operationRunning.compareAndSet(false, true)) {
            sendActions("operation-running", sender, Map.of());
            return false;
        }
        if (pauseCounting) {
            maintenance = true;
        }
        return true;
    }

    private void endOperation() {
        maintenance = false;
        resetDatabaseStarted = false;
        operationRunning.set(false);
    }

    private void runSyncIfActive(Runnable action) {
        if (!shuttingDown && isEnabled()) {
            Bukkit.getScheduler().runTask(this, action);
        }
    }

    private void requestVisualRefresh() {
        if (visualRefreshTask != null) {
            return;
        }
        visualRefreshTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            visualRefreshTask = null;
            gui.refreshOpen();
            dashboard.refreshNow();
        }, 1L);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        unregisterPlaceholders.run();
        unregisterPlaceholders = () -> { };
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (scoreboards != null) {
            scoreboards.stop();
        }
        if (gui != null) {
            gui.stop();
        }
        if (harvestActionBar != null) {
            harvestActionBar.stop();
        }
        if (harvestSummary != null) {
            harvestSummary.stop();
        }
        if (dashboard != null) {
            dashboard.stop();
        }
        if (visualRefreshTask != null) {
            visualRefreshTask.cancel();
        }
        if (database != null) {
            synchronized (persistenceLock) {
                try {
                    if (progress != null && !resetDatabaseStarted) {
                        flushAllNow(false);
                    }
                    database.close();
                } catch (SQLException exception) {
                    getLogger().log(Level.SEVERE, "Could not close progress database cleanly", exception);
                }
            }
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public Text text() {
        return text;
    }

    public ProgressDatabase database() {
        return database;
    }

    public ProgressService progress() {
        return progress;
    }

    public ScoreboardService scoreboards() {
        return scoreboards;
    }

    public GuiService gui() {
        return gui;
    }

    public HarvestActionBarService harvestActionBar() {
        return harvestActionBar;
    }

    public HarvestSummaryService harvestSummary() {
        return harvestSummary;
    }

    public CelebrationService celebrations() {
        return celebrations;
    }

    public WebDashboardService dashboard() {
        return dashboard;
    }

    public CropWandListener cropWand() {
        return cropWand;
    }

    public PlantWandListener plantWand() {
        return plantWand;
    }

    public SummaryActionService actions() {
        return actions;
    }

    public void sendActions(String event, CommandSender sender, Map<String, String> replacements) {
        actions.execute(event, java.util.List.of(sender),
                sender instanceof Player player ? java.util.List.of(player) : java.util.List.of(),
                java.util.List.of(), replacements);
    }

    private record CompletionWork(ProgressSnapshot snapshot, CropDefinition crop, boolean grandFinale,
                                  Set<java.util.UUID> recipients) {
    }
}
