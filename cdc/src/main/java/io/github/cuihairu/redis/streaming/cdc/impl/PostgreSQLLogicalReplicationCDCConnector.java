package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL logical replication CDC connector implementation
 */
@Slf4j
public class PostgreSQLLogicalReplicationCDCConnector extends AbstractCDCConnector {

    private static final String HOSTNAME_PROPERTY = "hostname";
    private static final String PORT_PROPERTY = "port";
    private static final String DATABASE_PROPERTY = "database";
    private static final String SLOT_NAME_PROPERTY = "slot.name";
    private static final String PUBLICATION_NAME_PROPERTY = "publication.name";
    private static final String STATUS_INTERVAL_PROPERTY = "status.interval.ms";
    // CDC-H3: minimum delay between replication-stream reconnect attempts (the pull model
    // retries from poll(), so no extra thread is needed).
    private static final String RECONNECT_BACKOFF_PROPERTY = "reconnect.backoff.ms";
    private static final int DEFAULT_RECONNECT_BACKOFF_MS = 1_000;

    private Connection connection;
    private PGReplicationStream replicationStream;
    // CDC-M1: bounded queue ("event.queue.capacity", default 10_000). The WAL reader thread
    // blocks while it is full (see enqueueBackpressured) so a slow consumer applies backpressure
    // to replication instead of growing the heap; nothing is silently dropped while the
    // connector runs (the reader throws on stop, leaving the stream behind the undelivered
    // message so it is replayed after restart).
    private final java.util.concurrent.BlockingQueue<ChangeEvent> eventQueue;
    private String slotName;
    private String publicationName;
    private LogSequenceNumber lastReceivedLSN;
    private long statusIntervalMs;
    private TableFilter tableFilter;
    // CDC-H3: stream-failure recovery state. A dead stream used to be polled forever
    // (SQLException swallowed per poll): health stayed HEALTHY, LSN feedback stopped and the
    // server retained WAL in the slot without bound.
    private volatile long reconnectBackoffMs = DEFAULT_RECONNECT_BACKOFF_MS;
    private volatile long lastReconnectAttemptMs;
    private volatile boolean slotInvalidated;
    // CDC-H3: a missing/invalidated slot only halts the connector once a stream has actually
    // run on it. {@code running} flips true before doStart() finishes, so an early poll()
    // (e.g. CDCManager's scheduler racing start()) can reach the reconnect path while the
    // slot does not exist yet — that must read as "not ready", never as "invalidated".
    private volatile boolean streamEverStarted;

    // Pattern for parsing logical replication messages (test-decoding format)
    private static final Pattern TABLE_PATTERN = Pattern.compile("table\\s+(\\w+)\\.(\\w+):");
    private static final Pattern INSERT_PATTERN = Pattern.compile("INSERT:\\s*(.+)");
    private static final Pattern UPDATE_PATTERN = Pattern.compile("UPDATE:\\s*(.+)");
    private static final Pattern DELETE_PATTERN = Pattern.compile("DELETE:\\s*(.+)");

    public PostgreSQLLogicalReplicationCDCConnector(CDCConfiguration configuration) {
        super(configuration);
        this.eventQueue = new java.util.concurrent.ArrayBlockingQueue<>(
                BackpressureSettings.positiveInt(configuration, BackpressureSettings.QUEUE_CAPACITY_PROPERTY,
                        BackpressureSettings.DEFAULT_QUEUE_CAPACITY));
    }

    /**
     * CDC-M1: blocking, loss-free enqueue while the connector runs. On stop/interrupt the
     * event is not silently discarded: the IllegalStateException unwinds the WAL message
     * handler before the replication client acknowledges further data, so the stream stays
     * behind the undelivered message and resumes it after restart (at-least-once).
     */
    private void enqueueBackpressured(ChangeEvent event) {
        while (running.get()) {
            try {
                if (eventQueue.offer(event, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(
                "connector " + getName() + " stopping: change event not enqueued (CDC-M1 backpressure)");
    }

    @Override
    protected void doStart() throws Exception {
        configuration.validate();

        String hostname = (String) configuration.getProperty(HOSTNAME_PROPERTY, "localhost");
        int port = Integer.parseInt(String.valueOf(configuration.getProperty(PORT_PROPERTY, "5432")));
        String database = (String) configuration.getProperty(DATABASE_PROPERTY);
        String username = configuration.getUsername();
        String password = configuration.getPassword();

        if (database == null) {
            throw new IllegalArgumentException("Database name is required for PostgreSQL CDC");
        }

        this.slotName = (String) configuration.getProperty(SLOT_NAME_PROPERTY, "cdc_slot");
        this.publicationName = (String) configuration.getProperty(PUBLICATION_NAME_PROPERTY);
        this.statusIntervalMs = Long.parseLong(
            String.valueOf(configuration.getProperty(STATUS_INTERVAL_PROPERTY, "10000"))
        );
        this.tableFilter = TableFilter.from(configuration.getTableIncludes(), configuration.getTableExcludes());
        // CDC-H3: a fresh start resets the recovery state — a halted (invalidated-slot) run
        // only resumes when the operator explicitly restarts the connector.
        this.slotInvalidated = false;
        this.streamEverStarted = false;
        this.lastReconnectAttemptMs = 0;
        this.reconnectBackoffMs = BackpressureSettings.positiveInt(configuration,
                RECONNECT_BACKOFF_PROPERTY, DEFAULT_RECONNECT_BACKOFF_MS);

        openConnection(hostname, port, database, username, password);

        createReplicationSlotIfNotExists();
        createPublicationIfNotExists();

        startReplicationStream();

        log.info("PostgreSQL logical replication CDC connector started: {}:{}/{}, slot: {}",
                 hostname, port, database, slotName);
    }

    /**
     * Opens (or reopens) the replication connection. CDC-H3: factored out of {@code doStart}
     * so the reconnect path can re-establish the connection with identical settings.
     * Protected so tests can substitute the reopened connection.
     */
    protected void openConnection(String hostname, int port, String database, String username, String password)
            throws SQLException {
        Properties props = new Properties();
        PGProperty.USER.set(props, username);
        PGProperty.PASSWORD.set(props, password);
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
        PGProperty.REPLICATION.set(props, "database");
        PGProperty.PREFER_QUERY_MODE.set(props, "simple");

        String url = String.format("jdbc:postgresql://%s:%d/%s", hostname, port, database);
        this.connection = DriverManager.getConnection(url, props);
    }

    @Override
    protected void doStop() throws Exception {
        if (replicationStream != null) {
            replicationStream.close();
            replicationStream = null;
        }

        if (connection != null && !connection.isClosed()) {
            connection.close();
        }

        // eventQueue is intentionally NOT cleared: on restart the stream resumes at
        // lastReceivedLSN, which is already past those queued events — clearing the queue here
        // used to permanently drop every captured-but-undelivered event (CDC-H2).
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

        // CDC-H3: a torn-down stream is re-established from poll() (the connector's
        // heartbeat), rate-limited by the reconnect backoff. An invalidated slot never
        // reconnects on its own — that path halts loudly instead of silently skipping data.
        if (running.get() && !slotInvalidated && replicationStream == null) {
            attemptStreamReconnect();
        }

        if (replicationStream != null) {
            processReplicationMessages();
        }

        return events;
    }

    @Override
    protected void doCommit(String position) throws Exception {
        if (position != null && replicationStream != null) {
            LogSequenceNumber lsn = LogSequenceNumber.valueOf(position);
            replicationStream.setAppliedLSN(lsn);
            replicationStream.setFlushedLSN(lsn);
            log.debug("Committed LSN: {}", lsn);
        }
    }

    @Override
    protected void doResetToPosition(String position) throws Exception {
        if (replicationStream != null) {
            replicationStream.close();
        }

        if (position != null) {
            LogSequenceNumber lsn = LogSequenceNumber.valueOf(position);
            this.lastReceivedLSN = lsn;
        }

        startReplicationStream();
    }

    private void createReplicationSlotIfNotExists() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            String checkSlotQuery = "SELECT slot_name FROM pg_replication_slots WHERE slot_name = ?";
            try (PreparedStatement ps = connection.prepareStatement(checkSlotQuery)) {
                ps.setString(1, slotName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        String createSlotQuery = String.format(
                            "SELECT pg_create_logical_replication_slot('%s', 'test_decoding')",
                            slotName
                        );
                        stmt.execute(createSlotQuery);
                        log.info("Created replication slot: {}", slotName);
                    } else {
                        log.info("Replication slot already exists: {}", slotName);
                    }
                }
            }
        }
    }

    private void createPublicationIfNotExists() throws SQLException {
        if (publicationName == null) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            String checkPubQuery = "SELECT pubname FROM pg_publication WHERE pubname = ?";
            try (PreparedStatement ps = connection.prepareStatement(checkPubQuery)) {
                ps.setString(1, publicationName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        String createPubQuery = String.format(
                            "CREATE PUBLICATION %s FOR ALL TABLES",
                            publicationName
                        );
                        stmt.execute(createPubQuery);
                        log.info("Created publication: {}", publicationName);
                    } else {
                        log.info("Publication already exists: {}", publicationName);
                    }
                }
            }
        }
    }

    private void startReplicationStream() throws SQLException {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);

        ChainedLogicalStreamBuilder builder = pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(slotName)
            .withStatusInterval((int) statusIntervalMs, TimeUnit.MILLISECONDS);

        if (lastReceivedLSN != null) {
            builder.withStartPosition(lastReceivedLSN);
        }

        this.replicationStream = builder.start();
        this.streamEverStarted = true;
        log.info("Started logical replication stream with slot: {}", slotName);
    }

    private void processReplicationMessages() {
        try {
            ByteBuffer buffer = replicationStream.readPending();
            if (buffer == null) {
                return;
            }

            // Read only the remaining bytes; do not use the backing array length as message length.
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String message = new String(bytes, StandardCharsets.UTF_8);

            parseLogicalMessage(message);

            this.lastReceivedLSN = replicationStream.getLastReceiveLSN();
            this.currentPosition = lastReceivedLSN.asString();

        } catch (SQLException e) {
            // CDC-H3: the old code logged and kept polling the dead stream forever — health
            // stayed HEALTHY, LSN feedback stopped and the server retained WAL in the slot
            // without bound. Tear the stream down and recover instead.
            handleStreamFailure(e);
        }
    }

    /**
     * CDC-H3: a replication read failed. Reports the error (as before), tears the dead
     * stream down so {@code poll()} can rebuild it, flips health to UNHEALTHY and — when the
     * slot itself was invalidated — halts reconnect attempts loudly instead of silently
     * skipping the WAL the server already dropped. Package-private seam for the regression
     * tests. No-op recovery-wise when the connector is stopping.
     */
    void handleStreamFailure(SQLException cause) {
        log.error("Error processing replication messages", cause);
        notifyEvent(listener -> listener.onConnectorError(getName(), cause));

        if (!running.get()) {
            return; // stop raced the read failure — doStop owns the teardown
        }

        closeReplicationStream();
        // The read just failed — close the connection right away instead of probing it:
        // isValid() on a terminated replication connection can block for tens of seconds
        // (its timeout is not honored there), which delays the health flip past usefulness.
        closeConnection();

        String invalidated = invalidationReason(cause);
        if (invalidated != null) {
            markSlotInvalidated(invalidated);
            return;
        }
        updateHealthStatus(CDCHealthStatus.unhealthy(
                "PostgreSQL replication stream lost (CDC-H3): " + cause.getMessage() + "; reconnecting"));
    }

    private void closeReplicationStream() {
        if (replicationStream != null) {
            try {
                replicationStream.close();
            } catch (Exception ignore) {
                // the stream is already dead — this is best-effort cleanup
            }
            replicationStream = null;
        }
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignore) {
            }
            connection = null;
        }
    }

    /**
     * CDC-H3: rebuild connection + stream (resuming at {@code lastReceivedLSN}) after a
     * failure. Rate-limited by {@code reconnect.backoff.ms}; a lost/dropped slot stops all
     * further attempts with an explicit UNHEALTHY status rather than recreating the slot
     * mid-stream (which would silently skip the changes the server already discarded).
     */
    private void attemptStreamReconnect() {
        long now = System.currentTimeMillis();
        if (now - lastReconnectAttemptMs < reconnectBackoffMs) {
            return;
        }
        lastReconnectAttemptMs = now;
        try {
            if (connection == null || connection.isClosed()) {
                String hostname = (String) configuration.getProperty(HOSTNAME_PROPERTY, "localhost");
                int port = Integer.parseInt(String.valueOf(configuration.getProperty(PORT_PROPERTY, "5432")));
                String database = (String) configuration.getProperty(DATABASE_PROPERTY);
                openConnection(hostname, port, database, configuration.getUsername(), configuration.getPassword());
            }

            // A vanished slot only halts the connector once a stream has actually run on it —
            // before the first successful start a missing slot is just "not ready yet"
            // (doStart may still be creating it; see streamEverStarted).
            if (streamEverStarted) {
                String serverSide = slotInvalidationOnServer();
                if (serverSide != null) {
                    markSlotInvalidated(serverSide);
                    return;
                }
            }

            startReplicationStream();
            updateHealthStatus(CDCHealthStatus.healthy("PostgreSQL replication stream reconnected (CDC-H3)"));
        } catch (SQLException e) {
            String invalidated = streamEverStarted ? invalidationReason(e) : null;
            if (invalidated != null) {
                markSlotInvalidated(invalidated);
                return;
            }
            updateHealthStatus(CDCHealthStatus.unhealthy(
                    "PostgreSQL reconnect attempt failed (CDC-H3): " + e.getMessage()));
        } catch (Exception e) {
            updateHealthStatus(CDCHealthStatus.unhealthy(
                    "PostgreSQL reconnect attempt failed (CDC-H3): " + e.getMessage()));
        }
    }

    /**
     * CDC-H3: best-effort server-side slot check. Returns a reason string when the slot is
     * unusable for resumption (dropped, or flagged {@code lost} — PG 14+ removes retained WAL
     * from invalidated slots), null when it looks fine or cannot be checked (pre-14 servers
     * lack the column; the resume attempt itself then surfaces any problem).
     */
    private String slotInvalidationOnServer() {
        if (connection == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT lost FROM pg_replication_slots WHERE slot_name = ?")) {
            ps.setString(1, slotName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "replication slot '" + slotName + "' no longer exists on the server";
                }
                return rs.getBoolean("lost")
                        ? "replication slot '" + slotName + "' is marked lost (retained WAL was removed)"
                        : null;
            }
        } catch (Exception e) {
            // best-effort probe: pre-PG14 servers lack the column, a broken probe must not
            // mask the resume attempt itself (which surfaces real problems)
            return null;
        }
    }

    /** CDC-H3: server errors that mean the slot cannot resume (invalidated or dropped). */
    private static String invalidationReason(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("was invalidated") || lower.contains("cannot continue replication")
                || (lower.contains("replication slot") && lower.contains("does not exist"))) {
            return message;
        }
        return null;
    }

    /**
     * CDC-H3: mark the slot unusable and stop reconnecting — the retained WAL is gone, so
     * resuming would silently skip changes. Halting loudly (UNHEALTHY + error notification)
     * replaces the old infinite silent stall.
     */
    private void markSlotInvalidated(String reason) {
        slotInvalidated = true;
        closeReplicationStream();
        notifyEvent(listener -> listener.onConnectorError(getName(),
                new SQLException("CDC-H3 replication slot invalidated: " + reason)));
        updateHealthStatus(CDCHealthStatus.unhealthy(
                "PostgreSQL replication slot invalidated (CDC-H3): " + reason
                        + " — connector halted to avoid a silent data gap;"
                        + " recreate the slot and resetToPosition to resume"));
    }

    private void parseLogicalMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String[] lines = message.split("\n");
        String currentTable = null;
        String currentDatabase = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher tableMatcher = TABLE_PATTERN.matcher(line);
            if (tableMatcher.find()) {
                currentDatabase = tableMatcher.group(1);
                currentTable = tableMatcher.group(2);
                // Real test_decoding emits the table prefix and the operation on ONE line
                // ("table public.users: INSERT: id[integer]:1 ..."); only the synthetic
                // multi-line format separates them. Falling through (instead of `continue`)
                // lets the operation matchers see the remainder of the same line — the old
                // unconditional skip dropped every event of a real replication stream (CDC-C1).
            }

            if (currentTable == null || currentDatabase == null) {
                continue;
            }
            if (tableFilter != null && !tableFilter.allowed(currentDatabase, currentTable)) {
                continue;
            }

            Matcher insertMatcher = INSERT_PATTERN.matcher(line);
            if (insertMatcher.find()) {
                handleInsertMessage(currentDatabase, currentTable, insertMatcher.group(1));
                continue;
            }

            Matcher updateMatcher = UPDATE_PATTERN.matcher(line);
            if (updateMatcher.find()) {
                handleUpdateMessage(currentDatabase, currentTable, updateMatcher.group(1));
                continue;
            }

            Matcher deleteMatcher = DELETE_PATTERN.matcher(line);
            if (deleteMatcher.find()) {
                handleDeleteMessage(currentDatabase, currentTable, deleteMatcher.group(1));
            }
        }
    }

    private void handleInsertMessage(String database, String table, String data) {
        Map<String, Object> afterData = parseColumnData(data);

        ChangeEvent changeEvent = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                database,
                table,
                generateKey(afterData),
                null,
                afterData
        );

        setEventMetadata(changeEvent);
        enqueueBackpressured(changeEvent);
    }

    private void handleUpdateMessage(String database, String table, String data) {
        String[] parts = data.split(" old-key:");
        Map<String, Object> afterData = parseColumnData(parts[0]);
        Map<String, Object> beforeData = parts.length > 1 ? parseColumnData(parts[1]) : new HashMap<>();

        ChangeEvent changeEvent = new ChangeEvent(
                ChangeEvent.EventType.UPDATE,
                database,
                table,
                generateKey(afterData),
                beforeData,
                afterData
        );

        setEventMetadata(changeEvent);
        enqueueBackpressured(changeEvent);
    }

    private void handleDeleteMessage(String database, String table, String data) {
        Map<String, Object> beforeData = parseColumnData(data);

        ChangeEvent changeEvent = new ChangeEvent(
                ChangeEvent.EventType.DELETE,
                database,
                table,
                generateKey(beforeData),
                beforeData,
                null
        );

        setEventMetadata(changeEvent);
        enqueueBackpressured(changeEvent);
    }

    private Map<String, Object> parseColumnData(String data) {
        Map<String, Object> result = new HashMap<>();

        if (data == null || data.trim().isEmpty()) {
            return result;
        }

        // Simple parsing for the test_decoding format: col1[type]:value col2[type]:value.
        // Values may be single-quoted, and a quoted value may contain the very spaces that
        // separate columns — the old naive whitespace split corrupted such rows
        // ("name[text]:'John Doe'" yielded "John" and dropped "Doe'", CDC-M7).
        int i = 0;
        int n = data.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(data.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            boolean inQuote = false;
            while (i < n) {
                char c = data.charAt(i);
                if (inQuote) {
                    if (c == '\'') {
                        inQuote = false;
                    }
                    i++;
                } else if (c == '\'') {
                    inQuote = true;
                    i++;
                } else if (Character.isWhitespace(c)) {
                    break;
                } else {
                    i++;
                }
            }
            String column = data.substring(start, i);
            if (column.contains(":")) {
                String[] parts = column.split(":", 2);
                if (parts.length == 2) {
                    String header = parts[0];
                    String type = null;
                    int lt = header.indexOf('[');
                    int gt = header.lastIndexOf(']');
                    if (lt >= 0 && gt > lt) {
                        type = header.substring(lt + 1, gt).trim().toLowerCase(Locale.ROOT);
                    }
                    String columnName = header.replaceAll("\\[.*?\\]", ""); // Remove type info
                    String value = parts[1];

                    // Handle null values
                    if ("null".equals(value)) {
                        result.put(columnName, null);
                    } else {
                        // Remove quotes if present; '' inside a quoted value is an escaped quote
                        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1).replace("''", "'");
                        }
                        result.put(columnName, coerceByPgType(type, value));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Convert a test_decoding payload value according to its declared PostgreSQL type.
     * Unparseable or unsupported types fall back to the raw String so a weird value
     * never breaks CDC ingestion.
     */
    private static Object coerceByPgType(String type, String value) {
        if (type == null) {
            return value;
        }
        try {
            switch (type) {
                case "integer":
                case "int":
                case "int4":
                case "smallint":
                case "int2":
                    return Integer.valueOf(value);
                case "bigint":
                case "int8":
                    return Long.valueOf(value);
                case "numeric":
                case "decimal":
                case "real":
                case "double precision":
                case "float4":
                case "float8":
                    return Double.valueOf(value);
                case "boolean":
                case "bool":
                    return Boolean.valueOf(value);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private void setEventMetadata(ChangeEvent changeEvent) {
        changeEvent.setSource(getName());
        changeEvent.setPosition(getCurrentPosition());
        changeEvent.setTimestamp(Instant.now());
    }

    private String generateKey(Map<String, Object> data) {
        return data.values().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .reduce((a, b) -> a + "_" + b)
                .orElse("unknown");
    }

    /**
     * Get current LSN position
     */
    public String getCurrentLSN() {
        return lastReceivedLSN != null ? lastReceivedLSN.asString() : null;
    }

    /**
     * Get replication slot name
     */
    public String getSlotName() {
        return slotName;
    }

    /**
     * Get publication name
     */
    public String getPublicationName() {
        return publicationName;
    }

    /**
     * Check if replication stream is active
     */
    public boolean isStreamActive() {
        return replicationStream != null;
    }
}
