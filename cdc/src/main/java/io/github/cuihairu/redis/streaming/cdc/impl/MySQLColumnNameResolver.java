package io.github.cuihairu.redis.streaming.cdc.impl;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

interface MySQLColumnNameResolver extends AutoCloseable {

    List<String> resolve(String database, String table);

    @Override
    void close();
}

@Slf4j
final class DriverManagerMySQLColumnNameResolver implements MySQLColumnNameResolver {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int queryTimeoutSeconds;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    DriverManagerMySQLColumnNameResolver(String jdbcUrl, String username, String password, int queryTimeoutSeconds) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
    }

    @Override
    public List<String> resolve(String database, String table) {
        if (database == null || database.isBlank() || table == null || table.isBlank()) {
            return List.of();
        }
        String key = database + "." + table;
        return cache.computeIfAbsent(key, k -> {
            try {
                return Collections.unmodifiableList(queryColumnNames(database, table));
            } catch (Exception e) {
                log.debug("Failed to resolve MySQL column names for {}", k, e);
                return List.of();
            }
        });
    }

    private List<String> queryColumnNames(String database, String table) throws Exception {
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                "ORDER BY ORDINAL_POSITION";

        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            stmt.setString(1, database);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    @Override
    public void close() {
        cache.clear();
    }
}
