package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full lifecycle coverage for the polling connector against an embedded H2 database:
 * initial snapshot, incremental capture, position commit, health/metrics and shutdown.
 */
@Tag("integration")
class DatabasePollingH2IntegrationTest {

    @Test
    void snapshotIncrementalCommitAndLifecycle() throws Exception {
        String db = "jdbc:h2:mem:cdc" + UUID.randomUUID().toString().substring(0, 8) + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(db, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE audit(id INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, msg VARCHAR(64))");
            st.execute("INSERT INTO audit(id, msg) VALUES (1, 'alpha')");
            st.execute("INSERT INTO audit(id, msg) VALUES (2, 'beta')");
        }

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-poll")
                        .username("sa").password("")
                        .batchSize(50)
                        .pollingIntervalMs(150)
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

        // snapshot events
        List<ChangeEvent> snapshot = drain(connector, 2, 15_000);
        assertTrue(snapshot.size() >= 2, "expected snapshot rows, got " + snapshot.size());
        assertEquals("audit", snapshot.get(0).getTable());

        // incremental event
        try (Connection c = DriverManager.getConnection(db, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit(id, msg) VALUES (3, 'gamma')");
        }
        List<ChangeEvent> incremental = drain(connector, 1, 15_000);
        assertFalse(incremental.isEmpty(), "poller should capture the new row");
        connector.commit(connector.getCurrentPosition());

        connector.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(connector.isRunning());
    }

    private static List<ChangeEvent> drain(DatabasePollingCDCConnector connector, int min, long timeoutMs)
            throws InterruptedException {
        java.util.List<ChangeEvent> out = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (out.size() < min && System.currentTimeMillis() < deadline) {
            out.addAll(connector.poll());
            if (out.size() < min) {
                Thread.sleep(100);
            }
        }
        return out;
    }
}
