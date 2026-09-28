package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.CDCHealthStatus;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link MySQLBinlogCDCConnector}: lifecycle-listener wiring,
 * initial-connect failure cleanup, position/offset handling, bounded backpressured queue and
 * reconnect-loop branches — all without any external MySQL.
 */
@Timeout(30)
class MySQLBinlogCDCConnectorResidualCoverageTest {

    // ------------------------------------------------------------------ start failure paths

    @Test
    void initialConnectFailureCleansUpExecutorAndFlipsHealthUnhealthy() {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-fail")
                        .mysqlHostname("127.0.0.1")
                        .mysqlPort(1) // nothing listens on port 1: connect() fails fast
                        .property("connect.timeout.ms", 500)
                        .build());
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> connector.start().get(15, TimeUnit.SECONDS));
        assertFalse(connector.isRunning());
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
        assertEquals(1, errors.size());
        // the reconnect executor must not leak on a failed initial connect
        assertNull(field(connector, "reconnectExecutor"));
        assertFalse(((AtomicBoolean) field(connector, "reconnecting")).get());
    }

    @Test
    void brokenSchemaResolverSettingsDisableResolverWithoutBreakingStart() {
        // schema.query.timeout.seconds is not a number -> createColumnNameResolver catches and
        // falls back to null (schema resolution is best-effort by design)
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-schema")
                        .mysqlHostname("127.0.0.1")
                        .mysqlPort(1)
                        .property("connect.timeout.ms", 500)
                        .property("schema.query.timeout.seconds", "not-a-number")
                        .build());

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> connector.start().get(15, TimeUnit.SECONDS));
        assertNull(field(connector, "columnNameResolver"),
                "an unusable schema-resolver config must degrade to no resolver");
    }

    @Test
    void schemaResolveDisabledLeavesNoResolver() {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-nores")
                        .mysqlHostname("127.0.0.1")
                        .mysqlPort(1)
                        .property("connect.timeout.ms", 500)
                        .property("schema.resolve.columns", false)
                        .build());

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> connector.start().get(15, TimeUnit.SECONDS));
        assertNull(field(connector, "columnNameResolver"));
    }

    // ------------------------------------------------------------------ lifecycle listener wiring

    @Test
    void lifecycleListenerConnectFlipsHealthHealthy() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(true);
        MySQLBinlogCDCConnector connector = injected(connector("mysql-lc-on"), client);

        connector.handleClientConnected();

        assertEquals(CDCHealthStatus.Status.HEALTHY, connector.getHealthStatus().getStatus());
        assertTrue(connector.getHealthStatus().getMessage().contains("connection established"));
        connector.running.set(false);
    }

    @Test
    void lifecycleListenerConnectIsNoopWhenStopping() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-lc-stopped").build());
        setField(connector, "binaryLogClient", mock(BinaryLogClient.class));

        connector.handleClientConnected(); // running == false: must not flip health
        assertEquals(CDCHealthStatus.Status.UNKNOWN, connector.getHealthStatus().getStatus());
    }

    @Test
    void lifecycleListenerCommunicationFailureAndDisconnectFlipUnhealthyAndNotify() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        List<String> errors = new CopyOnWriteArrayList<>();
        MySQLBinlogCDCConnector connector = injected(connector("mysql-lc-comm"), client);
        connector.setEventListener(recordingListener(errors));

        connector.handleClientDisconnected(new IOException("socket broken"));
        assertTrue(connector.getHealthStatus().getMessage().contains("socket broken"),
                "the original failure must surface in health (onCommunicationFailure path)");
        assertTrue(errors.get(0).contains("socket broken"));
        stopAndAwaitReconnectIdle(connector);

        // after stop the executor is gone: scheduling is refused softly (reconnecting cleared)
        connector.handleClientDisconnected(null);
        assertTrue(connector.getHealthStatus().getMessage().contains("connection lost (CDC-H3)"),
                "a synthetic IOException is used when no cause is given (onDisconnect path)");
        assertFalse(((AtomicBoolean) field(connector, "reconnecting")).get());
    }

    @Test
    void doStartRegistersTheLifecycleListenerThatForwardsToTheSeams() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-listener-wiring")
                        .mysqlHostname("127.0.0.1")
                        .mysqlPort(1)
                        .property("connect.timeout.ms", 500)
                        .build());
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> connector.start().get(15, TimeUnit.SECONDS));

        // the failed start still registered the CDC-H3 lifecycle listener on the real client
        BinaryLogClient client = (BinaryLogClient) field(connector, "binaryLogClient");
        List<BinaryLogClient.LifecycleListener> listeners = client.getLifecycleListeners();
        assertEquals(1, listeners.size());
        BinaryLogClient.LifecycleListener listener = listeners.get(0);

        connector.running.set(true);
        setField(connector, "reconnectExecutor", java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t2 = new Thread(r, "test-mysql-reconnect");
            t2.setDaemon(true);
            return t2;
        }));
        connector.healthStatus = CDCHealthStatus.unknown("fresh");

        listener.onConnect(client);
        assertEquals(CDCHealthStatus.Status.HEALTHY, connector.getHealthStatus().getStatus(),
                "onConnect must reach handleClientConnected");

        listener.onCommunicationFailure(client, new IOException("dump thread died"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("dump thread died")),
                "onCommunicationFailure must reach handleClientDisconnected with the cause");
        connector.running.set(false);

        listener.onDisconnect(client);
        assertTrue(errors.size() >= 2, "onDisconnect must reach handleClientDisconnected");
    }

    @Test
    void doStartAppliesConfiguredBinlogCoordinatesBeforeConnecting() throws Exception {
        // a TCP peer that accepts but never speaks the MySQL handshake: connect(timeout)
        // times out and the start fails, but the configured coordinates were already applied
        java.net.ServerSocket stalling = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!stalling.isClosed()) {
                try {
                    stalling.accept().close(); // accept then stall: no handshake bytes ever
                } catch (Exception e) {
                    return;
                }
            }
        }, "test-stalling-mysql");
        acceptor.setDaemon(true);
        acceptor.start();

        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-coords")
                        .mysqlHostname("127.0.0.1")
                        .mysqlPort(stalling.getLocalPort())
                        .property("connect.timeout.ms", 400)
                        .property("binlog.filename", "mysql-bin.000004")
                        .property("binlog.position", "8200")
                        .build());

        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> connector.start().get(15, TimeUnit.SECONDS));
            assertEquals("mysql-bin.000004", connector.getBinlogFilename(),
                    "the configured binlog file must be applied before connecting");
            assertEquals(8200L, connector.getBinlogPosition(),
                    "the configured binlog position must be applied before connecting");
        } finally {
            connector.running.set(false);
            stalling.close();
        }
    }

    // ------------------------------------------------------------------ position / offset behavior

    @Test
    void resetToPositionRestartsClientFromNewCoordinates() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(true);
        MySQLBinlogCDCConnector connector = injected(connector("mysql-reset"), client);

        connector.doResetToPosition("mysql-bin.000009:4096");

        verify(client).disconnect();
        verify(client).setBinlogFilename("mysql-bin.000009");
        verify(client).setBinlogPosition(4096L);
        verify(client).connect();
        assertEquals("mysql-bin.000009", connector.getBinlogFilename());
        assertEquals(4096L, connector.getBinlogPosition());
    }

    @Test
    void xidEventAdvancesWatermark() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-xid").build());
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");

        invokeHandle(connector, event(EventType.XID, 980, null));

        assertEquals("mysql-bin.000001:980", connector.getCurrentPosition());
    }

    @Test
    void interruptedBackpressuredEnqueueThrowsAndDoesNotAdvanceWatermark() throws Exception {
        // capacity-1 queue: poll() drains before the next handle, so the delivery thread's
        // row-dump carries TWO rows — the first refills the queue, the second blocks in the
        // timed offer() until the delivery thread is interrupted (at-least-once: the
        // watermark must not advance past an un-delivered event, CDC-M1)
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-bp")
                        .property("event.queue.capacity", 1)
                        .build());
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");
        setField(connector, "columnNameResolver", resolver("id"));
        long tableId = 7L;
        tableMap(connector, tableId, "t", 1);
        invokeHandle(connector, writeEvent(tableId, new Serializable[]{1}, 2)); // fills the queue
        assertEquals(1, connector.poll().size());
        long positionAfterFirstDelivery = connector.getBinlogPosition();

        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        Thread delivering = new Thread(() -> invokeHandle(connector, writeRows(tableId,
                List.of(new Serializable[]{2}, new Serializable[]{3}), 999)));
        delivering.start();
        awaitBlocked(delivering); // condition wait: park inside offer(), not a fixed sleep
        delivering.interrupt(); // interrupts the 50ms offer slices
        delivering.join(30_000); // generous bound: the thread must still get scheduled under load
        assertFalse(delivering.isAlive());

        // Poll for the error to surface — the InterruptedException path may take a few ms to
        // propagate through the thread's exception handler and the listener callback, so we wait
        // rather than asserting on a single instantaneous check.
        boolean errorSeen = false;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (errors.stream().anyMatch(e -> e.contains("not enqueued"))) {
                errorSeen = true;
                break;
            }
            Thread.sleep(10);
        }
        assertTrue(errorSeen, "the undelivered event must surface as an error: " + errors);
        assertEquals(positionAfterFirstDelivery, connector.getBinlogPosition(),
                "the watermark must NOT advance past an interrupted (un-delivered) event (CDC-M1)");
    }

    // ------------------------------------------------------------------ event handling branches

    @Test
    void rowsWithoutTableMapEntryAreSkippedSilently() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-notm").build());
        connector.running.set(true);
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(true); // otherwise the doPoll watchman fires
        setField(connector, "binaryLogClient", client);
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        // tableId 99 has no TABLE_MAP for this connector: write/update/delete all skip
        invokeHandle(connector, writeEvent(99, new Serializable[]{1}, 2));
        invokeHandle(connector, updateEvent(99, 3));
        invokeHandle(connector, deleteEvent(99, 4));

        assertTrue(connector.poll().isEmpty());
        assertTrue(errors.isEmpty(), "missing table maps are normal (e.g. filtered rows), not errors");
    }

    @Test
    void filterExcludedTablesSkipUpdateAndDelete() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("mysql-filt")
                        .property("table.excludes", List.of("excluded"))
                        .build());
        connector.running.set(true);
        // the tests bypass doStart(), so seed the filter it would have parsed
        setField(connector, "tableFilter", TableFilter.from(List.of(), List.of("excluded")));
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(true); // otherwise the doPoll watchman fires
        setField(connector, "binaryLogClient", client);
        setField(connector, "columnNameResolver", resolver("id"));
        long tableId = 11L;
        tableMap(connector, tableId, "excluded", 1);
        invokeHandle(connector, updateEvent(tableId, 2));
        invokeHandle(connector, deleteEvent(tableId, 3));

        assertTrue(connector.poll().isEmpty(), "excluded tables must not emit UPDATE/DELETE events");
    }

    @Test
    void generateKeyPrefersPkWhenIdAbsent() throws Exception {
        MySQLBinlogCDCConnector connector = connector("mysql-pk");
        connector.running.set(true);
        setField(connector, "binaryLogClient", mock(BinaryLogClient.class));
        setField(connector, "columnNameResolver", resolver("pk", "v"));
        long tableId = 12L;
        tableMap(connector, tableId, "t", 1);

        invokeHandle(connector, writeEvent(tableId, new Serializable[]{"k1", "vv"}, 2));
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        assertEquals("k1", events.get(0).getKey());
    }

    @Test
    void resolverFailureFallsBackToPositionalThenCachesOnRetry() throws Exception {
        MySQLBinlogCDCConnector connector = connector("mysql-resolver");
        connector.running.set(true);
        setField(connector, "binaryLogClient", mock(BinaryLogClient.class));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        setField(connector, "columnNameResolver", new MySQLColumnNameResolver() {
            @Override
            public List<String> resolve(String database, String table) {
                if (calls.incrementAndGet() < 3) {
                    throw new IllegalStateException("information_schema hiccup");
                }
                return List.of("id", "name");
            }

            @Override
            public void close() {}
        });
        long tableId = 13L;
        tableMap(connector, tableId, "t", 1); // resolve #1 throws: not cached, not an error

        invokeHandle(connector, writeEvent(tableId, new Serializable[]{1, "a"}, 2)); // resolve #2 throws
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        assertTrue(events.get(0).getAfterData().keySet().contains("col_0"),
                "a failing resolver must degrade to positional column names: " + events.get(0).getAfterData());

        invokeHandle(connector, writeEvent(tableId, new Serializable[]{2, "b"}, 3)); // resolve #3 succeeds
        events = connector.poll();
        assertEquals(2, events.get(0).getAfterData().get("id"));
        assertEquals("b", events.get(0).getAfterData().get("name"));
    }

    // ------------------------------------------------------------------ reconnect loop branches

    @Test
    void reconnectLoopSeesClientBackOnlineAndCallsItConnected() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        AtomicBoolean online = new AtomicBoolean(false);
        when(client.isConnected()).thenAnswer(inv -> online.get());
        doThrow(new IOException("still down")).when(client).connect(anyLong());
        MySQLBinlogCDCConnector connector = injected(connector("mysql-rlc"), client);

        connector.handleClientDisconnected(new IOException("cut"));
        await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.UNHEALTHY);

        // the binlog library re-established the connection on its own (not via the reconnect
        // loop): the loop must notice isConnected() and declare health HEALTHY
        online.set(true);
        await(() -> connector.getHealthStatus().getMessage().contains("connection established"));
        await(() -> !((AtomicBoolean) field(connector, "reconnecting")).get());
        connector.running.set(false);
        connector.stop().get(10, TimeUnit.SECONDS);
    }

    @Test
    void reconnectLoopStopsCleanlyOnConnectorStop() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(false);
        doThrow(new IOException("still down")).when(client).connect(anyLong());
        MySQLBinlogCDCConnector connector = injected(connector("mysql-rl-stop"), client);

        connector.handleClientDisconnected(new IOException("cut"));
        await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.UNHEALTHY);

        connector.stop().get(10, TimeUnit.SECONDS); // must not hang: interrupts the backoff sleep
        assertFalse(((AtomicBoolean) field(connector, "reconnecting")).get(),
                "the reconnect loop must release its in-flight flag on stop");
        assertFalse(connector.isRunning());
    }

    @Test
    void scheduleReconnectNoopWhenExecutorAlreadyShutDown() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(false);
        MySQLBinlogCDCConnector connector = injected(connector("mysql-rl-dead"), client);
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown(); // executor torn down (e.g. by a failed start): no reconnect loop may run
        setField(connector, "reconnectExecutor", dead);

        connector.handleClientDisconnected(new IOException("cut"));

        assertTrue(connector.getHealthStatus().getMessage().contains("connection lost (CDC-H3)"));
        assertFalse(((AtomicBoolean) field(connector, "reconnecting")).get(),
                "a dead executor must not leave the reconnect flag stuck");
    }

    @Test
    void doStopClosesResolverAndClient() throws Exception {
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(true);
        MySQLColumnNameResolver resolver = mock(MySQLColumnNameResolver.class);
        MySQLBinlogCDCConnector connector = injected(connector("mysql-stop"), client);
        setField(connector, "columnNameResolver", resolver);

        connector.stop().get(10, TimeUnit.SECONDS);

        verify(client).disconnect();
        verify(resolver).close();
        assertEquals(CDCHealthStatus.Status.UNKNOWN, connector.getHealthStatus().getStatus(),
                "a deliberate stop must not leave health UNHEALTHY");
    }

    // ------------------------------------------------------------------ helpers

    private static MySQLBinlogCDCConnector connector(String name) {
        return new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog(name)
                        .property("reconnect.backoff.initial.ms", 1)
                        .property("reconnect.backoff.max.ms", 5)
                        .build());
    }

    /** Sets running=true, a fresh reconnect executor and a mock client (bypasses doStart). */
    private static MySQLBinlogCDCConnector injected(MySQLBinlogCDCConnector connector,
                                                    BinaryLogClient client) throws Exception {
        setField(connector, "binaryLogClient", client);
        setField(connector, "reconnectBackoffInitialMs", 1L);
        setField(connector, "reconnectBackoffMaxMs", 5L);
        setField(connector, "reconnectExecutor", Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-mysql-reconnect");
            t.setDaemon(true);
            return t;
        }));
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        return connector;
    }

    /** Stops the connector and waits for its reconnect loop to release the in-flight flag. */
    private static void stopAndAwaitReconnectIdle(MySQLBinlogCDCConnector connector) throws Exception {
        connector.running.set(false);
        connector.stop().get(10, TimeUnit.SECONDS);
        await(() -> !((AtomicBoolean) field(connector, "reconnecting")).get());
    }

    private static MySQLColumnNameResolver resolver(String... columns) {
        return new MySQLColumnNameResolver() {
            @Override
            public List<String> resolve(String database, String table) {
                return List.of(columns);
            }

            @Override
            public void close() {}
        };
    }

    private static void tableMap(MySQLBinlogCDCConnector connector, long tableId,
                                 String table, long nextPos) {
        TableMapEventData tm = mock(TableMapEventData.class);
        when(tm.getTableId()).thenReturn(tableId);
        when(tm.getDatabase()).thenReturn("db");
        when(tm.getTable()).thenReturn(table);
        invokeHandle(connector, event(EventType.TABLE_MAP, nextPos, tm));
    }

    private static Event writeEvent(long tableId, Serializable[] row, long nextPos) {
        return writeRows(tableId, List.<Serializable[]>of(row), nextPos);
    }

    private static Event writeRows(long tableId, List<Serializable[]> rows, long nextPos) {
        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(rows);
        return event(EventType.WRITE_ROWS, nextPos, write);
    }

    /** Await the thread parking inside the timed offer() (bounded, no fixed sleep). */
    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (thread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("thread did not block within 30s, state=" + thread.getState());
    }

    private static Event updateEvent(long tableId, long nextPos) {
        UpdateRowsEventData update = mock(UpdateRowsEventData.class);
        when(update.getTableId()).thenReturn(tableId);
        when(update.getRows()).thenReturn(List.of(
                java.util.Map.entry(new Serializable[]{1, "a"}, new Serializable[]{1, "a2"})));
        return event(EventType.UPDATE_ROWS, nextPos, update);
    }

    private static Event deleteEvent(long tableId, long nextPos) {
        DeleteRowsEventData delete = mock(DeleteRowsEventData.class);
        when(delete.getTableId()).thenReturn(tableId);
        when(delete.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{1}));
        return event(EventType.DELETE_ROWS, nextPos, delete);
    }

    private static Event event(EventType type, long nextPos, Object data) {
        EventHeaderV4 header = mock(EventHeaderV4.class);
        when(header.getEventType()).thenReturn(type);
        when(header.getNextPosition()).thenReturn(nextPos);
        Event event = mock(Event.class);
        when(event.getHeader()).thenReturn(header);
        when(event.getData()).thenReturn(data);
        return event;
    }

    private static void invokeHandle(MySQLBinlogCDCConnector connector, Event event) {
        try {
            java.lang.reflect.Method m = MySQLBinlogCDCConnector.class
                    .getDeclaredMethod("handleBinlogEvent", Event.class);
            m.setAccessible(true);
            m.invoke(connector, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CDCEventListener recordingListener(List<String> errors) {
        return new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error));
            }
        };
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = declaredField(target.getClass(), name);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = declaredField(target.getClass(), name);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }
}