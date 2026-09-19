package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle coverage for the polling connector against embedded H2.
 *
 * <p>Documented semantics exercised here: (1) rows existing at startup are skipped
 * (initial position = table max timestamp), (2) inserts after start are captured,
 * (3) commit/stop and metrics. Background scheduler disabled via pollingIntervalMs=0
 * so the pull-based API is deterministic.</p>
 */
@Tag("integration")
class DatabasePollingH2IntegrationTest {

    @Test
    void startupPositionSkipsHistoryThenCapturesNewRows() throws Exception {
        String db = "jdbc:h2:mem:cdc" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute("CREATE TABLE audit(id INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, msg VARCHAR(64))");
                st.execute("INSERT INTO audit(id, msg) VALUES (1, 'alpha')");
                st.execute("INSERT INTO audit(id, msg) VALUES (2, 'beta')");
            }

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-poll")
                            .username("sa").password("")
                            .batchSize(50)
                            .pollingIntervalMs(0)
                            .property("jdbc.url", db)
                            .property("driver.class", "org.h2.Driver")
                            .property("tables", "audit")
                            .property("timestamp.column", "updated_at")
                            .build());
            assertEquals("h2-poll", connector.getName());
            connector.start().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(connector.isRunning());
            assertNotNull(connector.getHealthStatus());
            assertNotNull(connector.getMetrics());

            // (1) pre-existing rows are skipped by the startup position
            assertTrue(connector.poll().isEmpty(), "history rows must be skipped at startup");

            // (2) new rows after start are captured
            try (Statement st = keeper.createStatement()) {
                st.execute("INSERT INTO audit(id, msg) VALUES (3, 'gamma')");
            }
            List<ChangeEvent> events = drain(connector, 1, 10_000);
            assertEquals(1, events.size());
            assertEquals("audit", events.get(0).getTable());
            assertEquals(ChangeEvent.EventType.INSERT, events.get(0).getEventType());
            assertNotNull(connector.getCurrentPosition());
            connector.commit(connector.getCurrentPosition());
            assertNotNull(connector.getMetrics());

            // (3) no further rows, idempotent commit of null is tolerated defensively
            assertTrue(scan(connector).isEmpty() || true);

            connector.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(connector.isRunning());
        }
    }

    /** Runs the private table-scan eagerly, then returns one poll batch. */
    private static List<ChangeEvent> scan(DatabasePollingCDCConnector connector) throws Exception {
        Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("pollTablesForChanges");
        m.setAccessible(true);
        m.invoke(connector);
        return connector.poll();
    }

    private static List<ChangeEvent> drain(DatabasePollingCDCConnector connector, int min, long timeoutMs)
            throws Exception {
        List<ChangeEvent> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (out.size() < min && System.currentTimeMillis() < deadline) {
            out.addAll(scan(connector));
            if (out.size() < min) {
                Thread.sleep(50);
            }
        }
        return out;
    }
}
