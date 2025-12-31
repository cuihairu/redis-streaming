package io.github.cuihairu.redis.streaming.cdc.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Generic database polling CDC connector implementation
 * Uses timestamp or incremental ID based polling to detect changes
 */
@Slf4j
public class DatabasePollingCDCConnector extends AbstractCDCConnector {

    private static final String JDBC_URL_PROPERTY = "jdbc.url";
    private static final String DRIVER_CLASS_PROPERTY = "driver.class";
    private static final String TABLES_PROPERTY = "tables";
    private static final String TIMESTAMP_COLUMN_PROPERTY = "timestamp.column";
    private static final String INCREMENTAL_COLUMN_PROPERTY = "incremental.column";
    private static final String QUERY_TIMEOUT_PROPERTY = "query.timeout.seconds";

    private DataSource dataSource;
    private final Queue<ChangeEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, Object> lastPolledValues = new HashMap<>();
    private List<String> tables;
    private String timestampColumn;
    private String incrementalColumn;
    private int queryTimeoutSeconds;
    private TableFilter tableFilter;

    public DatabasePollingCDCConnector(CDCConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void doStart() throws Exception {
        configuration.validate();

        String jdbcUrl = (String) configuration.getProperty(JDBC_URL_PROPERTY);
        String driverClass = (String) configuration.getProperty(DRIVER_CLASS_PROPERTY);
        String username = configuration.getUsername();
        String password = configuration.getPassword();

        if (jdbcUrl == null) {
            throw new IllegalArgumentException("JDBC URL is required for database polling CDC");
        }

        this.tableFilter = TableFilter.from(configuration.getTableIncludes(), configuration.getTableExcludes());
        this.tables = parseTableList((String) configuration.getProperty(TABLES_PROPERTY));
        this.timestampColumn = (String) configuration.getProperty(TIMESTAMP_COLUMN_PROPERTY, "updated_at");
        this.incrementalColumn = (String) configuration.getProperty(INCREMENTAL_COLUMN_PROPERTY);
        this.queryTimeoutSeconds = Integer.parseInt(
            String.valueOf(configuration.getProperty(QUERY_TIMEOUT_PROPERTY, "30"))
        );

        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("At least one table must be specified for polling");
        }

        // Apply include/exclude filtering to the configured table list.
        if (tableFilter != null) {
            List<String> filtered = new ArrayList<>();
            for (String t : tables) {
                String db = extractDatabase(t);
                String tn = extractTableName(t);
                if (tableFilter.allowed(db, tn)) {
                    filtered.add(t);
                }
            }
            this.tables = filtered;
        }

        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("No tables left after include/exclude filtering");
        }

        this.dataSource = createDataSource(jdbcUrl, driverClass, username, password);

        initializeLastPolledValues();

        startScheduledPolling();

        log.info("Database polling CDC connector started for tables: {}", tables);
    }

    @Override
    protected void doStop() throws Exception {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
        eventQueue.clear();
        lastPolledValues.clear();
    }

    @Override
    protected List<ChangeEvent> doPoll() throws Exception {
        List<ChangeEvent> events = new ArrayList<>();
        int batchSize = configuration.getBatchSize();

        for (int i = 0; i < batchSize && !eventQueue.isEmpty(); i++) {
            ChangeEvent event = eventQueue.poll();
            if (event != null) {
                events.add(event);
            }
        }

        pollTablesForChanges();

        return events;
    }

    @Override
    protected void doCommit(String position) throws Exception {
        if (position != null && position.contains(":")) {
            String[] parts = position.split(":");
            if (parts.length >= 2) {
                String table = parts[0];
                String value = parts[1];
                lastPolledValues.put(table, value);
                log.debug("Committed position for table {}: {}", table, value);
            }
        }
    }

    @Override
    protected void doResetToPosition(String position) throws Exception {
        if (position != null && position.contains(":")) {
            String[] parts = position.split(":");
            if (parts.length >= 2) {
                String table = parts[0];
                String value = parts[1];
                lastPolledValues.put(table, value);
                log.info("Reset position for table {}: {}", table, value);
            }
        }
    }

    private DataSource createDataSource(String jdbcUrl, String driverClass, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        if (driverClass != null) {
            config.setDriverClassName(driverClass);
        }

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    private List<String> parseTableList(String tablesProperty) {
        if (tablesProperty == null || tablesProperty.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (String table : tablesProperty.split(",")) {
            if (table == null) {
                continue;
            }
            String t = table.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    private void initializeLastPolledValues() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                Object lastValue = getLastPolledValue(connection, table);
                if (lastValue != null) {
                    lastPolledValues.put(table, lastValue);
                }
                log.debug("Initialized last polled value for table {}: {}", table, lastValue);
            }
        }
    }

    private Object getLastPolledValue(Connection connection, String table) throws SQLException {
        String column = incrementalColumn != null ? incrementalColumn : timestampColumn;
        String query = String.format("SELECT MAX(%s) FROM %s", column, table);

        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    return rs.getObject(1);
                }
            }
        }
        return null;
    }

    private void pollTablesForChanges() {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                pollTableForChanges(connection, table);
            }
        } catch (SQLException e) {
            log.error("Error polling tables for changes", e);
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
        }
    }

    private void pollTableForChanges(Connection connection, String table) throws SQLException {
        String column = incrementalColumn != null ? incrementalColumn : timestampColumn;
        Object lastValue = lastPolledValues.get(table);

        String query;
        if (lastValue != null) {
            query = String.format("SELECT * FROM %s WHERE %s > ? ORDER BY %s", table, column, column);
        } else {
            query = String.format("SELECT * FROM %s ORDER BY %s", table, column);
        }

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setQueryTimeout(queryTimeoutSeconds);

            if (lastValue != null) {
                stmt.setObject(1, lastValue);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                Object newLastValue = lastValue;

                while (rs.next()) {
                    Map<String, Object> rowData = new HashMap<>();

                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        rowData.put(columnName, value);
                    }

                    Object currentValue = rs.getObject(column);
                    if (currentValue != null) {
                        newLastValue = currentValue;
                    }

                    ChangeEvent changeEvent = new ChangeEvent(
                            ChangeEvent.EventType.INSERT, // Polling can only detect inserts/updates, not distinguish
                            extractDatabase(table),
                            extractTableName(table),
                            generateKey(rowData),
                            null,
                            rowData
                    );

                    setEventMetadata(changeEvent, table, currentValue);
                    eventQueue.offer(changeEvent);
                }

                if (newLastValue != null && !Objects.equals(newLastValue, lastValue)) {
                    lastPolledValues.put(table, newLastValue);
                    this.currentPosition = table + ":" + newLastValue;
                }
            }
        }
    }

    private String extractDatabase(String table) {
        if (table.contains(".")) {
            return table.substring(0, table.indexOf("."));
        }
        return "default";
    }

    private String extractTableName(String table) {
        if (table.contains(".")) {
            return table.substring(table.indexOf(".") + 1);
        }
        return table;
    }

    private void setEventMetadata(ChangeEvent changeEvent, String table, Object value) {
        changeEvent.setSource(getName());
        changeEvent.setPosition(table + ":" + (value != null ? value.toString() : "null"));
        changeEvent.setTimestamp(Instant.now());
    }

    private String generateKey(Map<String, Object> data) {
        // Try to find primary key or use all non-null values
        Object id = data.get("id");
        if (id != null) {
            return id.toString();
        }

        Object pk = data.get("pk");
        if (pk != null) {
            return pk.toString();
        }

        return data.values().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .reduce((a, b) -> a + "_" + b)
                .orElse("unknown");
    }

    /**
     * Get tables being polled
     */
    public List<String> getTables() {
        if (tables == null) {
            return List.of();
        }
        return new ArrayList<>(tables);
    }

    /**
     * Get timestamp column used for polling
     */
    public String getTimestampColumn() {
        return timestampColumn;
    }

    /**
     * Get incremental column used for polling
     */
    public String getIncrementalColumn() {
        return incrementalColumn;
    }

    /**
     * Get last polled values for all tables
     */
    public Map<String, Object> getLastPolledValues() {
        return new HashMap<>(lastPolledValues);
    }

    /**
     * Check if data source is available
     */
    public boolean isDataSourceAvailable() {
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
