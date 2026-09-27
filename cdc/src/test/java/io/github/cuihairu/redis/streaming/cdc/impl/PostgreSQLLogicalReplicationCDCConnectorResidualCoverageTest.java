package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.CDCHealthStatus;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationConnection;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.ChainedStreamBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link PostgreSQLLogicalReplicationCDCConnector}: the full
 * doStart pipeline (slot/publication creation), stop/commit/reset (LSN offset) paths, live
 * WAL-message processing, parser coercions and every reconnect-failure branch — all against
 * mocked JDBC, no external PostgreSQL.
 */
@Timeout(60)
class PostgreSQLLogicalReplicationCDCConnectorResidualCoverageTest {

    // ------------------------------------------------------------------ doStart pipeline

    @Test
    void doStartCreatesMissingSlotAndExistingPublicationThenStartsStream() throws Exception {
        Connection connection = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(connection.createStatement()).thenReturn(stmt);
        wireReplicationApi(connection, mockStream());
        // override the default healthy-slot probe: the slot is missing here
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(false);
        stubQuery(connection, "pg_replication_slots", slotRs);
        ResultSet pubRs = mock(ResultSet.class);
        when(pubRs.next()).thenReturn(true); // publication exists -> must NOT be created
        stubQuery(connection, "pg_publication", pubRs);

        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-start-1",
                builder("pg-start-1").property("publication.name", "h3_pub").build(), connection);

        connector.start().get(15, TimeUnit.SECONDS);
        connector.stop().get(15, TimeUnit.SECONDS);

        assertFalse(connector.isStreamActive(), "stop must have closed the stream");
        assertEquals("h3_pub", connector.getPublicationName());
        // slot created with the configured name via test_decoding
        verify(stmt).execute(contains("pg_create_logical_replication_slot"));
        verify(stmt).execute(contains("'cdc_slot'"));
        verify(stmt).execute(contains("test_decoding"));
        verify(stmt, never()).execute(contains("CREATE PUBLICATION"));
    }

    @Test
    void doStartSkipsExistingSlotAndCreatesMissingPublication() throws Exception {
        Connection connection = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(connection.createStatement()).thenReturn(stmt);
        wireReplicationApi(connection, mockStream());
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(true); // slot exists -> keep it
        stubQuery(connection, "pg_replication_slots", slotRs);
        // override: the publication is missing here
        ResultSet pubRs = mock(ResultSet.class);
        when(pubRs.next()).thenReturn(false);
        stubQuery(connection, "pg_publication", pubRs);

        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-start-2",
                builder("pg-start-2").property("publication.name", "h3_pub_missing").build(), connection);

        connector.start().get(15, TimeUnit.SECONDS);
        connector.stop().get(15, TimeUnit.SECONDS);

        verify(stmt, never()).execute(contains("pg_create_logical_replication_slot"));
        verify(stmt).execute(contains("CREATE PUBLICATION h3_pub_missing"));
    }

    @Test
    void doStartWithoutPublicationNeverTouchesPublicationCatalog() throws Exception {
        Connection connection = mock(Connection.class);
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(true);
        stubQuery(connection, "pg_replication_slots", slotRs);
        wireReplicationApi(connection, mockStream());

        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-start-3",
                config("pg-start-3"), connection);
        assertNull(connector.getPublicationName());

        connector.start().get(15, TimeUnit.SECONDS);
        connector.stop().get(15, TimeUnit.SECONDS);

        verify(connection, never()).prepareStatement(contains("pg_publication"));
    }

    @Test
    void stopClosesStreamAndConnection() throws Exception {
        PGReplicationStream stream = mockStream();
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-stop",
                config("pg-stop"), connection);

        connector.start().get(15, TimeUnit.SECONDS);
        assertTrue(connector.isStreamActive());
        connector.stop().get(15, TimeUnit.SECONDS);

        verify(stream).close();
        verify(connection).close();
        assertFalse(connector.isStreamActive());
        assertEquals(CDCHealthStatus.Status.UNKNOWN, connector.getHealthStatus().getStatus());
    }

    // ------------------------------------------------------------------ commit / reset (LSN offset)

    @Test
    void commitFeedsAppliedAndFlushedLsnBackToTheStream() throws Exception {
        PGReplicationStream stream = mockStream();
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-commit",
                config("pg-commit"), connection);
        connector.start().get(15, TimeUnit.SECONDS);
        List<String> commits = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onPositionCommitted(String connectorName, String position) {
                commits.add(position);
            }
        });

        connector.commit("0/16B45D0");

        verify(stream).setAppliedLSN(LogSequenceNumber.valueOf("0/16B45D0"));
        verify(stream).setFlushedLSN(LogSequenceNumber.valueOf("0/16B45D0"));
        assertEquals("0/16B45D0", connector.getCurrentPosition());
        assertEquals(List.of("0/16B45D0"), commits);
    }

    @Test
    void commitWithoutStreamIsIgnoredGracefully() {
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-commit-null",
                config("pg-commit-null"), null);

        connector.commit("0/16B45D0"); // no stream yet: must not throw
        assertEquals("0/16B45D0", connector.getCurrentPosition(),
                "the abstract layer stamps the committed position even without a live stream");
    }

    @Test
    void resetToPositionRestartsTheStreamAtTheGivenLsn() throws Exception {
        PGReplicationStream oldStream = mockStream();
        PGReplicationStream newStream = mockStream();
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, oldStream, newStream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-reset",
                config("pg-reset"), connection);
        connector.start().get(15, TimeUnit.SECONDS);
        assertSame(oldStream, field(connector, "replicationStream"));

        connector.doResetToPosition("0/FFFF");

        verify(oldStream).close();
        assertEquals("0/FFFF", connector.getCurrentLSN());
        assertSame(newStream, field(connector, "replicationStream"));
        assertTrue(connector.isStreamActive());
    }

    // ------------------------------------------------------------------ WAL message processing

    @Test
    void walMessagesAreParsedCoercedAndPositionStamped() throws Exception {
        PGReplicationStream stream = mock(PGReplicationStream.class);
        AtomicInteger reads = new AtomicInteger();
        when(stream.readPending()).thenAnswer(inv -> {
            switch (reads.incrementAndGet()) {
                case 1:
                    return null; // no data ready yet
                case 2:
                    return buffer(""); // empty message: skipped
                default:
                    return buffer("BEGIN\n" // no table yet: line skipped
                            + "table public.users: INSERT: id[bigint]:7 name[text]:'Ann'\n");
            }
        });
        when(stream.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/16B45D1"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-wal",
                config("pg-wal"), connection);
        connector.start().get(15, TimeUnit.SECONDS);

        // each poll() handles exactly one readPending(); the parsed event is returned by the
        // NEXT poll (the batch is drained before the WAL read)
        assertTrue(connector.poll().isEmpty(), "readPending()==null and an empty message emit nothing");
        assertTrue(connector.poll().isEmpty());
        assertTrue(connector.poll().isEmpty(), "the payload is parsed and queued during this poll");
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        ChangeEvent event = events.get(0);
        assertEquals(ChangeEvent.EventType.INSERT, event.getEventType());
        assertEquals("public", event.getDatabase());
        assertEquals("users", event.getTable());
        assertEquals(7L, event.getAfterData().get("id"));
        assertEquals("Ann", event.getAfterData().get("name"));
        assertEquals("0/16B45D1", connector.getCurrentLSN());
        assertEquals("0/16B45D1", event.getPosition(),
                "events are stamped with the LSN watermark of their WAL message");
    }

    @Test
    void parserCoversAllPgTypeCoercionsAndNulls() throws Exception {
        PGReplicationStream stream = mock(PGReplicationStream.class);
        when(stream.readPending()).thenReturn(buffer(
                "table public.t: INSERT: a[bigint]:5 b[numeric]:1.5 c[boolean]:true"
                        + " note[text]:null broken[int8]:notanum custom[weirdtype]:xyz"
                        + " plain:raw small[int2]:3"));
        when(stream.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/10"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-coerce",
                config("pg-coerce"), connection);
        connector.start().get(15, TimeUnit.SECONDS);

        assertTrue(connector.poll().isEmpty(), "the payload is parsed and queued during this poll");
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        var data = events.get(0).getAfterData();
        assertEquals(5L, data.get("a"));
        assertEquals(1.5, data.get("b"));
        assertEquals(Boolean.TRUE, data.get("c"));
        assertNull(data.get("note"), "a literal null value maps to a null entry");
        assertEquals("notanum", data.get("broken"), "unparseable numbers fall back to the raw string");
        assertEquals("xyz", data.get("custom"), "unknown types fall back to the raw string");
        assertEquals("raw", data.get("plain"), "columns without [type] still parse");
        assertEquals(3, data.get("small"));
    }

    @Test
    void includeFilteredWalTablesAreSkipped() throws Exception {
        PGReplicationStream stream = mock(PGReplicationStream.class);
        when(stream.readPending()).thenReturn(buffer(
                "table public.hidden: INSERT: id[integer]:1\n"
                        + "table public.shown: INSERT: id[integer]:2\n"));
        when(stream.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/20"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-filter",
                builder("pg-filter").property("table.includes", List.of("shown")).build(), connection);
        connector.start().get(15, TimeUnit.SECONDS);

        assertTrue(connector.poll().isEmpty(), "the payload is parsed and queued during this poll");
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        assertEquals("shown", events.get(0).getTable());
    }

    @Test
    void updateAndDeleteWalMessagesProduceBeforeAfterSnapshots() throws Exception {
        PGReplicationStream stream = mock(PGReplicationStream.class);
        when(stream.readPending()).thenReturn(buffer(
                "table public.users: UPDATE: id[integer]:2 name[text]:'B' old-key:id[integer]:2\n"));
        when(stream.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/30"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-upd",
                config("pg-upd"), connection);
        connector.start().get(15, TimeUnit.SECONDS);

        assertTrue(connector.poll().isEmpty(), "the payload is parsed and queued during this poll");
        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        assertEquals(ChangeEvent.EventType.UPDATE, events.get(0).getEventType());
        assertEquals("B", events.get(0).getAfterData().get("name"));

        PGReplicationStream stream2 = mock(PGReplicationStream.class);
        when(stream2.readPending()).thenReturn(buffer("table public.users: DELETE: id[integer]:2\n"));
        when(stream2.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/31"));
        setField(connector, "replicationStream", stream2);
        assertTrue(connector.poll().isEmpty(), "the payload is parsed and queued during this poll");
        events = connector.poll();
        assertEquals(1, events.size());
        assertEquals(ChangeEvent.EventType.DELETE, events.get(0).getEventType());
        assertEquals(2, events.get(0).getBeforeData().get("id"));
    }

    @Test
    void interruptedBackpressuredEnqueueThrowsAndReportsLoss() throws Exception {
        // capacity-1 queue: the second WAL message blocks until the delivery thread is
        // interrupted; the event stays un-acknowledged so the stream replays it (at-least-once)
        PGReplicationStream stream = mock(PGReplicationStream.class);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(stream.readPending()).thenAnswer(inv -> {
            if (reads.incrementAndGet() == 1) {
                return buffer("table public.users: INSERT: id[integer]:1\n");
            }
            return buffer("table public.users: INSERT: id[integer]:2\n");
        });
        when(stream.getLastReceiveLSN()).thenReturn(LogSequenceNumber.valueOf("0/40"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, stream);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-bp",
                CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg-bp")
                        .postgresqlDatabase("test_db")
                        .property("event.queue.capacity", 1)
                        .build(), connection);
        connector.start().get(15, TimeUnit.SECONDS);
        assertTrue(connector.poll().isEmpty(), "the first message is parsed and queued during this poll");
        assertEquals(1, connector.poll().size()); // drains the first event

        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        Thread delivering = new Thread(connector::poll); // second delivery blocks on the full queue
        delivering.start();
        delivering.interrupt();
        delivering.join(10_000);
        assertFalse(delivering.isAlive());
        assertTrue(errors.stream().anyMatch(e -> e.contains("not enqueued")),
                "the undelivered event must surface as an error: " + errors);
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    void streamFailureThroughThePollPathTearsDownAndFlipsUnhealthy() throws Exception {
        PGReplicationStream dead = mock(PGReplicationStream.class);
        when(dead.readPending()).thenThrow(new SQLException("terminating connection due to administrator command"));
        Connection connection = mock(Connection.class);
        wireReplicationApi(connection, dead);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-pollfail",
                config("pg-pollfail"), connection);
        connector.start().get(15, TimeUnit.SECONDS);
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(recordingListener(errors));

        assertTrue(connector.poll().isEmpty());

        assertTrue(errors.size() >= 1, "the read failure must be notified");
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
        assertTrue(connector.getHealthStatus().getMessage().contains("stream lost (CDC-H3)"));
        assertNull(field(connector, "replicationStream"), "the dead stream must be torn down");
        assertNull(field(connector, "connection"), "the dead connection must be torn down");
        assertFalse((boolean) field(connector, "slotInvalidated"),
                "an ordinary connection cut is not slot invalidation");
    }

    @Test
    void streamFailureIsNoopWhenConnectorIsStopping() throws Exception {
        PGReplicationStream stream = mockStream();
        Connection connection = mock(Connection.class);
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-stop-fail",
                config("pg-stop-fail"), null);
        setField(connector, "replicationStream", stream);
        setField(connector, "connection", connection);
        connector.running.set(false); // stop() already owns the teardown

        connector.handleStreamFailure(new SQLException("read failed"));

        assertSame(stream, field(connector, "replicationStream"),
                "the stopping path must leave teardown to doStop()");
        assertSame(connection, field(connector, "connection"));
    }

    @Test
    void teardownToleratesThrowingStreamAndConnectionClose() throws Exception {
        PGReplicationStream stream = mockStream();
        doThrow(new RuntimeException("already dead")).when(stream).close();
        Connection connection = mock(Connection.class);
        doThrow(new RuntimeException("already dead")).when(connection).close();
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-close-throw",
                config("pg-close-throw"), null);
        setField(connector, "replicationStream", stream);
        setField(connector, "connection", connection);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        connector.handleStreamFailure(new SQLException("read failed"));

        assertNull(field(connector, "replicationStream"));
        assertNull(field(connector, "connection"));
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
    }

    @Test
    void nullMessageSqlExceptionIsNotSlotInvalidation() {
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-nullmsg",
                config("pg-nullmsg"), null);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        connector.handleStreamFailure(new SQLException());

        assertFalse((boolean) field(connector, "slotInvalidated"),
                "a message-less SQLException cannot mean slot invalidation");
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
    }

    // ------------------------------------------------------------------ reconnect branches

    @Test
    void reconnectIsRateLimitedByBackoff() throws Exception {
        AtomicInteger reopens = new AtomicInteger();
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopenCounting("pg-rate",
                CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg-rate")
                        .postgresqlDatabase("test_db")
                        .property("reconnect.backoff.ms", 60_000) // window cannot elapse mid-test
                        .build(), reopens);
        seedForPoll(connector);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");
        setField(connector, "lastReconnectAttemptMs", System.currentTimeMillis()); // just tried

        connector.poll();

        assertEquals(0, reopens.get(), "a fresh backoff window must suppress the reopen attempt");
    }

    @Test
    void reconnectRuntimeExceptionMarksConnectorUnhealthy() throws Exception {
        AtomicInteger reopens = new AtomicInteger();
        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(
                config("pg-rt-fail")) {
            @Override
            protected void openConnection(String hostname, int port, String database,
                                          String username, String password) {
                reopens.incrementAndGet();
                throw new IllegalStateException("dns is gone");
            }
        };
        seedForPoll(connector);
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        connector.poll();

        assertEquals(1, reopens.get());
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, connector.getHealthStatus().getStatus());
        assertTrue(connector.getHealthStatus().getMessage().contains("reconnect attempt failed (CDC-H3)"));
    }

    @Test
    void reconnectSqlExceptionAboutTheSlotHaltsLoudly() throws Exception {
        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(
                config("pg-sql-invalidate")) {
            @Override
            protected void openConnection(String hostname, int port, String database,
                                          String username, String password) throws SQLException {
                throw new SQLException("replication slot \"cdc_slot\" does not exist");
            }
        };
        seedForPoll(connector); // streamEverStarted=true: a missing slot is now fatal
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        connector.poll();

        assertTrue((boolean) field(connector, "slotInvalidated"));
        assertTrue(connector.getHealthStatus().getMessage().contains("invalidated (CDC-H3)"));
    }

    @Test
    void slotProbeReportsNothingWithoutConnection() throws Exception {
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-probe-null",
                config("pg-probe-null"), null);

        assertNull(invokePrivateSlotInvalidationOnServer(connector),
                "without a connection the best-effort probe must report nothing");
    }

    @Test
    void brokenSlotProbeDoesNotBlockTheResumeAttempt() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        wireReplicationApi(connection, mockStream());
        // last stub wins: the slot probe itself explodes (best-effort, must be tolerated)
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("probe exploded"));
        PostgreSQLLogicalReplicationCDCConnector connector = connectorWithReopen("pg-probe",
                config("pg-probe"), connection);
        seedForPoll(connector); // streamEverStarted=true so the reconnect path probes the slot
        connector.running.set(true);
        connector.healthStatus = CDCHealthStatus.healthy("up");

        connector.poll(); // rebuild succeeds despite the broken probe

        assertTrue(connector.isStreamActive());
        assertTrue(connector.getHealthStatus().getMessage().contains("reconnected (CDC-H3)"));
    }

    // ------------------------------------------------------------------ helpers

    private static CDCConfiguration config(String name) {
        return builder(name).build();
    }

    /** Builder for configs that need extra properties (publication.name, table.includes...). */
    private static CDCConfigurationBuilder builder(String name) {
        return CDCConfigurationBuilder.forPostgreSQLLogicalReplication(name)
                .postgresqlDatabase("test_db")
                .property("reconnect.backoff.ms", 1);
    }

    /** Anonymous subclass whose reopen path injects the given mock connection. */
    private static PostgreSQLLogicalReplicationCDCConnector connectorWithReopen(String name,
                                                                                CDCConfiguration cfg,
                                                                                Connection reopenConnection) {
        return new PostgreSQLLogicalReplicationCDCConnector(cfg) {
            @Override
            protected void openConnection(String hostname, int port, String database,
                                          String username, String password) throws SQLException {
                if (reopenConnection == null) {
                    super.openConnection(hostname, port, database, username, password);
                    return;
                }
                try {
                    setField(this, "connection", reopenConnection);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static PostgreSQLLogicalReplicationCDCConnector connectorWithReopen(String name,
                                                                                Connection reopenConnection) {
        return connectorWithReopen(name, config(name), reopenConnection);
    }

    private static PostgreSQLLogicalReplicationCDCConnector connectorWithReopenCounting(
            String name, CDCConfiguration cfg, AtomicInteger reopens) {
        return new PostgreSQLLogicalReplicationCDCConnector(cfg) {
            @Override
            protected void openConnection(String hostname, int port, String database,
                                          String username, String password) {
                reopens.incrementAndGet();
            }
        };
    }

    /** Seeds the fields doStart() would normally parse, for tests that call poll() directly. */
    private static void seedForPoll(PostgreSQLLogicalReplicationCDCConnector connector) throws Exception {
        setField(connector, "slotName", "cdc_slot");
        setField(connector, "streamEverStarted", true);
    }

    private static PGReplicationStream mockStream() {
        PGReplicationStream stream = mock(PGReplicationStream.class);
        try {
            when(stream.readPending()).thenReturn(null);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return stream;
    }

    /** Wires unwrap(PGConnection) -> replication API; streams are returned in order. */
    private static void wireReplicationApi(Connection connection, PGReplicationStream... streams)
            throws Exception {
        ChainedLogicalStreamBuilder logical = mock(ChainedLogicalStreamBuilder.class);
        when(logical.withSlotName(anyString())).thenReturn(logical);
        when(logical.withStatusInterval(anyInt(), any())).thenReturn(logical);
        when(logical.withStartPosition(any())).thenReturn(logical);
        if (streams.length == 1) {
            when(logical.start()).thenReturn(streams[0]);
        } else {
            AtomicInteger i = new AtomicInteger();
            when(logical.start()).thenAnswer(inv -> streams[Math.min(i.getAndIncrement(), streams.length - 1)]);
        }
        ChainedStreamBuilder replication = mock(ChainedStreamBuilder.class);
        when(replication.logical()).thenReturn(logical);
        PGReplicationConnection api = mock(PGReplicationConnection.class);
        when(api.replicationStream()).thenReturn(replication);
        PGConnection pg = mock(PGConnection.class);
        when(pg.getReplicationAPI()).thenReturn(api);
        when(connection.unwrap(PGConnection.class)).thenReturn(pg);
        // default slot probe: the row exists and is healthy (tests override it afterwards)
        try {
            ResultSet healthySlot = mock(ResultSet.class);
            when(healthySlot.next()).thenReturn(true);
            when(healthySlot.getBoolean("lost")).thenReturn(false);
            stubQuery(connection, "pg_replication_slots", healthySlot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stubQuery(Connection connection, String tableFragment, ResultSet rs)
            throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(connection.prepareStatement(contains(tableFragment))).thenReturn(ps);
    }

    private static Object invokePrivateSlotInvalidationOnServer(
            PostgreSQLLogicalReplicationCDCConnector connector) throws Exception {
        Method m = PostgreSQLLogicalReplicationCDCConnector.class
                .getDeclaredMethod("slotInvalidationOnServer");
        m.setAccessible(true);
        return m.invoke(connector);
    }

    private static ByteBuffer buffer(String message) {
        return ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
    }

    private static CDCEventListener recordingListener(List<String> errors) {
        return new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error));
            }
        };
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