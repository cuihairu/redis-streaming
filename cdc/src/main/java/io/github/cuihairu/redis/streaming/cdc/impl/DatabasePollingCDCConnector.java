package io.github.cuihairu.redis.streaming.cdc.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    // CDC-M1: bounded queue (capacity via "event.queue.capacity", default 10_000). Producers
    // block while full (see enqueueWithBackpressure) so a slow consumer applies backpressure
    // instead of growing the heap; nothing is silently dropped while the connector runs.
    private final java.util.concurrent.BlockingQueue<ChangeEvent> eventQueue;
    // CDC-M2: written by the polling scheduler thread AND by user-thread commit()/reset()
    // (doCommit/doResetToPosition), read by the public snapshot getter. A plain HashMap
    // corrupts under concurrent resize (lost watermarks -> silent re-polling, CME/NPE in
    // copies). All put sites store non-null values (249/342 null-guarded; doCommit parses
    // non-empty strings), so a null-hostile ConcurrentHashMap is a safe drop-in.
    private final Map<String, Object> lastPolledValues = new ConcurrentHashMap<>();
    private List<String> tables;
    private String timestampColumn;
    private String incrementalColumn;
    private int queryTimeoutSeconds;
    // CDC-M1: rows per scan statement ("poll.batch.limit", default 1000); every fetch is
    // LIMIT limit+1 so a truncated batch can never split a watermark tie group.
    private int pollBatchLimit = BackpressureSettings.DEFAULT_POLL_BATCH_LIMIT;
    // CDC-M1: safety bound for the batching loop so a watermark that cannot advance
    // (e.g. an all-NULL incremental column) cannot spin a single poll() forever.
    private static final int MAX_BATCHES_PER_ROUND = 1_000;
    private TableFilter tableFilter;
    private final AtomicBoolean snapshotPending = new AtomicBoolean(false);
    private final AtomicLong snapshotRecordCount = new AtomicLong();

    public DatabasePollingCDCConnector(CDCConfiguration configuration) {
        super(configuration);
        this.eventQueue = new java.util.concurrent.ArrayBlockingQueue<>(
                BackpressureSettings.positiveInt(configuration, BackpressureSettings.QUEUE_CAPACITY_PROPERTY,
                        BackpressureSettings.DEFAULT_QUEUE_CAPACITY));
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
        // CDC-M1: configurable per-statement scan cap; invalid values fall back to the default
        this.pollBatchLimit = BackpressureSettings.positiveInt(
                configuration, BackpressureSettings.POLL_BATCH_LIMIT_PROPERTY,
                BackpressureSettings.DEFAULT_POLL_BATCH_LIMIT);

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

        try {
            initializeSnapshotOrBaseline();

            startScheduledPolling();
        } catch (Exception e) {
            // CDC-M4: baseline/polling initialization failed after the pool was created —
            // close it here, or a failed start leaks the Hikari housekeeping threads.
            try {
                closeDataSource();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }

        log.info("Database polling CDC connector started for tables: {}", tables);
    }

    @Override
    protected void doStop() throws Exception {
        closeDataSource();
        // Keep eventQueue and lastPolledValues across stop/start: undelivered events survive a
        // graceful restart and the polling position is not re-baselined at MAX (which silently
        // skipped everything scanned-but-not-delivered plus everything inserted while stopped).
    }

    /**
     * Close the Hikari pool created by {@link #createDataSource} (CDC-M4).
     *
     * <p>Shared by {@code doStop()} and {@code doStart()}'s failure path so a start that dies
     * after {@code createDataSource()} never leaks the pool. No-op when no pool was created.
     */
    private void closeDataSource() {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
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
            // split with limit 2: the watermark value itself contains colons (timestamps like
            // "2024-01-01 10:15:30.0"); the old unlimited split kept only "2024-01-01 10" (CDC-H4).
            String[] parts = position.split(":", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
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
            // Same limit-2 split as doCommit — see CDC-H4.
            String[] parts = position.split(":", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
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

    /**
     * Decide how to start the polling baseline.
     *
     * <p>When an initial snapshot is configured ({@code snapshot.enabled=true} and
     * {@code snapshot.mode != never}), {@code lastPolledValues} is left empty so the first
     * scans emit rows that already exist as INSERT events. Otherwise {@link
     * #initializeLastPolledValues()} baselines at MAX(incremental column) and existing rows
     * are skipped.
     */
    private void initializeSnapshotOrBaseline() throws SQLException {
        if (!lastPolledValues.isEmpty()) {
            // Restart of the same instance: resume from the preserved polling positions instead
            // of re-baselining at MAX(col), which skipped rows the previous run never delivered.
            log.info("Resuming polling positions for tables {}: {}", lastPolledValues.keySet(), lastPolledValues);
            return;
        }
        if (shouldCaptureSnapshot()) {
            snapshotPending.set(true);
            snapshotRecordCount.set(0);
            notifyEvent(listener -> listener.onSnapshotStarted(getName(), tables.size()));
            log.info("Snapshot enabled (mode={}): existing rows of tables {} will be emitted as INSERT events",
                    configuration.getSnapshotMode(), tables);
        } else {
            initializeLastPolledValues();
        }
    }

    private boolean shouldCaptureSnapshot() {
        if (!configuration.isSnapshotEnabled()) {
            return false;
        }
        String mode = configuration.getSnapshotMode();
        return mode == null || !"never".equalsIgnoreCase(mode.trim());
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
            return;
        }
        if (snapshotPending.compareAndSet(true, false)) {
            long records = snapshotRecordCount.getAndSet(0);
            notifyEvent(listener -> listener.onSnapshotCompleted(getName(), records));
            log.info("Snapshot completed for connector {}: {} records captured", getName(), records);
        }
    }

    private void pollTableForChanges(Connection connection, String table) throws SQLException {
        String column = incrementalColumn != null ? incrementalColumn : timestampColumn;
        Object lastValue = lastPolledValues.get(table);
        // CDC-M1: the persisted watermark may only advance past rows that were actually
        // enqueued (emit-then-advance), so an aborted round resumes without losing rows.
        Object highWater = lastValue;
        int batches = 0;

        while (running.get() && !Thread.currentThread().isInterrupted()
                && batches++ < MAX_BATCHES_PER_ROUND) {
            // CDC-M1: bounded fetch. The +1 probe row tells "drained" (<= limit rows) apart
            // from "truncated" (limit+1 rows) without a second count query.
            String query = (lastValue != null)
                    ? String.format("SELECT * FROM %s WHERE %s > ? ORDER BY %s LIMIT %d", table, column, column, pollBatchLimit + 1)
                    : String.format("SELECT * FROM %s ORDER BY %s LIMIT %d", table, column, pollBatchLimit + 1);
            List<BufferedRow> rows = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
                if (lastValue != null) {
                    stmt.setObject(1, lastValue);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> rowData = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            rowData.put(metaData.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(new BufferedRow(rowData, rs.getObject(column)));
                    }
                }
            }

            if (rows.isEmpty()) {
                break; // drained
            }
            boolean truncated = rows.size() > pollBatchLimit;
            int emitCount = truncated ? pollBatchLimit : rows.size();
            if (truncated) {
                // Never emit a partially-fetched watermark group: rows sharing the boundary
                // value are left for the next fetch (which re-reads them via "> lastEmitted").
                // A group larger than the whole batch is flushed forcibly (warned) — raise
                // poll.batch.limit or use a monotonic column for such data.
                Object boundary = rows.get(emitCount - 1).value;
                if (Objects.equals(boundary, rows.get(emitCount).value)) {
                    while (emitCount > 0 && Objects.equals(rows.get(emitCount - 1).value, boundary)) {
                        emitCount--;
                    }
                    if (emitCount == 0) {
                        log.warn("Table {} has more than {} rows sharing one watermark value ({}); "
                                        + "flushing the first batch to keep making progress",
                                table, pollBatchLimit, boundary);
                        emitCount = pollBatchLimit;
                    }
                }
            }

            boolean aborted = false;
            for (int i = 0; i < emitCount; i++) {
                BufferedRow row = rows.get(i);
                if (!enqueueWithBackpressure(toChangeEvent(table, row))) {
                    aborted = true; // connector stopping: keep only what landed
                    break;
                }
                if (row.value != null) {
                    highWater = row.value;
                }
                if (snapshotPending.get()) {
                    snapshotRecordCount.incrementAndGet();
                }
            }
            if (aborted) {
                break;
            }
            if (!truncated) {
                break; // fewer rows than the probe -> table fully scanned for this round
            }
            Object previous = lastValue;
            lastValue = rows.get(emitCount - 1).value; // resume strictly after what was emitted
            if (Objects.equals(lastValue, previous)) {
                break; // no progress (e.g. all-NULL incremental column): end the round instead
                // of spinning on the identical batch; the next poll() retries from the same point
            }
        }

        if (highWater != null && !Objects.equals(highWater, lastPolledValues.get(table))) {
            lastPolledValues.put(table, highWater);
            this.currentPosition = table + ":" + highWater;
        }
    }

    /** One buffered scan row: its column data plus the watermark column's value. */
    private static final class BufferedRow {
        final Map<String, Object> data;
        final Object value;

        BufferedRow(Map<String, Object> data, Object value) {
            this.data = data;
            this.value = value;
        }
    }

    private ChangeEvent toChangeEvent(String table, BufferedRow row) {
        ChangeEvent changeEvent = new ChangeEvent(
                ChangeEvent.EventType.INSERT, // Polling can only detect inserts/updates, not distinguish
                extractDatabase(table),
                extractTableName(table),
                generateKey(row.data),
                null,
                row.data
        );
        setEventMetadata(changeEvent, table, row.value);
        return changeEvent;
    }

    /**
     * CDC-M1: enqueue with loss-free backpressure. Blocks (in 50ms slices) while the bounded
     * queue is full, applying backpressure to the scan instead of growing the heap. Returns
     * {@code false} only when the connector is stopping or the thread was interrupted — the
     * un-emitted rows stay below the persisted watermark and are re-polled on the next start.
     */
    private boolean enqueueWithBackpressure(ChangeEvent event) {
        while (running.get()) {
            try {
                if (eventQueue.offer(event, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Connector {} stopped before its bounded event queue accepted a change event", getName());
        return false;
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
