package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MySQL binlog CDC connector implementation
 */
@Slf4j
public class MySQLBinlogCDCConnector extends AbstractCDCConnector {

    private static final String HOSTNAME_PROPERTY = "hostname";
    private static final String PORT_PROPERTY = "port";
    private static final String SERVER_ID_PROPERTY = "server.id";
    private static final String BINLOG_FILENAME_PROPERTY = "binlog.filename";
    private static final String BINLOG_POSITION_PROPERTY = "binlog.position";

    private BinaryLogClient binaryLogClient;
    private final Queue<ChangeEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong binlogPosition = new AtomicLong(0);
    private String binlogFilename;
    private final Map<Long, TableMapEventData> tableMapEvents = new HashMap<>();
    private final Map<Long, List<String>> tableColumnsById = new HashMap<>();
    private TableFilter tableFilter;
    private MySQLColumnNameResolver columnNameResolver;

    public MySQLBinlogCDCConnector(CDCConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void doStart() throws Exception {
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String hostname = (String) configuration.getProperty(HOSTNAME_PROPERTY, "localhost");
        int port = Integer.parseInt(String.valueOf(configuration.getProperty(PORT_PROPERTY, "3306")));
        String username = configuration.getUsername();
        String password = configuration.getPassword();
        long serverId = Long.parseLong(String.valueOf(configuration.getProperty(SERVER_ID_PROPERTY, "1")));

        // Initialize binlog position
        this.binlogFilename = (String) configuration.getProperty(BINLOG_FILENAME_PROPERTY);
        String binlogPosStr = (String) configuration.getProperty(BINLOG_POSITION_PROPERTY);
        if (binlogPosStr != null) {
            this.binlogPosition.set(Long.parseLong(binlogPosStr));
        }

        // Create and configure binary log client
        this.binaryLogClient = new BinaryLogClient(hostname, port, username, password);
        this.binaryLogClient.setServerId(serverId);

        // Build include/exclude filter (empty includes means allow all).
        this.tableFilter = TableFilter.from(configuration.getTableIncludes(), configuration.getTableExcludes());

        // Best-effort schema resolver (for column names) to make events usable in production.
        this.columnNameResolver = createColumnNameResolver(hostname, port, username, password);

        // Set starting position if specified
        if (binlogFilename != null) {
            this.binaryLogClient.setBinlogFilename(binlogFilename);
            this.binaryLogClient.setBinlogPosition(binlogPosition.get());
        }

        // Register event listener
        this.binaryLogClient.registerEventListener(this::handleBinlogEvent);

        // Start the binary log client
        this.binaryLogClient.connect();

        log.info("MySQL binlog CDC connector started: {}:{}, server ID: {}", hostname, port, serverId);
    }

    @Override
    protected void doStop() throws Exception {
        if (binaryLogClient != null && binaryLogClient.isConnected()) {
            binaryLogClient.disconnect();
        }
        if (columnNameResolver != null) {
            try {
                columnNameResolver.close();
            } catch (Exception ignore) {}
            columnNameResolver = null;
        }
        eventQueue.clear();
        tableMapEvents.clear();
        tableColumnsById.clear();
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

        return events;
    }

    @Override
    protected void doCommit(String position) throws Exception {
        // For MySQL binlog, position is in format "filename:position"
        if (position != null && position.contains(":")) {
            String[] parts = position.split(":");
            this.binlogFilename = parts[0];
            this.binlogPosition.set(Long.parseLong(parts[1]));
        }
    }

    @Override
    protected void doResetToPosition(String position) throws Exception {
        if (binaryLogClient != null && binaryLogClient.isConnected()) {
            binaryLogClient.disconnect();
        }

        // Parse position
        if (position != null && position.contains(":")) {
            String[] parts = position.split(":");
            this.binlogFilename = parts[0];
            this.binlogPosition.set(Long.parseLong(parts[1]));

            // Reconnect from new position
            this.binaryLogClient.setBinlogFilename(binlogFilename);
            this.binaryLogClient.setBinlogPosition(binlogPosition.get());
            this.binaryLogClient.connect();
        }
    }

    private void handleBinlogEvent(Event event) {
        try {
            EventType eventType = event.getHeader().getEventType();
            EventData eventData = event.getData();

            switch (eventType) {
                case TABLE_MAP:
                    handleTableMapEvent((TableMapEventData) eventData);
                    break;

                case EXT_WRITE_ROWS:
                case WRITE_ROWS:
                    handleWriteRowsEvent((WriteRowsEventData) eventData);
                    break;

                case EXT_UPDATE_ROWS:
                case UPDATE_ROWS:
                    handleUpdateRowsEvent((UpdateRowsEventData) eventData);
                    break;

                case EXT_DELETE_ROWS:
                case DELETE_ROWS:
                    handleDeleteRowsEvent((DeleteRowsEventData) eventData);
                    break;

                case ROTATE:
                    handleRotateEvent((RotateEventData) eventData);
                    break;

                case XID:
                    // Transaction commit
                    updateCurrentPosition(event);
                    break;

                default:
                    // Ignore other event types
                    break;
            }

            updateCurrentPosition(event);

        } catch (Exception e) {
            log.error("Error handling binlog event", e);
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
        }
    }

    private void handleTableMapEvent(TableMapEventData eventData) {
        tableMapEvents.put(eventData.getTableId(), eventData);
        if (columnNameResolver == null) {
            return;
        }
        try {
            List<String> columns = columnNameResolver.resolve(eventData.getDatabase(), eventData.getTable());
            if (columns != null && !columns.isEmpty()) {
                tableColumnsById.put(eventData.getTableId(), columns);
            }
        } catch (Exception e) {
            log.debug("Failed to resolve MySQL column names for {}.{}", eventData.getDatabase(), eventData.getTable(), e);
        }
    }

    private void handleWriteRowsEvent(WriteRowsEventData eventData) {
        TableMapEventData tableMapEvent = tableMapEvents.get(eventData.getTableId());
        if (tableMapEvent == null) {
            return;
        }

        String database = tableMapEvent.getDatabase();
        String table = tableMapEvent.getTable();
        if (tableFilter != null && !tableFilter.allowed(database, table)) {
            return;
        }

        List<String> columns = getOrResolveColumns(eventData.getTableId(), database, table);
        for (Serializable[] row : eventData.getRows()) {
            Map<String, Object> afterData = convertRowToMap(row, columns);

            ChangeEvent changeEvent = new ChangeEvent(
                    ChangeEvent.EventType.INSERT,
                    database,
                    table,
                    generateKey(afterData),
                    null,
                    afterData
            );

            changeEvent.setSource(getName());
            changeEvent.setPosition(getCurrentPosition());
            changeEvent.setTimestamp(java.time.Instant.now());

            eventQueue.offer(changeEvent);
        }
    }

    private void handleUpdateRowsEvent(UpdateRowsEventData eventData) {
        TableMapEventData tableMapEvent = tableMapEvents.get(eventData.getTableId());
        if (tableMapEvent == null) {
            return;
        }

        String database = tableMapEvent.getDatabase();
        String table = tableMapEvent.getTable();
        if (tableFilter != null && !tableFilter.allowed(database, table)) {
            return;
        }

        for (Map.Entry<Serializable[], Serializable[]> row : eventData.getRows()) {
            List<String> columns = getOrResolveColumns(eventData.getTableId(), database, table);
            Map<String, Object> beforeData = convertRowToMap(row.getKey(), columns);
            Map<String, Object> afterData = convertRowToMap(row.getValue(), columns);

            ChangeEvent changeEvent = new ChangeEvent(
                    ChangeEvent.EventType.UPDATE,
                    database,
                    table,
                    generateKey(afterData),
                    beforeData,
                    afterData
            );

            changeEvent.setSource(getName());
            changeEvent.setPosition(getCurrentPosition());
            changeEvent.setTimestamp(java.time.Instant.now());

            eventQueue.offer(changeEvent);
        }
    }

    private void handleDeleteRowsEvent(DeleteRowsEventData eventData) {
        TableMapEventData tableMapEvent = tableMapEvents.get(eventData.getTableId());
        if (tableMapEvent == null) {
            return;
        }

        String database = tableMapEvent.getDatabase();
        String table = tableMapEvent.getTable();
        if (tableFilter != null && !tableFilter.allowed(database, table)) {
            return;
        }

        List<String> columns = getOrResolveColumns(eventData.getTableId(), database, table);
        for (Serializable[] row : eventData.getRows()) {
            Map<String, Object> beforeData = convertRowToMap(row, columns);

            ChangeEvent changeEvent = new ChangeEvent(
                    ChangeEvent.EventType.DELETE,
                    database,
                    table,
                    generateKey(beforeData),
                    beforeData,
                    null
            );

            changeEvent.setSource(getName());
            changeEvent.setPosition(getCurrentPosition());
            changeEvent.setTimestamp(java.time.Instant.now());

            eventQueue.offer(changeEvent);
        }
    }

    private void handleRotateEvent(RotateEventData eventData) {
        this.binlogFilename = eventData.getBinlogFilename();
        this.binlogPosition.set(eventData.getBinlogPosition());
        log.debug("Binlog rotated to: {}:{}", binlogFilename, binlogPosition.get());
    }

    private void updateCurrentPosition(Event event) {
        // Prefer nextPosition from header (v4) so position advances on every row event, not only on ROTATE.
        EventHeader header = event.getHeader();
        if (header instanceof EventHeaderV4) {
            long next = ((EventHeaderV4) header).getNextPosition();
            if (next > 0) {
                binlogPosition.set(next);
            }
        }
        String fn = (binlogFilename != null) ? binlogFilename : "";
        this.currentPosition = fn + ":" + binlogPosition.get();
    }

    private Map<String, Object> convertRowToMap(Serializable[] row, List<String> columns) {
        Map<String, Object> data = new HashMap<>();
        List<String> names = columns != null ? columns : List.of();
        for (int i = 0; i < row.length; i++) {
            if (i < names.size()) {
                data.put(names.get(i), row[i]);
            } else {
                data.put("col_" + i, row[i]);
            }
        }
        return data;
    }

    private String generateKey(Map<String, Object> data) {
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
     * Get current binlog position
     */
    public String getBinlogFilename() {
        return binlogFilename;
    }

    /**
     * Get current binlog position
     */
    public long getBinlogPosition() {
        return binlogPosition.get();
    }

    /**
     * Check if binlog client is connected
     */
    public boolean isConnected() {
        return binaryLogClient != null && binaryLogClient.isConnected();
    }

    private List<String> getOrResolveColumns(long tableId, String database, String table) {
        List<String> cached = tableColumnsById.get(tableId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        if (columnNameResolver == null) {
            return List.of();
        }
        try {
            List<String> resolved = columnNameResolver.resolve(database, table);
            if (resolved != null && !resolved.isEmpty()) {
                tableColumnsById.put(tableId, resolved);
                return resolved;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve MySQL column names for {}.{}", database, table, e);
        }
        return List.of();
    }

    private MySQLColumnNameResolver createColumnNameResolver(String hostname, int port, String username, String password) {
        try {
            Object enabledProp = configuration.getProperty("schema.resolve.columns", "true");
            boolean enabled = Boolean.parseBoolean(String.valueOf(enabledProp));
            if (!enabled) {
                return null;
            }

            String url = (String) configuration.getProperty("schema.jdbc.url");
            if (url == null || url.isBlank()) {
                url = String.format("jdbc:mysql://%s:%d/information_schema", hostname, port);
            }

            int timeoutSeconds = Integer.parseInt(String.valueOf(configuration.getProperty("schema.query.timeout.seconds", "5")));
            return new DriverManagerMySQLColumnNameResolver(url, username, password, timeoutSeconds);
        } catch (Exception e) {
            log.warn("MySQL schema resolver is disabled (failed to initialize): {}", e.getMessage());
            return null;
        }
    }
}
