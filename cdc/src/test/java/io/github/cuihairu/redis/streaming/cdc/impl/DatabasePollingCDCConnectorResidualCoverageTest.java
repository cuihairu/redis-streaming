package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residual branch coverage for {@link DatabasePollingCDCConnector}'s scan loop: truncated
 * batches whose watermark group cannot be split, a scan aborted by stop/interrupt (whose
 * rows must stay below the persisted watermark), all driven by an in-memory H2 database.
 */
@Timeout(60)
class DatabasePollingCDCConnectorResidualCoverageTest {

    @Test
    void watermarkGroupLargerThanTheBatchIsFlushedWithoutSpinning() throws Exception {
        // 5 rows ALL sharing one watermark value with a 3-row batch: the +1 probe row reports
        // truncation, the boundary group cannot be split, so the first batch is flushed
        // forcibly and the round ends without progress (instead of spinning forever)
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE big_group(id INT PRIMARY KEY, w INT NULL)");
            for (int i = 1; i <= 5; i++) {
                exec(keeper, "INSERT INTO big_group(id, w) VALUES (" + i + ", NULL)");
            }

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-big-group")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.BIG_GROUP")
                            .property("incremental.column", "w")
                            .property("snapshot.enabled", true)
                            .property("poll.batch.limit", 3)
                            .build());
            AtomicInteger snapshotRecords = new AtomicInteger(-1);
            connector.setEventListener(new CDCEventListener() {
                @Override
                public void onSnapshotCompleted(String connectorName, long recordCount) {
                    snapshotRecords.set((int) recordCount);
                }
            });

            connector.start().get(15, TimeUnit.SECONDS);
            List<ChangeEvent> events = scan(connector);
            connector.stop().get(15, TimeUnit.SECONDS);

            assertEquals(3, events.size(),
                    "the first full batch must be flushed despite the unsplittable group");
            assertEquals(3, snapshotRecords.get());
            assertNull(connector.getLastPolledValues().get("PUBLIC.BIG_GROUP"),
                    "all-NULL watermark values must not advance the polling position");
        }
    }

    @Test
    void truncatedBatchDefersTheTieGroupAndReReadsIt() throws Exception {
        // values [1,2,2,2] with batch limit 3: the first fetch (LIMIT 4) is truncated and the
        // trailing 2-tie cannot be split, so the batch defers to the single pre-tie row; the
        // next fetch re-reads the whole tie via "> 1" and drains it
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE ties(id INT PRIMARY KEY, w INT NOT NULL)");
            int[][] rows = {{1, 1}, {2, 2}, {3, 2}, {4, 2}};
            for (int[] r : rows) {
                exec(keeper, "INSERT INTO ties(id, w) VALUES (" + r[0] + ", " + r[1] + ")");
            }

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-ties")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.TIES")
                            .property("incremental.column", "w")
                            .property("snapshot.enabled", true)
                            .property("poll.batch.limit", 3)
                            .build());

            connector.start().get(15, TimeUnit.SECONDS);
            List<ChangeEvent> events = scan(connector);
            connector.stop().get(15, TimeUnit.SECONDS);

            assertEquals(4, events.size(), "every row must be delivered exactly once across batches");
            assertEquals("2", connector.getLastPolledValues().get("PUBLIC.TIES").toString(),
                    "the watermark lands on the last emitted row's value");
        }
    }

    @Test
    void stoppingTheConnectorAbortsTheScanBelowTheWatermark() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE abort(id INT PRIMARY KEY, w INT NOT NULL)");
            for (int i = 1; i <= 3; i++) {
                exec(keeper, "INSERT INTO abort(id, w) VALUES (" + i + ", " + i + ")");
            }

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-abort")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.ABORT")
                            .property("incremental.column", "w")
                            .property("snapshot.enabled", true)
                            .property("event.queue.capacity", 1)
                            .build());
            // fill the capacity-1 queue so the scan's first enqueue must block
            queue(connector).put(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "x", null, Map.of()));

            connector.start().get(15, TimeUnit.SECONDS);

            // run the scan on its own thread; it blocks on the full queue until stop()
            Thread scanning = new Thread(() -> invokeScan(connector));
            scanning.start();
            awaitBlocked(scanning); // the enqueue must apply backpressure while the queue is full

            connector.stop().get(15, TimeUnit.SECONDS);
            scanning.join(10_000);
            assertTrue(!scanning.isAlive(), "the scan must abort once the connector stops");

            assertNull(connector.getLastPolledValues().get("PUBLIC.ABORT"),
                    "an aborted scan must NOT persist a watermark above its un-emitted rows (CDC-M1)");
        }
    }

    @Test
    void interruptedDeliveryThreadAbortsTheScan() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE intr(id INT PRIMARY KEY, w INT NOT NULL)");
            for (int i = 1; i <= 3; i++) {
                exec(keeper, "INSERT INTO intr(id, w) VALUES (" + i + ", " + i + ")");
            }

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-intr")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.INTR")
                            .property("incremental.column", "w")
                            .property("snapshot.enabled", true)
                            .property("event.queue.capacity", 1)
                            .build());
            queue(connector).put(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "x", null, Map.of()));

            connector.start().get(15, TimeUnit.SECONDS);

            Thread scanning = new Thread(() -> invokeScan(connector));
            scanning.start();
            awaitBlocked(scanning);
            scanning.interrupt(); // interrupts the 50ms offer slices
            scanning.join(10_000);
            assertTrue(!scanning.isAlive(), "an interrupted scan must abort promptly");

            assertNull(connector.getLastPolledValues().get("PUBLIC.INTR"),
                    "an interrupted scan must not persist a watermark above un-emitted rows");
            connector.stop().get(15, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Await the thread reaching a blocked state (park inside offer, monitor, …). */
    private static void awaitBlocked(Thread scanning) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = scanning.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                    || state == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("scan thread did not block within 5s, state=" + scanning.getState());
    }

    private static String newDb() {
        return "jdbc:h2:mem:res" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Runs the private scan eagerly and then returns one poll batch. */
    private static List<ChangeEvent> scan(DatabasePollingCDCConnector connector) throws Exception {
        invokeScan(connector);
        return connector.poll();
    }

    private static void invokeScan(DatabasePollingCDCConnector connector) {
        try {
            Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("pollTablesForChanges");
            m.setAccessible(true);
            m.invoke(connector);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.BlockingQueue<ChangeEvent> queue(
            DatabasePollingCDCConnector connector) throws Exception {
        Field f = DatabasePollingCDCConnector.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        return (java.util.concurrent.BlockingQueue<ChangeEvent>) f.get(connector);
    }
}