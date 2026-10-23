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
import com.onemillioncrops.util.StartupBanner;
import com.onemillioncrops.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class OneMillionCropsPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private Text text;
    private ProgressDatabase database;
    // Read from async save and placeholder threads; replaced only under stateLock.
    private volatile ProgressService progress;
    private ScoreboardService scoreboards;
    private GuiService gui;
    private HarvestActionBarService harvestActionBar;
    private HarvestSummaryService harvestSummary;
    private SummaryActionService actions;
    private CelebrationService celebrations;
    private CropWandListener cropWand;
    private PlantWandListener plantWand;
    private BukkitTask autosaveTask;
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

        printStartupBanner();
    }

    private void printStartupBanner() {
        for (String line : StartupBanner.lines(getPluginMeta().getVersion(), configManager.crops().size(),
                progress.completedCount(), progress.target())) {
            getServer().getConsoleSender().sendMessage(text.parse(line));
        }
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
        harvestActionBar.record(player, crop, result.added());
        harvestSummary.record(player, result.added());
        if (configManager.settings().guiPickupSound()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.25f, 1.6f);
        }
        finishProgressUpdate(crop, result);
        return result.added();
    }

    public long recordAutomatedPickup(CropDefinition crop, int amount) {
        if (maintenance) {
            return 0;
        }
        ProgressService.IncrementResult result = addProgress(null, crop, amount);
        if (result.added() <= 0) {
            return 0;
        }
        finishProgressUpdate(crop, result);
        return result.added();
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
        // Only marks open menus stale; GuiService rebuilds them on its next animation frame.
        gui.refreshOpen();
    }

    public void reloadPlugin(CommandSender sender) {
        if (!beginOperation(sender, true)) {
            return;
        }
        String databaseFile = configManager.settings().databaseFile();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                configManager.ensureResources();
                ConfigManager.LoadedConfiguration loaded = configManager.read();
                ProgressSnapshot snapshot;
                synchronized (persistenceLock) {
                    flushAllNow(true);
                    snapshot = database.load();
                }
                completeOperation(sender, "reload-failed", "Could not apply the reloaded configuration", () -> {
                    configManager.apply(loaded);
                    if (!databaseFile.equals(loaded.settings().databaseFile())) {
                        getLogger().warning("storage.database-file changes take effect after a full server restart.");
                    }
                    replaceProgress(createProgress(snapshot));
                    startAutosave();
                    scoreboards.start();
                    gui.start();
                    harvestSummary.start();
                }, () -> sendActions("reloaded", sender, Map.of()));
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
                String failure = "Could not apply the toggle of crop " + cropId;
                completeOperation(player, "crop-toggle-failed", failure, () -> {
                    configManager.apply(loaded);
                    replaceProgress(createProgress(snapshot));
                }, () -> {
                    scoreboards.updateAll();
                    gui.refreshOpen();
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
                configManager.setAllowAutomatedFarms(enable);
                completeOperation(sender, "automode-toggle-failed", "Could not apply automated farm crediting",
                        () -> configManager.applyAllowAutomatedFarms(enable),
                        () -> sendActions(enable ? "automode-enabled" : "automode-disabled", sender, Map.of()));
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
                    path = database.backup(configManager.settings().maxBackups());
                }
                completeOperation(sender, "backup-failed", "Could not finish the progress backup", () -> { },
                        () -> sendActions("backup-complete", sender, Map.of("file", path.getFileName().toString())));
            } catch (Exception exception) {
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
                        beforeReset = progress.takeSnapshot();
                    }
                    database.save(beforeReset);
                    if (configManager.settings().backupBeforeReset()) {
                        database.backup(configManager.settings().maxBackups());
                    }
                    if (crop == null) {
                        database.resetAll();
                    } else {
                        database.resetCrop(crop.id());
                    }
                }
                completeOperation(sender, "reset-failed", "Could not apply the progress reset", () -> {
                    // The in-memory reset comes first: it must happen before counting resumes, or the next
                    // save would write the old totals over the reset rows.
                    if (crop == null) {
                        progress.resetAll();
                        harvestSummary.resetPersonalBests();
                    } else {
                        progress.resetCrop(crop.id());
                    }
                }, () -> {
                    if (crop == null) {
                        sendActions("reset-complete", sender, Map.of());
                    } else {
                        sendActions("crop-reset-complete", sender, Map.of("crop", crop.displayMiniMessage()));
                    }
                    scoreboards.updateAll();
                    gui.refreshOpen();
                });
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not reset progress", exception);
                progress.markDirty();
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
            while (true) {
                synchronized (persistenceLock) {
                    // Check under the lock: a reset or shutdown may have run while this thread waited,
                    // and saving pre-reset state now would overwrite its work.
                    if (maintenance || shuttingDown) {
                        break;
                    }
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
                    // A routine save carries only what changed since the last one; markDirty() in the
                    // catch below makes the next snapshot a full one if this write fails.
                    database.save(snapshot);
                }
            }
        } catch (SQLException | RuntimeException exception) {
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

    /** Writes the full state and every queued completion, as reload, backup and shutdown need. */
    private void flushAllNow(boolean announceCompletions) throws SQLException {
        ProgressService service;
        ProgressSnapshot current;
        synchronized (stateLock) {
            service = progress;
            current = service.takeSnapshot();
        }
        try {
            database.save(current);
        } catch (SQLException | RuntimeException exception) {
            // takeSnapshot() cleared the tracked changes, so the next routine save must write everything.
            service.markDirty();
            throw exception;
        }
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

    /**
     * Finishes an operation on the main thread. The operation always ends, even if {@code work} throws,
     * so one unexpected error cannot leave counting paused and every admin command refused until a
     * restart. {@code onSuccess} runs after the operation has ended, and only if {@code work} completed;
     * otherwise the error is logged and {@code failedEvent} is sent instead.
     */
    private void completeOperation(CommandSender sender, String failedEvent, String failureMessage,
                                   Runnable work, Runnable onSuccess) {
        runSyncIfActive(() -> {
            boolean succeeded = runThenEnd(work, this::endOperation,
                    exception -> getLogger().log(Level.SEVERE, failureMessage, exception));
            if (succeeded) {
                onSuccess.run();
            } else {
                sendActions(failedEvent, sender, Map.of());
            }
        });
    }

    /** Runs {@code work}, then always {@code end}; returns false after passing a failure to {@code onFailure}. */
    static boolean runThenEnd(Runnable work, Runnable end, Consumer<RuntimeException> onFailure) {
        try {
            work.run();
            return true;
        } catch (RuntimeException exception) {
            onFailure.accept(exception);
            return false;
        } finally {
            end.run();
        }
    }

    private void replaceProgress(ProgressService replacement) {
        synchronized (stateLock) {
            progress = replacement;
        }
    }

    private void runSyncIfActive(Runnable action) {
        if (!shuttingDown && isEnabled()) {
            Bukkit.getScheduler().runTask(this, action);
        }
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
