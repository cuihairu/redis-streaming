package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCConnector;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.CDCHealthStatus;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Position-offset (位点) integration tests: after a connector stops, its recorded watermark
 * (MySQL binlog file:position, PostgreSQL LSN) must be a usable resume point — a restarted
 * connector continues from there instead of re-reading or skipping the outage window.
 *
 * <p>Requires a MySQL 8 container with ROW binlog (e.g. the compose test MySQL) and a
 * PostgreSQL with {@code wal_level=logical}; skipped cleanly otherwise, so the default
 * unit build never needs the containers.
 */
@Tag("integration")
class CDCPositionResumeIntegrationTest {

    // ------------------------------------------------------------------ MySQL

    @Test
    void mySqlResumesFromTheRecordedBinlogWatermarkAfterRestart() throws Exception {
        String host = host("MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/test_db");
        int port = port("MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/test_db", 3306);
        String rootPassword = System.getenv().getOrDefault("MYSQL_ROOT_PASSWORD", "test_password");
        String adminUrl = String.format("jdbc:mysql://%s:%d/test_db?allowPublicKeyRetrieval=true&useSSL=false", host, port);
        Assumptions.assumeTrue(mysqlTestDatabaseAvailable(adminUrl, rootPassword),
                "MySQL test database not usable on " + host + ":" + port);

        String table = "h3r_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        long serverId = 250_000L + ThreadLocalRandom.current().nextLong(9_000);

        try (Connection ddl = DriverManager.getConnection(adminUrl, "root", rootPassword)) {
            exec(ddl, "CREATE TABLE `" + table + "` (id INT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(64))");
            // marker row: because the binlog watermark only advances on XID (commit) events,
            // reading it right after the last captured event can sit before that event's
            // commit — replaying the boundary row. Flushing one extra row first pins the
            // watermark strictly AFTER the pre-stop transaction, making the no-replay
            // assertion deterministic rather than racy (the marker itself may replay once).
            exec(ddl, "CREATE TABLE IF NOT EXISTS h3r_marker (id INT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(64))");
            String[] current = currentBinlogCoordinates(ddl);
            String startFile = current[0];
            long startPos = Long.parseLong(current[1]);

            MySQLBinlogCDCConnector first = mysqlConnector("h3-resume-1", host, port, rootPassword,
                    serverId, startFile, startPos);
            List<ChangeEvent> events = new CopyOnWriteArrayList<>();
            List<String> errors = new CopyOnWriteArrayList<>();
            Thread driver = null;
            try {
                first.setEventListener(new CDCEventListener() {
                    @Override
                    public void onEvents(String connectorName, List<ChangeEvent> captured) {
                        events.addAll(captured);
                    }

                    @Override
                    public void onConnectorError(String connectorName, Throwable t) {
                        errors.add(String.valueOf(t));
                    }
                });
                first.start().get(30, TimeUnit.SECONDS);
                assertTrue(await(() -> first.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY, 20_000),
                        "first connector must reach HEALTHY — " + first.getHealthStatus().getMessage());
                driver = startPollDriver(first, events);

                exec(ddl, "INSERT INTO `" + table + "` (v) VALUES ('before-stop')");
                assertTrue(await(() -> count(events, "before-stop") >= 1, 20_000),
                        "the pre-stop insert must be captured, events=" + dump(events));

                // pin the watermark past the pre-stop transaction's commit
                exec(ddl, "INSERT INTO h3r_marker (v) VALUES ('marker-1')");
                assertTrue(await(() -> count(events, "marker-1") >= 1, 20_000),
                        "the marker flush must be captured, events=" + dump(events)
                                + ", health=" + first.getHealthStatus()
                                + ", pos=" + first.getCurrentPosition()
                                + ", errors=" + errors);

                // record the live watermark, then stop
                String watermark = first.getCurrentPosition();
                assertTrue(watermark != null && watermark.contains(":"));
                first.stop().get(30, TimeUnit.SECONDS);

                // changes written while NO connector is running
                exec(ddl, "INSERT INTO `" + table + "` (v) VALUES ('while-stopped')");

                // a fresh connector resumes from the recorded watermark
                String file = watermark.substring(0, watermark.indexOf(':'));
                long pos = Long.parseLong(watermark.substring(watermark.indexOf(':') + 1));
                assertTrue(pos >= startPos, "the watermark must not be behind the start position");
                MySQLBinlogCDCConnector second = mysqlConnector("h3-resume-2", host, port, rootPassword,
                        serverId + 1, file, pos);
                try {
                    second.setEventListener(deliveryCollector(second, events));
                    second.start().get(30, TimeUnit.SECONDS);
                    assertTrue(await(() -> second.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY, 20_000),
                            "second connector must reach HEALTHY — " + second.getHealthStatus().getMessage());
                    driver.interrupt();
                    driver.join(5_000);
                    driver = startPollDriver(second, events);

                    exec(ddl, "INSERT INTO `" + table + "` (v) VALUES ('after-resume')");
                    assertTrue(await(() -> count(events, "after-resume") >= 1, 20_000),
                            "the post-resume insert must be captured");
                    assertTrue(count(events, "while-stopped") >= 1,
                            "the outage window must be replayed from the recorded watermark (at-least-once)");
                    // exactly the original pre-stop capture: no re-delivery after the
                    // restart from the recorded watermark (shared list across both phases)
                    assertEquals(1, count(events, "before-stop"),
                            "changes before the watermark must NOT be re-delivered");
                } finally {
                    second.stop().get(30, TimeUnit.SECONDS);
                }
            } finally {
                if (driver != null) {
                    driver.interrupt();
                }
                first.stop().get(30, TimeUnit.SECONDS);
                exec(ddl, "DROP TABLE IF EXISTS `" + table + "`");
                exec(ddl, "DROP TABLE IF EXISTS h3r_marker");
            }
        }
    }

    private static MySQLBinlogCDCConnector mysqlConnector(String name, String host, int port,
                                                          String password, long serverId,
                                                          String file, long position) {
        CDCConfigurationBuilder builder = CDCConfigurationBuilder.forMySQLBinlog(name)
                .mysqlHostname(host)
                .mysqlPort(port)
                .username("root")
                .password(password)
                .mysqlServerId(serverId)
                // pure pull mode: the test's poll driver is the only queue consumer, so the
                // internal push scheduler must not compete with it for batches
                .pollingIntervalMs(0)
                .property("connect.timeout.ms", 5_000)
                .property("reconnect.backoff.initial.ms", 500)
                .property("reconnect.backoff.max.ms", 2_000);
                // schema.resolve.columns stays at its default (true) so afterData keys are
                // the real column names — resolvable here via information_schema as root
        if (file != null) {
            builder.property("binlog.filename", file);
            builder.property("binlog.position", String.valueOf(position));
        }
        return new MySQLBinlogCDCConnector(builder.build());
    }

    private static String[] currentBinlogCoordinates(Connection ddl) throws SQLException {
        try (Statement st = ddl.createStatement();
             ResultSet rs = st.executeQuery("SHOW MASTER STATUS")) {
            assertTrue(rs.next(), "SHOW MASTER STATUS must return the current binlog coordinates");
            return new String[]{rs.getString("File"), String.valueOf(rs.getLong("Position"))};
        }
    }

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    void pgSlotRetainsAndReplaysChangesAcrossRestart() throws Exception {
        String url = System.getenv().getOrDefault("POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/test_db");
        String host = host("POSTGRES_URL", url);
        int port = port("POSTGRES_URL", url, 5432);
        String database = database("POSTGRES_URL", url, "test_db");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "test_user");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "test_password");
        String ddlUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        Assumptions.assumeTrue(postgresLogicalAvailable(ddlUrl, user, password),
                "PostgreSQL test database with wal_level=logical not usable on " + host + ":" + port);

        String table = "h3r_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        String slot = "h3r_slot_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));

        try (Connection ddl = DriverManager.getConnection(ddlUrl, user, password)) {
            // TEXT (not VARCHAR): the test_decoding payload for "character varying" contains an
            // unquoted space inside the [type] tag, which the payload tokenizer splits on
            // (known parser quirk, documented in the module notes) — TEXT parses cleanly
            exec(ddl, "CREATE TABLE " + table + " (id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY, v TEXT)");

            PostgreSQLLogicalReplicationCDCConnector first = pgConnector("h3r-pg-1", host, port, database,
                    user, password, slot);
            List<ChangeEvent> events = new CopyOnWriteArrayList<>();
            Thread driver = null;
            try {
                first.setEventListener(deliveryCollector(first, events));
                first.start().get(30, TimeUnit.SECONDS);
                assertTrue(await(() -> first.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY, 20_000),
                        "first connector must reach HEALTHY — " + first.getHealthStatus().getMessage());
                driver = startPollDriver(first, events);

                exec(ddl, "INSERT INTO " + table + " (v) VALUES ('before-stop')");
                assertTrue(await(() -> count(events, "before-stop") >= 1, 20_000),
                        "the pre-stop insert must be captured, events=" + dump(events));
                String lsnAtStop = first.getCurrentLSN();
                assertNotNull(lsnAtStop, "a running stream must have a current LSN");
                first.stop().get(30, TimeUnit.SECONDS);

                // written while the connector is down: the slot retains the WAL
                exec(ddl, "INSERT INTO " + table + " (v) VALUES ('while-stopped')");

                PostgreSQLLogicalReplicationCDCConnector second = pgConnector("h3r-pg-2", host, port,
                        database, user, password, slot);
                try {
                    second.setEventListener(deliveryCollector(second, events));
                    second.start().get(30, TimeUnit.SECONDS);
                    assertTrue(await(() -> second.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY, 20_000),
                            "second connector must reach HEALTHY — " + second.getHealthStatus().getMessage());
                    driver.interrupt();
                    driver.join(5_000);
                    driver = startPollDriver(second, events);

                    exec(ddl, "INSERT INTO " + table + " (v) VALUES ('after-resume')");
                    assertTrue(await(() -> count(events, "after-resume") >= 1, 20_000),
                            "the post-resume insert must be captured");
                    assertTrue(count(events, "while-stopped") >= 1,
                            "the slot must retain WAL written while no consumer was attached");
                } finally {
                    second.stop().get(30, TimeUnit.SECONDS);
                }
            } finally {
                if (driver != null) {
                    driver.interrupt();
                }
                first.stop().get(30, TimeUnit.SECONDS);
                try (Statement st = ddl.createStatement()) {
                    st.execute("SELECT pg_drop_replication_slot('" + slot + "')");
                } catch (Exception ignore) {
                    // best-effort cleanup — the container is ephemeral
                }
                exec(ddl, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static PostgreSQLLogicalReplicationCDCConnector pgConnector(String name, String host, int port,
                                                                        String database, String user,
                                                                        String password, String slot) {
        return new PostgreSQLLogicalReplicationCDCConnector(
                CDCConfigurationBuilder.forPostgreSQLLogicalReplication(name)
                        .postgresqlHostname(host)
                        .postgresqlPort(port)
                        .postgresqlDatabase(database)
                        .postgresqlSlotName(slot)
                        .postgresqlStatusInterval(1_000)
                        // pure pull mode: only the test's poll driver consumes the queue
                        .pollingIntervalMs(0)
                        .username(user)
                        .password(password)
                        .property("reconnect.backoff.ms", 500)
                        .build());
    }

    // ------------------------------------------------------------------ helpers

    /** Routes capture events into the shared list (push connectors are pull-consumed). */
    private static CDCEventListener deliveryCollector(CDCConnector connector,
                                                      List<ChangeEvent> events) {
        return new CDCEventListener() {
            @Override
            public void onEvents(String connectorName, List<ChangeEvent> captured) {
                events.addAll(captured);
            }
        };
    }

    private static long count(List<ChangeEvent> events, String v) {
        return events.stream()
                .filter(e -> e.getAfterData() != null && v.equals(String.valueOf(e.getAfterData().get("v"))))
                .count();
    }

    private static String dump(List<ChangeEvent> events) {
        StringBuilder sb = new StringBuilder("[");
        for (ChangeEvent e : events) {
            sb.append(e.getEventType()).append(' ').append(e.getFullTableName())
                    .append(" key=").append(e.getKey())
                    .append(" after=").append(e.getAfterData())
                    .append("; ");
        }
        return sb.append(']').toString();
    }

    /**
     * CDCManager-equivalent: drive {@code connector.poll()} from a daemon thread and collect
     * every event (counting keys off unique row values, so no table predicate is needed).
     */
    private static Thread startPollDriver(CDCConnector connector,
                                          List<ChangeEvent> sink) {
        Thread driver = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (ChangeEvent event : connector.poll()) {
                        if (event != null) {
                            sink.add(event);
                        }
                    }
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignore) {
                    // poll() reports its own failures through health/listeners
                }
            }
        }, "h3r-poll-driver-" + connector.getName());
        driver.setDaemon(true);
        driver.start();
        return driver;
    }

    private static boolean mysqlTestDatabaseAvailable(String adminUrl, String rootPassword) {
        try (Connection connection = DriverManager.getConnection(adminUrl, "root", rootPassword)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean postgresLogicalAvailable(String ddlUrl, String user, String password) {
        try (Connection connection = DriverManager.getConnection(ddlUrl, user, password);
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SHOW wal_level")) {
            return rs.next() && "logical".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }

    private static String host(String envVar, String defaultUrl) {
        String url = System.getenv().getOrDefault(envVar, defaultUrl);
        Matcher m = Pattern.compile("jdbc:\\w+://([^/:]+)").matcher(url);
        return m.find() ? m.group(1) : "127.0.0.1";
    }

    private static int port(String envVar, String defaultUrl, int fallback) {
        String url = System.getenv().getOrDefault(envVar, defaultUrl);
        Matcher m = Pattern.compile("jdbc:\\w+://[^/:]+:(\\d+)").matcher(url);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String database(String envVar, String defaultUrl, String fallback) {
        String url = System.getenv().getOrDefault(envVar, defaultUrl);
        Matcher m = Pattern.compile("jdbc:\\w+://[^/]+/(\\w+)").matcher(url.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : fallback;
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            boolean isResultSet = st.execute(sql);
            if (isResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    while (rs.next()) {
                        // consume
                    }
                }
            }
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
