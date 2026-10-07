package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
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
    // CDC-H3 knobs: handshake timeout for connect(timeout), and the reconnect backoff window
    // (first retry after `initial`, doubling up to `max`) used after an unexpected disconnect.
    private static final String CONNECT_TIMEOUT_PROPERTY = "connect.timeout.ms";
    private static final String RECONNECT_BACKOFF_INITIAL_PROPERTY = "reconnect.backoff.initial.ms";
    private static final String RECONNECT_BACKOFF_MAX_PROPERTY = "reconnect.backoff.max.ms";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_RECONNECT_BACKOFF_INITIAL_MS = 1_000;
    private static final int DEFAULT_RECONNECT_BACKOFF_MAX_MS = 30_000;

    private BinaryLogClient binaryLogClient;
    // CDC-M1: bounded queue ("event.queue.capacity", default 10_000). The binlog listener
    // thread blocks while it is full (see enqueueBackpressured) so a slow consumer applies
    // backpressure to replication instead of growing the heap; nothing is silently dropped
    // while the connector runs.
    private final java.util.concurrent.BlockingQueue<ChangeEvent> eventQueue;
    private final AtomicLong binlogPosition = new AtomicLong(0);
    // CDC-L7: written by the event thread (handleRotateEvent) and read by reconnect/commit paths
    private volatile String binlogFilename;
    private final Map<Long, TableMapEventData> tableMapEvents = new HashMap<>();
    private final Map<Long, List<String>> tableColumnsById = new HashMap<>();
    private TableFilter tableFilter;
    private MySQLColumnNameResolver columnNameResolver;
    // CDC-H3: the binlog client never reconnects on its own. After an unexpected disconnect
    // the connector flips UNHEALTHY and retries on this executor (doubling backoff) until the
    // stream is back; the flags keep at most one loop alive per connector.
    private volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private volatile long reconnectBackoffInitialMs = DEFAULT_RECONNECT_BACKOFF_INITIAL_MS;
    private volatile long reconnectBackoffMaxMs = DEFAULT_RECONNECT_BACKOFF_MAX_MS;
    private volatile java.util.concurrent.ExecutorService reconnectExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public MySQLBinlogCDCConnector(CDCConfiguration configuration) {
        super(configuration);
        this.eventQueue = new java.util.concurrent.ArrayBlockingQueue<>(
                BackpressureSettings.positiveInt(configuration, BackpressureSettings.QUEUE_CAPACITY_PROPERTY,
                        BackpressureSettings.DEFAULT_QUEUE_CAPACITY));
    }

    /**
     * CDC-M1: blocking, loss-free enqueue while the connector runs. On stop/interrupt the
     * event is NOT dropped silently: the IllegalStateException unwinds {@code
     * handleBinlogEvent} before its trailing position update, so the binlog watermark never
     * advances past an event that was not delivered (restart re-reads it from the last
     * committed position — at-least-once, no gap).
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
        // Validate configuration
        configuration.validate();

        // Parse configuration
        String hostname = (String) configuration.getProperty(HOSTNAME_PROPERTY, "localhost");
        int port = Integer.parseInt(String.valueOf(configuration.getProperty(PORT_PROPERTY, "3306")));
        String username = configuration.getUsername();
        String password = configuration.getPassword();
        long serverId = Long.parseLong(String.valueOf(configuration.getProperty(SERVER_ID_PROPERTY, "1")));

        // Initialize binlog position. On a restart of the same connector instance the live
        // watermark advanced by handleRotateEvent/updateCurrentPosition is preserved —
        // re-reading the configuration here used to rewind to the configured (or server-current)
        // position, losing or duplicating everything consumed since (CDC-H1).
        if (this.binlogFilename == null) {
            this.binlogFilename = (String) configuration.getProperty(BINLOG_FILENAME_PROPERTY);
        }
        String binlogPosStr = (String) configuration.getProperty(BINLOG_POSITION_PROPERTY);
        if (binlogPosStr != null && this.binlogPosition.get() == 0L) {
            this.binlogPosition.set(Long.parseLong(binlogPosStr));
        }

        // Create and configure binary log client
        this.binaryLogClient = new BinaryLogClient(hostname, port, username, password);
        this.binaryLogClient.setServerId(serverId);

        // CDC-H3: disconnect detection + automatic reconnect. mysql-binlog-connector-java does
        // not reconnect on its own and the old code registered no lifecycle listener, so a
        // network cut / MySQL restart / failover left the connector silently stalled forever
        // while health kept reporting HEALTHY.
        this.connectTimeoutMs = BackpressureSettings.positiveInt(configuration, CONNECT_TIMEOUT_PROPERTY,
                DEFAULT_CONNECT_TIMEOUT_MS);
        this.reconnectBackoffInitialMs = BackpressureSettings.positiveInt(configuration,
                RECONNECT_BACKOFF_INITIAL_PROPERTY, DEFAULT_RECONNECT_BACKOFF_INITIAL_MS);
        this.reconnectBackoffMaxMs = Math.max(this.reconnectBackoffInitialMs,
                BackpressureSettings.positiveInt(configuration, RECONNECT_BACKOFF_MAX_PROPERTY,
                        DEFAULT_RECONNECT_BACKOFF_MAX_MS));
        this.binaryLogClient.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient client) {
                handleClientConnected();
            }

            @Override
            public void onCommunicationFailure(BinaryLogClient client, Exception ex) {
                handleClientDisconnected(ex);
            }

            @Override
            public void onDisconnect(BinaryLogClient client) {
                handleClientDisconnected(null);
            }
        });
        this.reconnectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mysql-binlog-reconnect-" + getName());
            t.setDaemon(true);
            return t;
        });

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

        // Start the binary log client. CDC-H3: connect(timeout) returns once the handshake
        // completed and streams events on the library's own thread. The old no-arg connect()
        // blocked this start thread until the stream died, so start() never completed and
        // health never reached HEALTHY (and nothing reconnected afterwards).
        try {
            this.binaryLogClient.connect(connectTimeoutMs);
        } catch (Exception e) {
            // a failed initial connect is a failed start (existing contract) — do not leak
            // the reconnect executor created above
            java.util.concurrent.ExecutorService executor = this.reconnectExecutor;
            this.reconnectExecutor = null;
            reconnecting.set(false);
            if (executor != null) {
                executor.shutdownNow();
            }
            throw e;
        }

        log.info("MySQL binlog CDC connector started: {}:{}, server ID: {}", hostname, port, serverId);
    }

    @Override
    protected void doStop() throws Exception {
        // CDC-H3: running is already false (set by stop() before doStop), so the reconnect
        // loop exits on its next check — this only reclaims the thread promptly.
        java.util.concurrent.ExecutorService executor = this.reconnectExecutor;
        this.reconnectExecutor = null;
        reconnecting.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (binaryLogClient != null && binaryLogClient.isConnected()) {
            binaryLogClient.disconnect();
        }
        if (columnNameResolver != null) {
            try {
                columnNameResolver.close();
            } catch (Exception ignore) {}
            columnNameResolver = null;
        }
        // eventQueue is intentionally NOT cleared: events captured but not yet delivered must
        // survive a stop/start cycle, mirroring the polling connector's restart semantics
        // (CDC-H1; clearing them silently dropped whatever the consumer had not drained yet).
        tableMapEvents.clear();
        tableColumnsById.clear();
    }

    @Override
    protected List<ChangeEvent> doPoll() throws Exception {
        // CDC-H3 watchman: lifecycle callbacks can be missed (e.g. the connection dies before
        // the listener is registered). poll() is the connector's heartbeat — a client that is
        // not connected here goes through the same disconnect path. No-op while a reconnect
        // loop is already running or the connector is stopping.
        if (running.get() && !reconnecting.get()
                && binaryLogClient != null && !binaryLogClient.isConnected()) {
            handleClientDisconnected(null);
        }

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
        String[] parts = parseBinlogPosition(position);
        if (parts != null) {
            this.binlogFilename = parts[0];
            this.binlogPosition.set(Long.parseLong(parts[1]));
        }
    }

    @Override
    protected void doResetToPosition(String position) throws Exception {
        // Parse position first: a malformed position must fail without disturbing a
        // running stream (the old code disconnected before validating)
        String[] parts = parseBinlogPosition(position);
        if (parts != null) {
            this.binlogFilename = parts[0];
            this.binlogPosition.set(Long.parseLong(parts[1]));
        }

        // CDC-L5: reset before start() has no client to reconnect — the recorded fields
        // above are honored by doStart(), instead of crashing with a NullPointerException
        if (binaryLogClient == null) {
            return;
        }
        if (binaryLogClient.isConnected()) {
            binaryLogClient.disconnect();
        }
        if (parts != null) {
            // Reconnect from new position — with the configured timeout, mirroring the start
            // path: the no-arg connect() blocks the caller for the lifetime of the stream
            this.binaryLogClient.setBinlogFilename(binlogFilename);
            this.binaryLogClient.setBinlogPosition(binlogPosition.get());
            this.binaryLogClient.connect(connectTimeoutMs);
        }
    }

    /**
     * CDC-L5: validates the {@code "filename:offset"} form up front. The old
     * {@code split(":") + parts[1]} crashed with a bare ArrayIndexOutOfBoundsException on a
     * trailing colon and silently ignored positions without one; both now surface as a
     * clear IllegalArgumentException, and the offset must be numeric.
     */
    private static String[] parseBinlogPosition(String position) {
        if (position == null || position.isEmpty()) {
            return null;
        }
        String[] parts = position.split(":", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Malformed MySQL binlog position \"" + position + "\", expected \"filename:offset\"");
        }
        try {
            Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Malformed MySQL binlog position \"" + position + "\", expected \"filename:offset\"", e);
        }
        return parts;
    }

    /**
     * CDC-H3: the binlog connection is (re-)established — health goes back to HEALTHY.
     * Invoked from the client's lifecycle {@code onConnect} and after a successful reconnect.
     */
    void handleClientConnected() {
        if (!running.get()) {
            return;
        }
        updateHealthStatus(CDCHealthStatus.healthy("MySQL binlog connection established"));
    }

    /**
     * CDC-H3: the binlog connection dropped unexpectedly. The old code registered no
     * lifecycle listener at all, so a network cut / MySQL restart / failover was undetectable:
     * {@code poll()} kept returning empty lists, health stayed HEALTHY and nothing ever
     * reconnected. This flips health to UNHEALTHY, reports the error and starts the
     * reconnect loop. Package-private seam for the disconnect regression tests.
     *
     * <p>No-op when the connector is stopping — {@code doStop()} disconnects on purpose.
     */
    void handleClientDisconnected(Exception cause) {
        if (!running.get()) {
            return;
        }
        Exception error = cause != null ? cause : new IOException("MySQL binlog connection lost");
        notifyEvent(listener -> listener.onConnectorError(getName(), error));
        updateHealthStatus(CDCHealthStatus.unhealthy(
                "MySQL binlog connection lost (CDC-H3): " + error.getMessage() + "; reconnecting with backoff"));
        scheduleReconnect();
    }

    /** CDC-H3: start the single reconnect loop if none is running. */
    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return; // a reconnect loop is already retrying
        }
        try {
            java.util.concurrent.ExecutorService executor = this.reconnectExecutor;
            if (executor == null || executor.isShutdown()) {
                reconnecting.set(false);
                return;
            }
            executor.submit(this::runReconnectLoop);
        } catch (Exception e) {
            reconnecting.set(false);
            log.warn("Failed to schedule MySQL binlog reconnect for connector {}", getName(), e);
        }
    }

    /**
     * CDC-H3: retry {@code connect(timeout)} with a doubling backoff until the stream is
     * back or the connector stops. Resume from the live watermark so the outage window is
     * replayed instead of skipped.
     */
    private void runReconnectLoop() {
        try {
            long backoff = reconnectBackoffInitialMs;
            while (running.get()) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                BinaryLogClient client = this.binaryLogClient;
                if (!running.get() || client == null) {
                    return;
                }
                if (client.isConnected()) {
                    handleClientConnected();
                    return;
                }
                try {
                    applyResumePosition(client);
                    client.connect(connectTimeoutMs);
                    if (running.get() && client.isConnected()) {
                        handleClientConnected();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("MySQL binlog reconnect attempt failed for connector {}: {}",
                            getName(), e.getMessage());
                }
                backoff = Math.min(backoff * 2, reconnectBackoffMaxMs);
            }
        } finally {
            reconnecting.set(false);
        }
    }

    /**
     * CDC-H3: point the client at the live watermark (kept across stop/start by CDC-H1)
     * before a reconnect attempt. When it is unknown, leave the client's internally tracked
     * binlog coordinates untouched.
     */
    private void applyResumePosition(BinaryLogClient client) {
        if (binlogFilename != null) {
            client.setBinlogFilename(binlogFilename);
            client.setBinlogPosition(binlogPosition.get());
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
                    // CDC-M5: stamp rows with THIS event's own end position (resume point at
                    // which the event is fully processed); the old getCurrentPosition() stamp
                    // handed every ChangeEvent the PREVIOUS event's position, so commit+resume
                    // replayed the just-committed event.
                    // CDC-M1: the end position is computed WITHOUT advancing the global
                    // watermark — that only happens at the trailing updateCurrentPosition once
                    // every row of the event has actually landed in the (bounded) queue. A
                    // stop-time backpressure drop therefore never lets the watermark skip past
                    // an undelivered event (restart re-reads it: at-least-once, no gap).
                    handleWriteRowsEvent((WriteRowsEventData) eventData, endPositionOf(event));
                    break;

                case EXT_UPDATE_ROWS:
                case UPDATE_ROWS:
                    handleUpdateRowsEvent((UpdateRowsEventData) eventData, endPositionOf(event));
                    break;

                case EXT_DELETE_ROWS:
                case DELETE_ROWS:
                    handleDeleteRowsEvent((DeleteRowsEventData) eventData, endPositionOf(event));
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

            // ROTATE advances the watermark via its payload (handleRotateEvent switches to the
            // new file and its payload start offset). Stamping it with this event's header
            // nextPosition instead would pair the NEW filename with an OLD-file end offset —
            // "newfile:oldEndOffset" — and silently skip the head of the new file on resume.
            if (event.getHeader().getEventType() == EventType.ROTATE) {
                String fn = (binlogFilename != null) ? binlogFilename : "";
                this.currentPosition = fn + ":" + binlogPosition.get();
            } else {
                updateCurrentPosition(event);
            }

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

    private void handleWriteRowsEvent(WriteRowsEventData eventData, String endPosition) {
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
            changeEvent.setPosition(endPosition);
            changeEvent.setTimestamp(java.time.Instant.now());

            enqueueBackpressured(changeEvent);
        }
    }

    private void handleUpdateRowsEvent(UpdateRowsEventData eventData, String endPosition) {
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
            changeEvent.setPosition(endPosition);
            changeEvent.setTimestamp(java.time.Instant.now());

            enqueueBackpressured(changeEvent);
        }
    }

    private void handleDeleteRowsEvent(DeleteRowsEventData eventData, String endPosition) {
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
            changeEvent.setPosition(endPosition);
            changeEvent.setTimestamp(java.time.Instant.now());

            enqueueBackpressured(changeEvent);
        }
    }

    private void handleRotateEvent(RotateEventData eventData) {
        this.binlogFilename = eventData.getBinlogFilename();
        this.binlogPosition.set(eventData.getBinlogPosition());
        log.debug("Binlog rotated to: {}:{}", binlogFilename, binlogPosition.get());
    }

    /**
     * The watermark string this event will advance to — computed WITHOUT mutating the
     * watermark itself (CDC-M5 stamp + CDC-M1 stop-safe delivery).
     */
    private String endPositionOf(Event event) {
        EventHeader header = event.getHeader();
        long pos = binlogPosition.get();
        if (header instanceof EventHeaderV4) {
            long next = ((EventHeaderV4) header).getNextPosition();
            if (next > 0) {
                pos = next;
            }
        }
        String fn = (binlogFilename != null) ? binlogFilename : "";
        return fn + ":" + pos;
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
