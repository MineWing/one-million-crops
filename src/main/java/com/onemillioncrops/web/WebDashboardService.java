package com.onemillioncrops.web;

import com.onemillioncrops.OneMillionCropsPlugin;
import com.onemillioncrops.config.PluginSettings;
import com.onemillioncrops.model.CropDefinition;
import com.onemillioncrops.model.ProgressSnapshot;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class WebDashboardService {
    private static final int MAX_ACTIVITY = 32;
    private static final int MAX_STREAM_CLIENTS = 64;
    private static final List<String> CROP_COLORS = List.of(
            "#84cc16", "#facc15", "#fb923c", "#f87171", "#c084fc", "#60a5fa",
            "#2dd4bf", "#a3e635", "#fbbf24", "#e879f9", "#38bdf8", "#4ade80"
    );

    private final OneMillionCropsPlugin plugin;
    private final Deque<Activity> activity = new ArrayDeque<>();
    private final Deque<HistoryPoint> history = new ArrayDeque<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private final Map<Long, StreamClient> streamClients = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile String snapshotJson = "{\"ready\":false}";
    private HttpServer server;
    private ExecutorService executor;
    private BukkitTask refreshTask;
    private long lastHistorySample;
    private String publicUrl = "";

    public WebDashboardService(OneMillionCropsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        PluginSettings settings = plugin.configManager().settings();
        if (!settings.webEnabled()) {
            plugin.getLogger().info("Live web dashboard is disabled.");
            return;
        }
        refreshNow();
        try {
            server = HttpServer.create(new InetSocketAddress(settings.webBindAddress(), settings.webPort()), 0);
            server.createContext("/api/v1/progress", this::handleSnapshot);
            server.createContext("/api/v1/events", this::handleEvents);
            server.createContext("/health", this::handleHealth);
            server.createContext("/", this::handleStatic);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.start();
            publicUrl = determinePublicUrl(settings);
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshNow,
                    settings.webRefreshTicks(), settings.webRefreshTicks());
            plugin.getLogger().info("Live dashboard listening at " + publicUrl);
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the live dashboard on "
                    + settings.webBindAddress() + ":" + settings.webPort(), exception);
            stop();
        }
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        streamClients.values().forEach(StreamClient::close);
        streamClients.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        publicUrl = "";
    }

    public void recordPickup(Player player, CropDefinition crop, long amount) {
        playerNames.put(player.getUniqueId(), player.getName());
        activity.addFirst(new Activity(ids.incrementAndGet(), System.currentTimeMillis(), player.getName(),
                crop.id(), displayName(crop), amount, "pickup"));
        trimActivity();
    }

    public void recordAutomatedPickup(CropDefinition crop, long amount) {
        activity.addFirst(new Activity(ids.incrementAndGet(), System.currentTimeMillis(), "Automatic farm",
                crop.id(), displayName(crop), amount, "pickup"));
        trimActivity();
    }

    public void recordReset(CropDefinition crop) {
        String cropId = crop == null ? "all" : crop.id();
        String cropName = crop == null ? "All crops" : displayName(crop);
        activity.addFirst(new Activity(ids.incrementAndGet(), System.currentTimeMillis(), "Server",
                cropId, cropName, 0, "reset"));
        trimActivity();
        lastHistorySample = 0;
        refreshNow();
    }

    public void refreshNow() {
        if (!plugin.isEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.put(player.getUniqueId(), player.getName());
        }
        ProgressSnapshot progress = plugin.progress().snapshot();
        long now = System.currentTimeMillis();
        long overall = total(progress.totals().values());
        sampleHistory(now, overall);
        snapshotJson = buildSnapshot(progress, now, overall);
        broadcast(snapshotJson);
    }

    public boolean isRunning() {
        return server != null;
    }

    public String publicUrl() {
        return publicUrl;
    }

    private String buildSnapshot(ProgressSnapshot progress, long now, long overall) {
        PluginSettings settings = plugin.configManager().settings();
        long target = plugin.progress().target();
        int cropCount = plugin.progress().crops().size();
        long goal = saturatingMultiply(target, cropCount);
        List<Map<String, Object>> crops = new ArrayList<>();
        int index = 0;
        for (CropDefinition crop : plugin.progress().crops().values()) {
            long amount = progress.totals().getOrDefault(crop.id(), 0L);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", crop.id());
            item.put("name", displayName(crop));
            item.put("material", crop.item().getKey().getKey());
            item.put("amount", amount);
            item.put("target", target);
            item.put("percent", percent(amount, target));
            item.put("complete", amount >= target);
            item.put("color", CROP_COLORS.get(index++ % CROP_COLORS.size()));
            crops.add(item);
        }
        crops.sort(Comparator.comparingDouble(item -> -((Number) item.get("percent")).doubleValue()));

        List<Map<String, Object>> leaderboard = progress.contributions().entrySet().stream()
                .map(entry -> leaderboardEntry(entry.getKey(), entry.getValue(), overall))
                .sorted(Comparator.comparingLong(item -> -((Number) item.get("total")).longValue()))
                .limit(12)
                .toList();

        List<Map<String, Object>> activityPayload = activity.stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("time", item.time());
            value.put("player", item.player());
            value.put("cropId", item.cropId());
            value.put("cropName", item.cropName());
            value.put("amount", item.amount());
            value.put("type", item.type());
            return value;
        }).toList();

        List<Map<String, Object>> historyPayload = history.stream().map(point -> Map.<String, Object>of(
                "time", point.time(), "total", point.total())).toList();

        double hourlyRate = hourlyRate();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ready", true);
        root.put("generatedAt", now);
        root.put("generatedAtIso", Instant.ofEpochMilli(now).toString());
        root.put("challenge", Map.of(
                "targetPerCrop", target,
                "cropCount", cropCount,
                "completedCount", progress.completed().values().stream().filter(Boolean::booleanValue).count(),
                "overall", overall,
                "goal", goal,
                "percent", percent(overall, goal),
                "remaining", Math.max(0L, goal - overall),
                "hourlyRate", hourlyRate
        ));
        root.put("server", Map.of(
                "name", Bukkit.getServer().getName(),
                "minecraftVersion", Bukkit.getMinecraftVersion(),
                "pluginVersion", plugin.getPluginMeta().getVersion(),
                "onlinePlayers", Bukkit.getOnlinePlayers().size(),
                "maxPlayers", Bukkit.getMaxPlayers()
        ));
        root.put("crops", crops);
        root.put("leaderboard", leaderboard);
        root.put("activity", activityPayload);
        root.put("history", historyPayload);
        root.put("historySampleSeconds", settings.webHistorySampleSeconds());
        return Json.encode(root);
    }

    private Map<String, Object> leaderboardEntry(UUID uuid, Map<String, Long> contributions, long overall) {
        long amount = total(contributions.values());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("uuid", uuid.toString());
        item.put("name", playerNames.getOrDefault(uuid, "Player " + uuid.toString().substring(0, 8)));
        item.put("total", amount);
        item.put("share", percent(amount, overall));
        item.put("cropCount", contributions.values().stream().filter(value -> value > 0).count());
        return item;
    }

    private void sampleHistory(long now, long overall) {
        PluginSettings settings = plugin.configManager().settings();
        if (lastHistorySample == 0 || now - lastHistorySample >= settings.webHistorySampleSeconds() * 1_000L) {
            history.addLast(new HistoryPoint(now, overall));
            lastHistorySample = now;
        }
        int maxPoints = Math.min(5_000, Math.max(2,
                settings.webHistoryRetentionHours() * 3_600 / settings.webHistorySampleSeconds()));
        long oldest = now - settings.webHistoryRetentionHours() * 3_600_000L;
        while (history.size() > maxPoints || (!history.isEmpty() && history.peekFirst().time() < oldest)) {
            history.removeFirst();
        }
    }

    private double hourlyRate() {
        if (history.size() < 2) {
            return 0;
        }
        HistoryPoint first = history.peekFirst();
        HistoryPoint last = history.peekLast();
        long elapsed = last.time() - first.time();
        if (elapsed <= 0 || last.total() <= first.total()) {
            return 0;
        }
        return (last.total() - first.total()) * 3_600_000.0 / elapsed;
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        if (!isReadable(exchange)) {
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", snapshotJson.getBytes(StandardCharsets.UTF_8), false);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!isReadable(exchange)) {
            return;
        }
        byte[] payload = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "application/json; charset=utf-8", payload, false);
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            return;
        }
        if (streamClients.size() >= MAX_STREAM_CLIENTS) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Too many live dashboard connections".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        long id = ids.incrementAndGet();
        StreamClient client = new StreamClient();
        streamClients.put(id, client);
        try (OutputStream output = exchange.getResponseBody()) {
            writeEvent(output, snapshotJson);
            while (!client.closed()) {
                String payload = client.next(15, TimeUnit.SECONDS);
                if (payload == null) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } else if (!payload.isEmpty()) {
                    writeEvent(output, payload);
                }
            }
        } catch (IOException ignored) {
            // The browser closed or refreshed the page.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            client.close();
            streamClients.remove(id);
            exchange.close();
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!isReadable(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        path = path.equals("/") ? "index.html" : path.substring(1);
        if (path.contains("..") || path.contains("\\")) {
            send(exchange, 400, "text/plain; charset=utf-8", "Bad request".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        byte[] payload = resource("web/" + path);
        if (payload == null && !path.contains(".")) {
            path = "index.html";
            payload = resource("web/index.html");
        }
        if (payload == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        send(exchange, 200, contentType(path), payload, path.startsWith("assets/"));
    }

    private boolean isGet(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET");
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
        return false;
    }

    private boolean isReadable(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("GET")
                || exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
        return false;
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] payload, boolean immutable)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", immutable ? "public, max-age=31536000, immutable" : "no-cache");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            headers.set("Content-Length", Integer.toString(payload.length));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private void securityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; "
                + "script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; "
                + "font-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream input = plugin.getResource(path)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private void broadcast(String payload) {
        streamClients.values().forEach(client -> client.offer(payload));
    }

    private static void writeEvent(OutputStream output, String payload) throws IOException {
        output.write("event: snapshot\ndata: ".getBytes(StandardCharsets.UTF_8));
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.write("\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void trimActivity() {
        while (activity.size() > MAX_ACTIVITY) {
            activity.removeLast();
        }
    }

    private static String determinePublicUrl(PluginSettings settings) {
        if (!settings.webPublicUrl().isBlank()) {
            return settings.webPublicUrl().replaceAll("/+$", "");
        }
        String host = settings.webBindAddress();
        if (host.equals("0.0.0.0") || host.equals("::")) {
            host = "localhost";
        }
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + settings.webPort();
    }

    private static String contentType(String path) {
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/html; charset=utf-8";
    }

    private static String displayName(CropDefinition crop) {
        return PlainTextComponentSerializer.plainText().serialize(
                MiniMessage.miniMessage().deserialize(crop.displayMiniMessage()));
    }

    private static long total(Iterable<Long> values) {
        long total = 0;
        for (long value : values) {
            total = total > Long.MAX_VALUE - value ? Long.MAX_VALUE : total + value;
        }
        return total;
    }

    private static long saturatingMultiply(long value, int multiplier) {
        return multiplier > 0 && value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static double percent(long amount, long target) {
        if (target <= 0) {
            return 100;
        }
        return Math.round(Math.min(100.0, amount * 100.0 / target) * 100.0) / 100.0;
    }

    private record Activity(long id, long time, String player, String cropId, String cropName,
                            long amount, String type) {
    }

    private record HistoryPoint(long time, long total) {
    }

    private static final class StreamClient {
        private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        private volatile boolean closed;

        void offer(String payload) {
            if (!queue.offer(payload)) {
                queue.poll();
                queue.offer(payload);
            }
        }

        String next(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        void close() {
            closed = true;
            queue.offer("");
        }

        boolean closed() {
            return closed;
        }
    }
}
