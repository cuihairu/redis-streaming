package io.github.cuihairu.redis.streaming.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.cdc.impl.DatabasePollingCDCConnector;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.cdc.mq.ChangeEventQueueSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.Redisson;
import org.redisson.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manager-level CDC integration tests (todo §7 "启动停止 / 多连接器并发"): a real
 * {@link CDCManager} drives real {@link DatabasePollingCDCConnector}s against a real
 * (embedded H2) database, so start/stop/restart and multi-connector concurrency are
 * exercised end to end instead of against mock connectors.
 *
 * <p>Determinism: every table uses an explicit {@code TIMESTAMP} literal so watermarks
 * never depend on the wall clock; rows present at first start are skipped by the
 * baseline (documented startup semantics), rows inserted while the manager is stopped
 * are resumed on restart (documented resume semantics — positions and the event queue
 * survive a stop/start cycle).</p>
 *
 * <p>The third test bridges captured events through {@link ChangeEventQueueSink} onto a
 * real Redis-backed MQ topic and asserts delivery through a real consumer. It needs a
 * reachable Redis ({@code REDIS_URL}, default {@code redis://127.0.0.1:6379} — start
 * {@code docker-compose.test.yml} or any local Redis) and is skipped with a clear
 * message otherwise.</p>
 */
@Tag("integration")
class CDCManagerLifecycleMultiConnectorIntegrationTest {

    /* ---------- start/stop lifecycle across a real database ---------- */

    @Test
    void managerStartStopAndRestartResumeAcrossRealDatabase() throws Exception {
        String db = h2();
        try (Connection keeper = connect(db)) {
            try (Statement st = keeper.createStatement()) {
                st.execute("CREATE TABLE orders(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))");
                st.execute("CREATE TABLE shipments(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))");
                insert(st, "orders", 1, "2020-01-01 10:00:00", "seed-o");
                insert(st, "shipments", 1, "2020-01-01 10:00:00", "seed-s");
            }

            DatabasePollingCDCConnector orders = pullConnector("it-orders", db, "orders");
            DatabasePollingCDCConnector shipments = pullConnector("it-shipments", db, "shipments");
            CDCManager manager = new CDCManager();
            manager.addConnector(orders);
            manager.addConnector(shipments);
            try {
                // start: both connectors come up and baseline-skip the pre-existing rows
                manager.start().get(30, TimeUnit.SECONDS);
                assertTrue(orders.isRunning());
                assertTrue(shipments.isRunning());
                assertEquals(Set.of("it-orders", "it-shipments"), manager.getHealthStatusAll().keySet());
                Map<String, List<ChangeEvent>> history = manager.pollAll();
                assertTrue(history.get("it-orders").isEmpty(), "history rows must be skipped at startup");
                assertTrue(history.get("it-shipments").isEmpty(), "history rows must be skipped at startup");

                // live rows are captured per table while the manager is running
                try (Statement st = keeper.createStatement()) {
                    insert(st, "orders", 2, "2020-01-01 10:00:01", "live-o");
                }
                List<ChangeEvent> live = drainPoll(manager, "it-orders", 1, 10_000);
                assertEquals(1, live.size());
                assertEquals("orders", live.get(0).getTable());
                assertEquals(ChangeEvent.EventType.INSERT, live.get(0).getEventType());
                assertEquals("live-o", live.get(0).getAfterData().get("ITEM")); // H2 upper-cases unquoted labels
                assertTrue(manager.getCurrentPositionsAll().containsKey("it-orders"));
                assertEquals(2, manager.getMetricsAll().size());

                // stop: every connector stops with the manager, and a second stop is idempotent
                manager.stop().get(30, TimeUnit.SECONDS);
                assertFalse(orders.isRunning());
                assertFalse(shipments.isRunning());
                manager.stop().get(30, TimeUnit.SECONDS);

                // rows written during the outage ...
                try (Statement st = keeper.createStatement()) {
                    insert(st, "orders", 3, "2020-01-01 10:00:02", "gap-o");
                }

                // ... are picked up by a restart: positions survive a stop/start cycle (CDC resume
                // semantics), and the manager restarts its own health scheduler (CDC-M6)
                manager.start().get(30, TimeUnit.SECONDS);
                assertTrue(orders.isRunning());
                assertTrue(shipments.isRunning());
                List<ChangeEvent> resumed = drainPoll(manager, "it-orders", 1, 10_000);
                assertEquals(1, resumed.size(), "the row inserted while stopped must be resumed");
                assertEquals("gap-o", resumed.get(0).getAfterData().get("ITEM"));
                assertTrue(manager.pollAll().get("it-orders").isEmpty(), "no double delivery after resume");
            } finally {
                manager.stop().get(30, TimeUnit.SECONDS);
            }
        }
    }

    /* ---------- multi-connector concurrency ---------- */

    @Test
    void threeConnectorsRunConcurrentlyWithoutCrossTalk() throws Exception {
        String db = h2();
        String[] tables = {"t_orders", "t_users", "t_payments"};
        try (Connection keeper = connect(db)) {
            try (Statement st = keeper.createStatement()) {
                for (String table : tables) {
                    st.execute("CREATE TABLE " + table
                            + "(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))");
                    insert(st, table, 0, "2020-01-01 09:00:00", "seed");
                }
            }

            CDCManager manager = new CDCManager();
            Map<String, List<ChangeEvent>> captured = new ConcurrentHashMap<>();
            Map<String, CountDownLatch> started = new ConcurrentHashMap<>();
            Map<String, CountDownLatch> stopped = new ConcurrentHashMap<>();
            for (String table : tables) {
                String name = "it-" + table;
                DatabasePollingCDCConnector connector = pushConnector(name, db, table);
                captured.put(name, new CopyOnWriteArrayList<>());
                started.put(name, new CountDownLatch(1));
                stopped.put(name, new CountDownLatch(1));
                connector.setEventListener(new CDCEventListener() {
                    @Override
                    public void onConnectorStarted(String connectorName) {
                        started.get(connectorName).countDown();
                    }

                    @Override
                    public void onConnectorStopped(String connectorName) {
                        stopped.get(connectorName).countDown();
                    }

                    @Override
                    public void onEvents(String connectorName, List<ChangeEvent> events) {
                        captured.get(connectorName).addAll(events);
                    }
                });
                manager.addConnector(connector);
            }

            try {
                manager.start().get(30, TimeUnit.SECONDS);
                assertTrue(awaitAll(started, 15_000), "all three connectors must report start");

                // interleaved writes into all three tables while every scheduler is live
                try (Statement st = keeper.createStatement()) {
                    insert(st, "t_orders", 1, "2020-01-01 09:00:01", "o1");
                    insert(st, "t_users", 1, "2020-01-01 09:00:02", "u1");
                    insert(st, "t_payments", 1, "2020-01-01 09:00:03", "p1");
                    insert(st, "t_orders", 2, "2020-01-01 09:00:04", "o2");
                    insert(st, "t_users", 2, "2020-01-01 09:00:05", "u2");
                    insert(st, "t_payments", 2, "2020-01-01 09:00:06", "p2");
                }

                for (String table : tables) {
                    String name = "it-" + table;
                    assertTrue(awaitSize(captured.get(name), 2, 15_000),
                            name + " must capture both of its own rows, got " + captured.get(name));
                    for (ChangeEvent event : captured.get(name)) {
                        assertEquals(table, event.getTable(), "no cross-table leakage into " + name);
                        assertEquals(ChangeEvent.EventType.INSERT, event.getEventType());
                        assertEquals(name, event.getSource());
                        assertTrue(Set.of("o1", "u1", "p1", "o2", "u2", "p2")
                                .contains(event.getAfterData().get("ITEM"))); // H2 upper-cases labels
                    }
                }

                assertEquals(3, manager.getHealthStatusAll().size());
                assertEquals(3, manager.getMetricsAll().size());

                // scheduled (push) polling owns the drained batches: nothing may be left
                // for a later pull poll() (documented in CDCEventListener#onEvents)
                for (Map.Entry<String, List<ChangeEvent>> left : manager.pollAll().entrySet()) {
                    assertTrue(left.getValue().isEmpty(),
                            left.getKey() + " must not leak scheduler-owned events to poll()");
                }

                manager.stop().get(30, TimeUnit.SECONDS);
                assertTrue(awaitAll(stopped, 15_000), "all three connectors must report stop");
                manager.getAllConnectors().forEach(c -> assertFalse(c.isRunning()));
            } finally {
                manager.stop().get(30, TimeUnit.SECONDS);
            }
        }
    }

    /* ---------- captured events bridged onto a real Redis MQ topic ---------- */

    @Test
    void managerEventsBridgeToRealRedisMqTopic() throws Exception {
        RedissonClient redis = null;
        MessageConsumer consumer = null;
        CDCManager manager = new CDCManager();
        try {
            redis = requireRedis();
            String topic = "cdc-bridge-it-" + UUID.randomUUID().toString().substring(0, 8);

            MessageQueueFactory factory = new MessageQueueFactory(redis);
            MessageProducer producer = factory.createProducer();
            consumer = factory.createConsumer("cdc-bridge-it");
            List<String> bridgedItems = new CopyOnWriteArrayList<>();
            List<String> bridgedKeys = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(2);
            consumer.subscribe(topic, "cdc-it", message -> {
                Map<?, ?> payload = payloadAsMap(message.getPayload());
                if (payload != null && "t_orders".equals(String.valueOf(payload.get("table")))) {
                    Object after = payload.get("after"); // row image, columns keyed as the driver reports them
                    Object item = after instanceof Map<?, ?> image ? image.get("ITEM") : null;
                    bridgedItems.add(String.valueOf(item));
                    bridgedKeys.add(message.getKey() == null ? null : String.valueOf(message.getKey()));
                    delivered.countDown();
                }
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();

            String db = h2();
            try (Connection keeper = connect(db)) {
                try (Statement st = keeper.createStatement()) {
                    st.execute("CREATE TABLE t_orders(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))");
                    insert(st, "t_orders", 0, "2020-01-01 08:00:00", "seed");
                }

                ChangeEventQueueSink sink = new ChangeEventQueueSink(producer, topic);
                DatabasePollingCDCConnector connector = pushConnector("it-bridge", db, "t_orders");
                connector.setEventListener(new CDCEventListener() {
                    @Override
                    public void onEvents(String connectorName, List<ChangeEvent> events) {
                        for (ChangeEvent event : events) {
                            try {
                                sink.invoke(event);
                            } catch (Exception e) {
                                throw new IllegalStateException("bridge delivery failed", e);
                            }
                        }
                    }
                });
                manager.addConnector(connector);
                try {
                    manager.start().get(30, TimeUnit.SECONDS);
                    try (Statement st = keeper.createStatement()) {
                        insert(st, "t_orders", 1, "2020-01-01 08:00:01", "bo1");
                        insert(st, "t_orders", 2, "2020-01-01 08:00:02", "bo2");
                    }

                    assertTrue(delivered.await(20, TimeUnit.SECONDS),
                            "both CDC events must reach the Redis MQ topic through the bridge");
                    assertEquals(Set.of("bo1", "bo2"), Set.copyOf(bridgedItems),
                            "each changed row must arrive with its own after-image");
                    assertEquals(2, Set.copyOf(bridgedKeys).size(),
                            "the MQ partition key must be distinct per row (ordering key)");
                } finally {
                    manager.stop().get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            if (consumer != null) {
                consumer.stop();
                consumer.close();
            }
            if (redis != null) {
                redis.shutdown();
            }
        }
    }

    /* ---------- helpers ---------- */

    /**
     * Connects to the integration Redis, or aborts (skips) the test when none is reachable.
     * Redisson connects eagerly, so a failed {@code create} is a reliable reachability probe.
     */
    private static RedissonClient requireRedis() {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        try {
            Config config = new Config();
            config.useSingleServer().setAddress(url);
            return Redisson.create(config);
        } catch (Throwable e) {
            Assumptions.abort("no Redis reachable at " + url
                    + " (start docker-compose.test.yml or point REDIS_URL at a running Redis): "
                    + e.getMessage());
            return null; // not reached: abort always throws
        }
    }

    /** Payloads cross the Redis stream as a map (or its JSON form); normalize to a map. */
    private static Map<?, ?> payloadAsMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return map;
        }
        try {
            return new ObjectMapper().readValue(String.valueOf(payload), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String h2() {
        return "jdbc:h2:mem:cdcmgr" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(db, "sa", "");
    }

    /** Pull-mode connector ({@code pollingIntervalMs=0}): events surface via manager {@code pollAll()}. */
    private static DatabasePollingCDCConnector pullConnector(String name, String db, String table) {
        return connector(name, db, table, 0);
    }

    /** Push-mode connector (background scheduler delivers batches to {@code onEvents}). */
    private static DatabasePollingCDCConnector pushConnector(String name, String db, String table) {
        return connector(name, db, table, 100);
    }

    private static DatabasePollingCDCConnector connector(String name, String db, String table, int pollMs) {
        return new DatabasePollingCDCConnector(CDCConfigurationBuilder.forDatabasePolling(name)
                .username("sa").password("")
                .batchSize(50)
                .pollingIntervalMs(pollMs)
                .property("jdbc.url", db)
                .property("driver.class", "org.h2.Driver")
                .property("tables", table)
                .property("timestamp.column", "updated_at")
                .build());
    }

    private static void insert(Statement st, String table, int id, String ts, String value) throws SQLException {
        st.execute("INSERT INTO " + table + "(id, updated_at, item) VALUES (" + id
                + ", TIMESTAMP '" + ts + "', '" + value + "')");
    }

    /** Manager-level poll drain: repeated {@code pollAll()} until the connector yields {@code min} events. */
    private static List<ChangeEvent> drainPoll(CDCManager manager, String connector, int min, long timeoutMs)
            throws Exception {
        List<ChangeEvent> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (out.size() < min && System.currentTimeMillis() < deadline) {
            out.addAll(manager.pollAll().getOrDefault(connector, List.of()));
            if (out.size() < min) {
                Thread.sleep(50);
            }
        }
        return out;
    }

    private static boolean awaitAll(Map<String, CountDownLatch> latches, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (CountDownLatch latch : latches.values()) {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            if (!latch.await(remaining, TimeUnit.MILLISECONDS)) {
                return false;
            }
        }
        return true;
    }

    private static boolean awaitSize(List<?> list, int size, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return list.size() >= size;
    }
}
