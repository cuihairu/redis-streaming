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
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private Connection connection;
    private PGReplicationStream replicationStream;
    private final Queue<ChangeEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private String slotName;
    private String publicationName;
    private LogSequenceNumber lastReceivedLSN;
    private long statusIntervalMs;
    private TableFilter tableFilter;

    // Pattern for parsing logical replication messages (test-decoding format)
    private static final Pattern TABLE_PATTERN = Pattern.compile("table\\s+(\\w+)\\.(\\w+):");
    private static final Pattern INSERT_PATTERN = Pattern.compile("INSERT:\\s*(.+)");
    private static final Pattern UPDATE_PATTERN = Pattern.compile("UPDATE:\\s*(.+)");
    private static final Pattern DELETE_PATTERN = Pattern.compile("DELETE:\\s*(.+)");

    public PostgreSQLLogicalReplicationCDCConnector(CDCConfiguration configuration) {
        super(configuration);
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

        Properties props = new Properties();
        PGProperty.USER.set(props, username);
        PGProperty.PASSWORD.set(props, password);
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
        PGProperty.REPLICATION.set(props, "database");
        PGProperty.PREFER_QUERY_MODE.set(props, "simple");

        String url = String.format("jdbc:postgresql://%s:%d/%s", hostname, port, database);
        this.connection = DriverManager.getConnection(url, props);

        createReplicationSlotIfNotExists();
        createPublicationIfNotExists();

        startReplicationStream();

        log.info("PostgreSQL logical replication CDC connector started: {}:{}/{}, slot: {}",
                 hostname, port, database, slotName);
    }

    @Override
    protected void doStop() throws Exception {
        if (replicationStream != null) {
            replicationStream.close();
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
            log.error("Error processing replication messages", e);
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
        }
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
        eventQueue.offer(changeEvent);
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
        eventQueue.offer(changeEvent);
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
        eventQueue.offer(changeEvent);
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
