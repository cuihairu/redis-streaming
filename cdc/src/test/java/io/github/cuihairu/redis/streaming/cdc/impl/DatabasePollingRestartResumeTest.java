package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: stopping the polling connector used to clear the undelivered queue and
 * re-baseline at MAX(col) on restart, silently dropping everything scanned-but-not-
 * delivered plus everything inserted while stopped.
 */
class DatabasePollingRestartResumeTest {

    private static String newDb() {
        return "jdbc:h2:mem:resume" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String msg(ChangeEvent e) {
        // H2 reports unquoted identifiers in upper case
        Object v = e.getAfterData().get("msg");
        return String.valueOf(v != null ? v : e.getAfterData().get("MSG"));
    }

    @Test
    void restartPreservesUndeliveredEventsAndPosition() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE audit(id INT PRIMARY KEY, updated_at TIMESTAMP, msg VARCHAR(64))");

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-resume")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.AUDIT")
                            .timestampColumn("updated_at")
                            .build());

            connector.start().get(10, TimeUnit.SECONDS);

            exec(keeper, "INSERT INTO audit VALUES (1, TIMESTAMP '2024-01-01 00:00:01', 'a')");
            assertTrue(connector.poll().isEmpty(), "first poll scans and fills the queue");
            List<ChangeEvent> first = connector.poll();
            assertEquals(1, first.size());
            assertEquals("a", msg(first.get(0)));

            exec(keeper, "INSERT INTO audit VALUES (2, TIMESTAMP '2024-01-01 00:00:02', 'b')");
            assertTrue(connector.poll().isEmpty(), "poll drains before scanning: b is queued but undelivered");

            connector.stop().get(10, TimeUnit.SECONDS);
            exec(keeper, "INSERT INTO audit VALUES (3, TIMESTAMP '2024-01-01 00:00:03', 'c')");

            connector.start().get(10, TimeUnit.SECONDS);
            List<String> received = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (received.size() < 2 && System.currentTimeMillis() < deadline) {
                for (ChangeEvent e : connector.poll()) {
                    received.add(msg(e));
                }
                if (received.size() < 2) {
                    Thread.sleep(50);
                }
            }
            assertEquals(List.of("b", "c"), received,
                    "undelivered row b and row inserted while stopped must both survive a restart");

            connector.stop().get(10, TimeUnit.SECONDS);
        }
    }
}
