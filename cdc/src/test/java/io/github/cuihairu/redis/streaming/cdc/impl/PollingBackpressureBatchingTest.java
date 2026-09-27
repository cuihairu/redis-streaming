package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDC-M1: the polling connector used to run {@code SELECT * ... ORDER BY col} with no
 * LIMIT, buffering an entire table in memory on every scan. It must now fetch bounded
 * batches ({@code poll.batch.limit}, default 1000), drain a table across several batches
 * within a single round, never split a watermark tie group, apply loss-free backpressure
 * when the bounded event queue is full, and fall back to defaults on invalid config.
 *
 * <p>Runs against embedded H2; the background scheduler is disabled
 * ({@code pollingIntervalMs=0}) so every scan is driven explicitly and deterministically.
 * Events are drained straight from the (reflected) event queue — {@code poll()} itself
 * triggers another scan, which would blur the per-scan statement accounting.</p>
 *
 * <p>The H2 URL disables {@code DATABASE_TO_UPPER} so result-set labels keep the
 * lowercase spelling the connector queries use — otherwise {@code generateKey} never sees
 * an "id" column and event keys degrade to value-joins.</p>
 */
@Tag("integration")
class PollingBackpressureBatchingTest {

    private static final int LIMIT = 4; // small batch limit so boundary cases fit in tests
    private static final String DDL = "CREATE TABLE t(id INT PRIMARY KEY, v INT, msg VARCHAR(64))";

    @Test
    void batchBoundaries_zeroRows_oneRow_exactlyOneBatch() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = connector(db, LIMIT);
            List<String> sqls = swapRecordingDataSource(connector);
            try {
                // 0 rows: nothing emitted, no watermark
                scanOnly(connector);
                assertTrue(drainQueue(connector).isEmpty());
                assertEquals(1, sqls.size(), "one bounded statement per table scan");
                assertEquals("SELECT * FROM t ORDER BY v LIMIT " + (LIMIT + 1), sqls.get(0));
                assertTrue(connector.getLastPolledValues().isEmpty());

                // 1 row: the single row below the probe limit is emitted (the very first
                // scan starts from the watermark-free variant)
                insert(keeper, 1);
                scanOnly(connector);
                assertEquals(List.of("1"), keys(drainQueue(connector)));
                assertEquals("SELECT * FROM t ORDER BY v LIMIT " + (LIMIT + 1), sqls.get(1));

                // exactly one batch (pending == limit): fetched once, not truncated
                insert(keeper, 2, 3, 4, 5);
                scanOnly(connector);
                assertEquals(3, sqls.size(), "a full batch must be fetched with a single statement");
                assertEquals("SELECT * FROM t WHERE v > ? ORDER BY v LIMIT " + (LIMIT + 1), sqls.get(2),
                        "resumption scans must continue strictly after the watermark");
                assertEquals(List.of("2", "3", "4", "5"), keys(drainQueue(connector)));
                assertEquals(Map.of("t", 5), connector.getLastPolledValues());
            } finally {
                connector.stop().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void multiBatchTableDrainsCompletelyInOneRoundWithoutLossOrDuplicates() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = connector(db, LIMIT);
            List<String> sqls = swapRecordingDataSource(connector);
            try {
                for (int id = 1; id <= 19; id++) { // 19 = 4 batches + 3 remainder
                    insert(keeper, id);
                }

                scanOnly(connector);
                List<ChangeEvent> events = drainQueue(connector);

                // every row exactly once, in watermark order, from one scan round
                List<String> expected = new ArrayList<>();
                for (int id = 1; id <= 19; id++) {
                    expected.add(Integer.toString(id));
                }
                assertEquals(expected, keys(events));
                assertEquals(5, sqls.size(), "ceil(19/4)=5 bounded fetches for one round");
                sqls.forEach(sql -> assertTrue(sql.endsWith("LIMIT " + (LIMIT + 1)),
                        "every fetch must be LIMIT-bounded, saw: " + sql));
                assertEquals(Map.of("t", 19), connector.getLastPolledValues());

                // the round ended exactly at the watermark: the next scan finds nothing
                scanOnly(connector);
                assertTrue(drainQueue(connector).isEmpty());
            } finally {
                connector.stop().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void tieGroupAtBatchBoundaryIsNeverSplitAcrossBatches() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = connector(db, LIMIT);
            try {
                // v=9 repeats across the batch boundary (positions 4..7 with limit=4):
                // a naive cut would emit part of the tie group and watermark past the rest.
                insertPairs(keeper, new int[]{1, 1}, new int[]{2, 2}, new int[]{3, 3},
                        new int[]{4, 9}, new int[]{5, 9}, new int[]{6, 9}, new int[]{7, 9});

                scanOnly(connector);
                List<ChangeEvent> events = drainQueue(connector);

                assertEquals(7, events.size(), "every row must be emitted exactly once");
                List<Integer> ids = events.stream()
                        .map(e -> Integer.parseInt(e.getKey()))
                        .sorted()
                        .toList();
                assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), ids);
                assertEquals(Map.of("t", 9), connector.getLastPolledValues(),
                        "watermark must end on the shared tie value only after the whole group");
            } finally {
                connector.stop().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void queueFullAppliesBackpressureWithoutLossAndRecovers() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-bp-full")
                            .username("sa").password("")
                            .batchSize(200)
                            .pollingIntervalMs(0)
                            .property("jdbc.url", db)
                            .property("driver.class", "org.h2.Driver")
                            .property("tables", "t")
                            .property("incremental.column", "v")
                            .property("event.queue.capacity", "3") // force backpressure after 3 rows
                            .build());
            connector.start().get(15, TimeUnit.SECONDS);
            try {
                for (int id = 1; id <= 10; id++) {
                    insert(keeper, id);
                }

                BlockingQueue<ChangeEvent> queue = queueOf(connector);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread scanner = scanner(connector, failure);
                scanner.start();

                waitUntilQueueFull(queue, 3);
                Thread.sleep(200); // let the producer hit the full queue
                assertEquals(3, queue.size(), "producer must be parked on the bounded queue");
                assertTrue(scanner.isAlive());

                // drain while the producer keeps refilling: all 10 rows, no loss, in order
                for (int expected = 1; expected <= 10; expected++) {
                    ChangeEvent e = queue.poll(5, TimeUnit.SECONDS);
                    assertNotNull(e, "no event may be lost under backpressure (waiting for #" + expected + ")");
                    assertEquals(Integer.toString(expected), e.getKey());
                }
                scanner.join(TimeUnit.SECONDS.toMillis(5));
                assertTrue(failure.get() == null, () -> "scanner failed: " + failure.get());
                assertEquals(0, queue.size());
                assertEquals(Map.of("t", 10), connector.getLastPolledValues());
                assertEquals("t:10", connector.getCurrentPosition());
            } finally {
                connector.stop().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void stopWhileBlockedAbortsScanAndKeepsWatermarkAtLastEnqueuedRow() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-bp-stop")
                            .username("sa").password("")
                            .batchSize(200)
                            .pollingIntervalMs(0)
                            .property("jdbc.url", db)
                            .property("driver.class", "org.h2.Driver")
                            .property("tables", "t")
                            .property("incremental.column", "v")
                            .property("event.queue.capacity", "3")
                            .build());
            connector.start().get(15, TimeUnit.SECONDS);
            try {
                for (int id = 1; id <= 10; id++) {
                    insert(keeper, id);
                }

                BlockingQueue<ChangeEvent> queue = queueOf(connector);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread scanner = scanner(connector, failure);
                scanner.start();
                waitUntilQueueFull(queue, 3);
                Thread.sleep(200);
                assertTrue(scanner.isAlive());

                connector.stop().get(15, TimeUnit.SECONDS);
                scanner.join(TimeUnit.SECONDS.toMillis(5));
                assertTrue(failure.get() == null, () -> "scanner failed: " + failure.get());
                assertEquals(3, queue.size());

                // emit-then-advance: the watermark only covers rows that were actually enqueued;
                // the rest of the table stays below it and is re-polled after a restart.
                assertEquals(Map.of("t", 3), connector.getLastPolledValues());
                assertEquals("t:3", connector.getCurrentPosition());
                for (int expected = 1; expected <= 3; expected++) {
                    ChangeEvent e = queue.poll(1, TimeUnit.SECONDS);
                    assertNotNull(e);
                    assertEquals(Integer.toString(expected), e.getKey());
                }
            } finally {
                if (connector.isRunning()) {
                    connector.stop().get(15, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void invalidPollBatchLimitFallsBackToDefaultBoundedFetch() throws Exception {
        String db = freshDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute(DDL);
            }
            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-bp-invalid")
                            .username("sa").password("")
                            .batchSize(200)
                            .pollingIntervalMs(0)
                            .property("jdbc.url", db)
                            .property("driver.class", "org.h2.Driver")
                            .property("tables", "t")
                            .property("incremental.column", "v")
                            .property("poll.batch.limit", "abc") // typo must not disable the bounded scan
                            .build());
            connector.start().get(15, TimeUnit.SECONDS);
            List<String> sqls = swapRecordingDataSource(connector);
            try {
                for (int id = 1; id <= 5; id++) {
                    insert(keeper, id);
                }
                scanOnly(connector);
                assertEquals(List.of("1", "2", "3", "4", "5"), keys(drainQueue(connector)));
                assertEquals("SELECT * FROM t ORDER BY v LIMIT 1001", sqls.get(0),
                        "invalid poll.batch.limit must fall back to the default (1000+1 probe)");
            } finally {
                connector.stop().get(15, TimeUnit.SECONDS);
            }
        }
    }

    // ===== helpers =====

    private static String freshDb() {
        return "jdbc:h2:mem:cdc" + UUID.randomUUID().toString().substring(0, 8)
                + ";DATABASE_TO_UPPER=false";
    }

    private static DatabasePollingCDCConnector connector(String db, int batchLimit) throws Exception {
        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-bp")
                        .username("sa").password("")
                        .batchSize(200)
                        .pollingIntervalMs(0)
                        .property("jdbc.url", db)
                        .property("driver.class", "org.h2.Driver")
                        .property("tables", "t")
                        .property("incremental.column", "v")
                        .property("poll.batch.limit", String.valueOf(batchLimit))
                        .build());
        connector.start().get(15, TimeUnit.SECONDS);
        return connector;
    }

    private static void insert(Connection keeper, int... ids) throws Exception {
        try (Statement st = keeper.createStatement()) {
            for (int id : ids) {
                st.execute("INSERT INTO t(id, v, msg) VALUES ("
                        + id + ", " + id + ", 'm" + id + "')");
            }
        }
    }

    /** Inserts (id, v) pairs where the watermark value differs from the id. */
    private static void insertPairs(Connection keeper, int[]... idAndValue) throws Exception {
        try (Statement st = keeper.createStatement()) {
            for (int[] pair : idAndValue) {
                st.execute("INSERT INTO t(id, v, msg) VALUES (" + pair[0] + ", "
                        + pair[1] + ", 'm" + pair[0] + "')");
            }
        }
    }

    private static List<String> keys(List<ChangeEvent> events) {
        return events.stream().map(ChangeEvent::getKey).toList();
    }

    private static void scanOnly(DatabasePollingCDCConnector connector) throws Exception {
        Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("pollTablesForChanges");
        m.setAccessible(true);
        m.invoke(connector);
    }

    /** Runs one table scan on a helper thread (used to observe blocking/backpressure). */
    private static Thread scanner(DatabasePollingCDCConnector connector, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                scanOnly(connector);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "h2-scanner");
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<ChangeEvent> queueOf(DatabasePollingCDCConnector connector) throws Exception {
        Field f = DatabasePollingCDCConnector.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        return (BlockingQueue<ChangeEvent>) f.get(connector);
    }

    private static List<ChangeEvent> drainQueue(DatabasePollingCDCConnector connector) throws Exception {
        BlockingQueue<ChangeEvent> queue = queueOf(connector);
        List<ChangeEvent> out = new ArrayList<>();
        ChangeEvent e;
        while ((e = queue.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    private static void waitUntilQueueFull(BlockingQueue<ChangeEvent> queue, int capacity) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (queue.size() < capacity && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(capacity, queue.size(), "producer never filled the queue");
    }

    /**
     * Replace the connector's pool with a recording proxy so tests can assert the exact
     * SQL of every scan statement (bounded fetch, resumption predicate).
     */
    private static List<String> swapRecordingDataSource(DatabasePollingCDCConnector connector) throws Exception {
        DataSource real = (DataSource) getField(connector, "dataSource");
        List<String> recorded = new CopyOnWriteArrayList<>();
        ClassLoader cl = PollingBackpressureBatchingTest.class.getClassLoader();
        DataSource proxy = (DataSource) Proxy.newProxyInstance(cl, new Class<?>[]{DataSource.class},
                (p, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        Connection conn = (Connection) method.invoke(real, args);
                        return Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                                (pc, m, a) -> {
                                    if ("prepareStatement".equals(m.getName())) {
                                        recorded.add((String) a[0]);
                                    }
                                    return m.invoke(conn, a);
                                });
                    }
                    return method.invoke(real, args);
                });
        setField(connector, "dataSource", proxy);
        return recorded;
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
