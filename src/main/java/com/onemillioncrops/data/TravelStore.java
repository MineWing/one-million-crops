package com.onemillioncrops.data;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable named locations. Use a background executor for database operations. */
public final class TravelStore implements AutoCloseable {
    private final Connection connection;
    private volatile java.util.Map<String, List<String>> namesSnapshot = java.util.Map.of();
    private final java.util.Map<String, java.util.TreeSet<String>> index = new java.util.HashMap<>();

    public TravelStore(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS locations (scope TEXT NOT NULL, name TEXT NOT NULL, "
                    + "world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, "
                    + "yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY(scope, name))");
            try (ResultSet rows = statement.executeQuery("SELECT scope, name FROM locations")) {
                while (rows.next()) index.computeIfAbsent(rows.getString(1), ignored -> new java.util.TreeSet<>()).add(rows.getString(2));
            }
            publishNames();
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    public synchronized void set(String scope, String name, Point point) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO locations VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(scope, name) DO UPDATE SET "
                        + "world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch")) {
            statement.setString(1, scope);
            statement.setString(2, normalize(name));
            statement.setString(3, point.world().toString());
            statement.setDouble(4, point.x());
            statement.setDouble(5, point.y());
            statement.setDouble(6, point.z());
            statement.setFloat(7, point.yaw());
            statement.setFloat(8, point.pitch());
            statement.executeUpdate();
            index.computeIfAbsent(scope, ignored -> new java.util.TreeSet<>()).add(normalize(name));
            publishNames();
        }
    }

    public synchronized Point get(String scope, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM locations WHERE scope=? AND name=?")) {
            statement.setString(1, scope);
            statement.setString(2, normalize(name));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Point(UUID.fromString(result.getString("world")),
                        result.getDouble("x"), result.getDouble("y"), result.getDouble("z"),
                        result.getFloat("yaw"), result.getFloat("pitch")) : null;
            }
        }
    }

    public synchronized boolean delete(String scope, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM locations WHERE scope=? AND name=?")) {
            statement.setString(1, scope);
            statement.setString(2, normalize(name));
            boolean removed = statement.executeUpdate() != 0;
            if (removed && index.containsKey(scope)) index.get(scope).remove(normalize(name));
            publishNames();
            return removed;
        }
    }

    public List<String> names(String scope) {
        return namesSnapshot.getOrDefault(scope, List.of());
    }

    private void publishNames() {
        java.util.Map<String, List<String>> snapshot = new java.util.HashMap<>();
        index.forEach((scope, names) -> snapshot.put(scope, List.copyOf(names)));
        namesSnapshot = java.util.Map.copyOf(snapshot);
    }

    public static String normalize(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Use 1 to 32 letters, numbers, underscores or hyphens.");
        }
        return normalized;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    public record Point(UUID world, double x, double y, double z, float yaw, float pitch) { }
}
