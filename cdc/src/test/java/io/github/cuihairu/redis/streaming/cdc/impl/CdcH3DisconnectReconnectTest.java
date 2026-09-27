package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.CDCHealthStatus;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.replication.PGReplicationConnection;
import org.postgresql.replication.fluent.ChainedStreamBuilder;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CDC-H3 regression: both push/pull connectors must DETECT a lost upstream connection and
 * recover (or halt loudly when recovery is impossible) instead of silently stalling forever.
 *
 * <ul>
 *   <li>MySQL: no lifecycle listener + a blocking no-arg {@code connect()} meant a network
 *       cut / MySQL restart was undetectable — {@code poll()} returned empty lists forever
 *       and health kept reporting HEALTHY. Now the disconnect flips health to UNHEALTHY and
 *       a backoff reconnect loop resumes from the live watermark.</li>
 *   <li>PostgreSQL: {@code processReplicationMessages} caught the SQLException and kept
 *       polling the dead stream — health stayed HEALTHY, LSN feedback stopped and the server
 *       retained WAL in the slot without bound. Now the dead stream is torn down, health
 *       flips, and {@code poll()} rebuilds the stream (an invalidated/dropped slot halts
 *       loudly instead of silently skipping the discarded WAL).</li>
 * </ul>
 *
 * <p>All new seams are reached reflectively (or through behavior) so the suite runs — and
 * fails precisely — against the pre-fix code instead of breaking its compile.
 */
class CdcH3DisconnectReconnectTest {

    // ------------------------------------------------------------------ MySQL

    @Test
    void mySqlDisconnectFlipsHealthNotifiesAndReconnectsFromWatermark() throws Exception {
        MySQLBinlogCDCConnector connector = mySqlConnector("mysql-h3-reconnect");
        BinaryLogClient client = mock(BinaryLogClient.class);
        AtomicBoolean online = new AtomicBoolean(false);
        when(client.isConnected()).thenAnswer(inv -> online.get());
        doAnswer(inv -> {
            online.set(true);
            return null;
        }).when(client).connect(anyLong());
        injectMySqlClient(connector, client);
        setField(connector, "binlogFilename", "mysql-bin.000004");
        ((AtomicLong) getField(connector, "binlogPosition")).set(8200L);

        List<String> errors = new CopyOnWriteArrayList<>();
        List<String> healthFlips = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error));
            }

            @Override
            public void onHealthStatusChanged(String connectorName, CDCHealthStatus oldStatus,
                                              CDCHealthStatus newStatus) {
                healthFlips.add(newStatus.getStatus() + ": " + newStatus.getMessage());
            }
        });

        // old code: no handleClientDisconnected seam existed — the failure must be loud
        invoke(connector, "handleClientDisconnected", new Class<?>[]{Exception.class},
                new IOException("connection reset by peer"));

        assertTrue(healthFlips.stream().anyMatch(f ->
                        f.startsWith(CDCHealthStatus.Status.UNHEALTHY.name()) && f.contains("CDC-H3")),
                "the disconnect must flip health to UNHEALTHY citing the reconnect contract — flips: "
                        + healthFlips);
        assertEquals(1, errors.size(), "the listener must be told about the disconnect");

        // the reconnect loop must come back online from the live watermark (CDC-H1 keeps it)
        assertTrue(await(() -> online.get()
                        && connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                5_000),
                "the connector must reconnect and flip health back to HEALTHY");
        verify(client).setBinlogFilename("mysql-bin.000004");
        verify(client).setBinlogPosition(8200L);
    }

    @Test
    void mySqlPollWatchmanDetectsDeadClientAndRetriesUntilBack() throws Exception {
        MySQLBinlogCDCConnector connector = mySqlConnector("mysql-h3-watchman");
        BinaryLogClient client = mock(BinaryLogClient.class);
        AtomicBoolean online = new AtomicBoolean(false);
        AtomicInteger failedAttempts = new AtomicInteger();
        when(client.isConnected()).thenAnswer(inv -> online.get());
        doAnswer(inv -> {
            if (failedAttempts.incrementAndGet() <= 3) {
                throw new IOException("connect refused");
            }
            online.set(true);
            return null;
        }).when(client).connect(anyLong());
        injectMySqlClient(connector, client);

        // old code: poll() returned empty forever and health stayed HEALTHY — the exact
        // silent-stall symptom of CDC-H3
        List<ChangeEvent> events = connector.poll();
        assertTrue(events.isEmpty(), "no events are buffered while the stream is down");
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus(),
                "poll() is the connector heartbeat — a dead client must flip health to UNHEALTHY");

        assertTrue(await(() -> online.get()
                        && connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                5_000),
                "the reconnect loop must keep retrying and recover once MySQL answers again");
        assertTrue(failedAttempts.get() >= 4, "several backoff attempts must have been made");

        // stopping while the loop runs (or after recovery) must not hang or throw
        assertDoesNotThrow(() -> connector.stop().get(10, TimeUnit.SECONDS));
    }

    @Test
    void mySqlIntentionalStopDoesNotFlipHealthOrReconnect() throws Exception {
        MySQLBinlogCDCConnector connector = mySqlConnector("mysql-h3-stop");
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.isConnected()).thenReturn(false);
        injectMySqlClient(connector, client);
        connector.running.set(false);
        connector.healthStatus = CDCHealthStatus.unknown("Connector stopped");

        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        // doStop() disconnects on purpose — that must not look like an outage
        invoke(connector, "handleClientDisconnected", new Class<?>[]{Exception.class}, (Exception) null);

        assertEquals(CDCHealthStatus.Status.UNKNOWN, connector.getHealthStatus().getStatus(),
                "an intentional stop must not flip health to UNHEALTHY");
        assertTrue(errors.isEmpty(), "an intentional stop must not raise connector errors");
        assertFalse(((AtomicBoolean) getField(connector, "reconnecting")).get(),
                "no reconnect loop may be scheduled after an intentional stop");
    }

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    void pgStreamFailureFlipsHealthAndTearsDownDeadStream() throws Exception {
        PostgreSQLLogicalReplicationCDCConnector connector = pgConnector("pg-h3-failure");
        PGReplicationStream stream = mock(PGReplicationStream.class);
        when(stream.readPending()).thenThrow(new SQLException("server closed the connection unexpectedly"));
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        setField(connector, "replicationStream", stream);
        setField(connector, "connection", connection);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        // old code: swallowed the SQLException and kept polling the dead stream forever
        List<ChangeEvent> events = connector.poll();

        assertTrue(events.isEmpty());
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus(),
                "a dead replication stream must be detectable through health");
        assertNull(getField(connector, "replicationStream"),
                "the dead stream must be torn down so poll() can rebuild it");
        assertEquals(1, errors.size(), "the listener must be told about the stream failure");
        verify(stream).close();
        verify(connection).close();
        assertNull(getField(connector, "connection"),
                "the broken connection must be released immediately (no isValid() probing — it can"
                        + " block for tens of seconds on a terminated replication connection)");
        assertDoesNotThrow(() -> connector.stop().get(10, TimeUnit.SECONDS),
                "stop must stay clean after a stream failure");
    }

    @Test
    void pgReconnectsOnNextPollResumingAtLastReceivedLsn() throws Exception {
        PGReplicationStream deadStream = mock(PGReplicationStream.class);
        when(deadStream.readPending()).thenThrow(new SQLException("connection reset"));
        // the rebuilt stream is healthy: readPending yields nothing pending
        PGReplicationStream rebuiltStream = mock(PGReplicationStream.class);
        when(rebuiltStream.readPending()).thenReturn(null);
        Connection connection = failingThenReconnectableConnection(rebuiltStream);
        PostgreSQLLogicalReplicationCDCConnector connector = pgConnector("pg-h3-resume", connection);
        setField(connector, "replicationStream", deadStream);
        setField(connector, "connection", connection);
        setField(connector, "lastReceivedLSN", org.postgresql.replication.LogSequenceNumber.valueOf("0/16B45D0"));
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        connector.setEventListener(recordingListener(new CopyOnWriteArrayList<>()));

        connector.poll(); // fails the stream, tears it down, flips health
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());

        // the reconnect happens inside poll() (pull model) — keep polling while waiting
        assertTrue(await(() -> {
            try {
                connector.poll();
            } catch (Exception ignored) {
                // poll() swallows its own errors; nothing here should blow up the wait
            }
            return connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY;
        }, 5_000),
                "the next poll must rebuild the stream and flip health back to HEALTHY — was: "
                        + connector.getHealthStatus().getStatus() + ": " + connector.getHealthStatus().getMessage());
        assertSame(rebuiltStream, getField(connector, "replicationStream"),
                "the rebuilt stream must be the active one");
        assertTrue(connector.isStreamActive());
        verify(deadStream).close();
    }

    @Test
    void pgInvalidatedSlotMessageHaltsReconnectLoudly() throws Exception {
        PostgreSQLLogicalReplicationCDCConnector connector = pgConnector("pg-h3-invalidated");
        PGReplicationStream stream = mock(PGReplicationStream.class);
        when(stream.readPending()).thenThrow(new SQLException(
                "FATAL: replication slot \"cdc_slot\" was invalidated by wal_removal"));
        setField(connector, "replicationStream", stream);
        setField(connector, "connection", null);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        connector.setEventListener(recordingListener(new CopyOnWriteArrayList<>()));

        connector.poll();
        assertTrue((boolean) getField(connector, "slotInvalidated"),
                "an invalidated-slot error must be recognized");
        assertTrue(connector.getHealthStatus().getMessage().contains("invalidated"),
                "health must name the invalidation");

        // no further polls may attempt to rebuild a slot whose WAL is gone — that would
        // silently skip the discarded changes
        connector.poll();
        connector.poll();
        assertNull(getField(connector, "replicationStream"),
                "an invalidated slot must not be silently rebuilt mid-stream");
        assertEquals(0L, (long) (Long) getField(connector, "lastReconnectAttemptMs"),
                "no reconnect attempt may be scheduled for an invalidated slot");
        assertTrue(connector.getHealthStatus().getMessage().contains("halted"),
                "the halt must be loud, not a silent stall");
    }

    @Test
    void pgLostSlotColumnHaltsReconnectLoudly() throws Exception {
        PGReplicationStream deadStream = mock(PGReplicationStream.class);
        when(deadStream.readPending()).thenThrow(new SQLException("terminating connection"));
        Connection connection = failingThenReconnectableConnection(deadStream);
        PostgreSQLLogicalReplicationCDCConnector connector = pgConnector("pg-h3-lost", connection);
        // server-side slot check: the row exists and reports lost=true
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean("lost")).thenReturn(true);
        stubSlotQuery(connection, rs);
        setField(connector, "replicationStream", deadStream);
        setField(connector, "connection", connection);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        connector.setEventListener(recordingListener(new CopyOnWriteArrayList<>()));

        connector.poll(); // stream failure
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
        connector.poll(); // reconnect attempt inspects the slot and must halt

        assertTrue((boolean) getField(connector, "slotInvalidated"),
                "lost=true on pg_replication_slots must be detected");
        assertTrue(connector.getHealthStatus().getMessage().contains("marked lost"),
                "health must explain WHY the slot cannot resume");
        assertNull(getField(connector, "replicationStream"),
                "a lost slot must not be rebuilt (its WAL is gone)");
    }

    @Test
    void pgDroppedSlotRowHaltsReconnectLoudly() throws Exception {
        PGReplicationStream deadStream = mock(PGReplicationStream.class);
        when(deadStream.readPending()).thenThrow(new SQLException("terminating connection"));
        Connection connection = failingThenReconnectableConnection(deadStream);
        PostgreSQLLogicalReplicationCDCConnector connector = pgConnector("pg-h3-dropped", connection);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false); // the slot row is gone
        stubSlotQuery(connection, rs);
        setField(connector, "replicationStream", deadStream);
        setField(connector, "connection", connection);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        connector.setEventListener(recordingListener(new CopyOnWriteArrayList<>()));

        connector.poll();
        connector.poll();

        assertTrue((boolean) getField(connector, "slotInvalidated"),
                "a slot that vanished server-side must be detected");
        assertTrue(connector.getHealthStatus().getMessage().contains("no longer exists"),
                "health must explain WHY the slot cannot resume");
    }

    // ------------------------------------------------------------------ helpers

    private static MySQLBinlogCDCConnector mySqlConnector(String name) {
        return new MySQLBinlogCDCConnector(CDCConfigurationBuilder.forMySQLBinlog(name)
                .username("u")
                .password("p")
                .property("reconnect.backoff.initial.ms", 1)
                .property("reconnect.backoff.max.ms", 5)
                .build());
    }

    private static PostgreSQLLogicalReplicationCDCConnector pgConnector(String name) {
        return pgConnector(name, null);
    }

    /**
     * @param reopenConnection replaces the real DriverManager reopen of the reconnect path
     *                         (null keeps the base behavior, which no reconnect test reaches)
     */
    private static PostgreSQLLogicalReplicationCDCConnector pgConnector(String name,
                                                                        Connection reopenConnection) {
        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(
                CDCConfigurationBuilder.forPostgreSQLLogicalReplication(name)
                        .postgresqlDatabase("test_db")
                        .property("reconnect.backoff.ms", 1)
                        .build()) {
            @Override
            protected void openConnection(String hostname, int port, String database,
                                          String username, String password) throws SQLException {
                if (reopenConnection == null) {
                    super.openConnection(hostname, port, database, username, password);
                    return;
                }
                try {
                    // the tests bypass doStart()/DriverManager: reseed the wired mock connection
                    setField(this, "connection", reopenConnection);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        try {
            // the tests bypass doStart(), so seed the parsed slot name for the stream rebuild
            // and mark a stream as started (the invalidated-slot halts apply only after one ran)
            setField(connector, "slotName", "cdc_slot");
            setField(connector, "streamEverStarted", true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return connector;
    }

    private static void injectMySqlClient(MySQLBinlogCDCConnector connector, BinaryLogClient client)
            throws Exception {
        setField(connector, "binaryLogClient", client);
        // the tests bypass doStart(), so inject the parsed backoff knobs directly
        setField(connector, "reconnectBackoffInitialMs", 1L);
        setField(connector, "reconnectBackoffMaxMs", 5L);
        // the tests bypass doStart(), so provide the reconnect executor directly
        setField(connector, "reconnectExecutor", Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-mysql-reconnect");
            t.setDaemon(true);
            return t;
        }));
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
    }

    /** A mock connection whose stream-rebuild chain succeeds with the given stream mock. */
    private static Connection failingThenReconnectableConnection(PGReplicationStream newStream)
            throws Exception {
        ChainedLogicalStreamBuilder builder = mock(ChainedLogicalStreamBuilder.class);
        when(builder.withSlotName(anyString())).thenReturn(builder);
        when(builder.withStatusInterval(anyInt(), any())).thenReturn(builder);
        when(builder.withStartPosition(any())).thenReturn(builder);
        when(builder.start()).thenReturn(newStream);
        ChainedStreamBuilder streamBuilder = mock(ChainedStreamBuilder.class);
        when(streamBuilder.logical()).thenReturn(builder);
        PGReplicationConnection api = mock(PGReplicationConnection.class);
        when(api.replicationStream()).thenReturn(streamBuilder);
        PGConnection pgConnection = mock(PGConnection.class);
        when(pgConnection.getReplicationAPI()).thenReturn(api);
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.isClosed()).thenReturn(false);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        // default slot probe: the slot row exists and is healthy (tests 7/8 override this)
        try {
            ResultSet healthySlot = mock(ResultSet.class);
            when(healthySlot.next()).thenReturn(true);
            when(healthySlot.getBoolean("lost")).thenReturn(false);
            stubSlotQuery(connection, healthySlot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return connection;
    }

    private static void stubSlotQuery(Connection connection, ResultSet rs) throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
    }

    private static CDCEventListener recordingListener(List<String> errors) {
        return new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error));
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = declaredField(target.getClass(), name);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = declaredField(target.getClass(), name);
        return f.get(target);
    }

    /** Walks the class hierarchy: anonymous test subclasses declare nothing themselves. */
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

    private static void invoke(Object target, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
