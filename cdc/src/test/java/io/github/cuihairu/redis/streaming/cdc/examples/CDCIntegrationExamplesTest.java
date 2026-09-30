package io.github.cuihairu.redis.streaming.cdc.examples;

import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test examples for the CDC connectors, wired to the shared test environment
 * ({@code docker-compose.test.yml}) through the standard environment variables:
 * {@code MYSQL_URL}/{@code MYSQL_USER}/{@code MYSQL_PASSWORD} and
 * {@code POSTGRES_URL}/{@code POSTGRES_USER}/{@code POSTGRES_PASSWORD}.
 *
 * <p>Guarded, not disabled: each test skips with a clear message when its service is not
 * configured. The MySQL binlog examples additionally require the {@code REPLICATION SLAVE}
 * privilege (the compose {@code test_user} does not have it by default — grant with
 * {@code GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'test_user'@'%'}); when the
 * privilege is missing the binlog examples skip, and the manager example falls back to a
 * second polling connector so the multi-connector management aspect stays exercised.</p>
 *
 * <p>Unlike their earlier log-only form, the examples now assert real behavior: rows written
 * after connector start are captured, positions can be committed, and the manager reports
 * health and metrics for every connector.</p>
 */
@Slf4j
@Tag("integration")
class CDCIntegrationExamplesTest {

    /* ---------- environment plumbing ---------- */

    private static String mysqlUrl() {
        return System.getenv().getOrDefault("MYSQL_URL", "jdbc:mysql://localhost:3306/test_db");
    }

    private static String mysqlUser() {
        return System.getenv().getOrDefault("MYSQL_USER", "test_user");
    }

    private static String mysqlPassword() {
        return System.getenv().getOrDefault("MYSQL_PASSWORD", "test_password");
    }

    private static String pgUrl() {
        return System.getenv().getOrDefault("POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/test_db");
    }

    private static String pgUser() {
        return System.getenv().getOrDefault("POSTGRES_USER", "test_user");
    }

    private static String pgPassword() {
        return System.getenv().getOrDefault("POSTGRES_PASSWORD", "test_password");
    }

    /** {@code jdbc:mysql://host[:port]/db} → host part (binlog replication is server-wide). */
    private static String mysqlHost() {
        return urlPart(mysqlUrl(), "^jdbc:mysql://", 1, "mysql host");
    }

    /** Port is optional in JDBC URLs; the driver default applies when absent. */
    private static int mysqlPort() {
        return Integer.parseInt(urlPart(mysqlUrl(), "^jdbc:mysql://", 2, "mysql port"));
    }

    private static String pgHost() {
        return urlPart(pgUrl(), "^jdbc:postgresql://", 1, "postgres host");
    }

    private static int pgPort() {
        return Integer.parseInt(urlPart(pgUrl(), "^jdbc:postgresql://", 2, "postgres port"));
    }

    private static String pgDatabase() {
        return pgUrl().replaceFirst("^jdbc:postgresql://[^/]+/", "").split("[?]")[0];
    }

    /**
     * Extracts {@code group} of {@code jdbc:SCHEME//host[:port]/...}; group 2 (the port)
     * falls back to {@code group1Default} when the URL omits it.
     */
    private static String urlPart(String url, String scheme, int group, int portDefault, String what) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(scheme + "([^/:?]+)(?::(\\d+))?(?:/|$)").matcher(url);
        if (!m.find()) {
            throw new IllegalStateException("Cannot parse " + what + " from JDBC URL: " + url);
        }
        String value = group == 2 ? m.group(2) : m.group(1);
        if (value == null) {
            value = String.valueOf(portDefault);
        }
        return value;
    }

    private static String urlPart(String url, String scheme, int group, String what) {
        return urlPart(url, scheme, group, -1, what);
    }

    /** TCP reachability probe — the guard for "environment not up" (skip), not auth problems. */
    private static void requireReachable(String host, int port, String service) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable at " + host + ":" + port
                    + " (start docker-compose.test.yml or point the *_URL env at a running instance)"
                    + " - skipping example");
        }
    }

    private static void assumeMysql() {
        requireReachable(mysqlHost(), mysqlPort(), "MySQL");
    }

    private static void assumePostgres() {
        requireReachable(pgHost(), pgPort(), "PostgreSQL");
    }

    /**
     * Binlog replication needs the global REPLICATION SLAVE privilege (renamed
     * REPLICATION REPLICA in MySQL 8.4+); {@code ALL PRIVILEGES ON *.*} implies it.
     * Absent by default for the compose {@code test_user}.
     */
    private static boolean mysqlHasReplicationPrivilege() {
        try (Connection c = DriverManager.getConnection(mysqlUrl(), mysqlUser(), mysqlPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW GRANTS")) {
            while (rs.next()) {
                String grant = rs.getString(1).toUpperCase();
                if (grant.contains("REPLICATION SLAVE") || grant.contains("REPLICATION REPLICA")
                        || (grant.contains("ALL PRIVILEGES") && grant.contains("ON *.*"))) {
                    return true;
                }
            }
        } catch (SQLException e) {
            return false;
        }
        return false;
    }

    private static void assumeMysqlReplication() {
        Assumptions.assumeTrue(mysqlHasReplicationPrivilege(),
                "MySQL user lacks REPLICATION SLAVE - grant it or use the polling examples");
    }

    private static void sql(String url, String user, String password, String... statements) {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement st = c.createStatement()) {
            for (String s : statements) {
                st.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("setup/teardown SQL failed: " + e.getMessage(), e);
        }
    }

    /** Polls until {@code min} events accumulated or the deadline passes. */
    private static List<ChangeEvent> drain(CDCConnector connector, int min, long timeoutMs)
            throws InterruptedException {
        List<ChangeEvent> out = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (out.size() < min && System.currentTimeMillis() < deadline) {
            List<ChangeEvent> batch = connector.poll();
            out.addAll(batch);
            if (!batch.isEmpty()) {
                String position = connector.getCurrentPosition();
                if (position != null) {
                    connector.commit(position);
                }
            } else {
                Thread.sleep(100);
            }
        }
        return out;
    }

    private static void assertInsertsFrom(String table, List<ChangeEvent> events, int expected) {
        assertTrue(events.size() >= expected,
                "expected at least " + expected + " captured events, got " + events.size());
        for (ChangeEvent event : events) {
            assertEquals(table, event.getTable());
            assertEquals(ChangeEvent.EventType.INSERT, event.getEventType());
        }
    }

    /* ---------- examples ---------- */

    @Test
    void testMySQLBinlogCDCExample() throws InterruptedException {
        assumeMysql();
        assumeMysqlReplication();

        sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                "DROP TABLE IF EXISTS cdc_binlog_demo",
                "CREATE TABLE cdc_binlog_demo(id INT PRIMARY KEY, item VARCHAR(64))");

        // Configure MySQL binlog CDC connector against the test environment
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql_example")
                .username(mysqlUser())
                .password(mysqlPassword())
                .mysqlHostname(mysqlHost())
                .mysqlPort(mysqlPort())
                .mysqlServerId(17401)
                .batchSize(10)
                .build();

        CDCConnector connector = CDCConnectorFactory.createMySQLBinlog(config);
        try {
            connector.start().join();
            log.info("MySQL binlog CDC connector started successfully");

            // rows written after start must be captured as INSERT events
            sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                    "INSERT INTO cdc_binlog_demo VALUES (1, 'b1')",
                    "INSERT INTO cdc_binlog_demo VALUES (2, 'b2')",
                    "INSERT INTO cdc_binlog_demo VALUES (3, 'b3')");

            List<ChangeEvent> events = drain(connector, 3, 30_000);
            assertInsertsFrom("cdc_binlog_demo", events, 3);
            log.info("Binlog captured {} events, last committed position: {}",
                    events.size(), connector.getCurrentPosition());
        } finally {
            connector.stop().join();
            sql(mysqlUrl(), mysqlUser(), mysqlPassword(), "DROP TABLE IF EXISTS cdc_binlog_demo");
            log.info("MySQL binlog CDC connector stopped");
        }
    }

    @Test
    void testPostgreSQLLogicalReplicationExample() throws InterruptedException {
        assumePostgres();

        // the connector creates its own publication; the table only needs a primary key
        sql(pgUrl(), pgUser(), pgPassword(),
                "DROP TABLE IF EXISTS cdc_pg_demo",
                "CREATE TABLE cdc_pg_demo(id INT PRIMARY KEY, item VARCHAR(64))");

        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg_example")
                .username(pgUser())
                .password(pgPassword())
                .postgresqlHostname(pgHost())
                .postgresqlPort(pgPort())
                .postgresqlDatabase(pgDatabase())
                .postgresqlSlotName("cdc_slot")
                .postgresqlPublicationName("cdc_publication")
                .postgresqlStatusInterval(1000)
                .batchSize(10)
                .build();

        CDCConnector connector = CDCConnectorFactory.createPostgreSQLLogicalReplication(config);
        try {
            connector.start().join();
            log.info("PostgreSQL logical replication CDC connector started");

            sql(pgUrl(), pgUser(), pgPassword(),
                    "INSERT INTO cdc_pg_demo VALUES (1, 'p1')",
                    "INSERT INTO cdc_pg_demo VALUES (2, 'p2')");

            List<ChangeEvent> events = drain(connector, 2, 30_000);
            assertInsertsFrom("cdc_pg_demo", events, 2);
        } finally {
            connector.stop().join();
            sql(pgUrl(), pgUser(), pgPassword(),
                    "DROP PUBLICATION IF EXISTS cdc_publication",
                    "DROP TABLE IF EXISTS cdc_pg_demo",
                    "SELECT pg_drop_replication_slot('cdc_slot') WHERE EXISTS"
                            + " (SELECT 1 FROM pg_replication_slots WHERE slot_name = 'cdc_slot')");
            log.info("PostgreSQL CDC connector stopped");
        }
    }

    @Test
    void testDatabasePollingCDCExample() throws InterruptedException {
        assumeMysql();

        // a baseline row present before start is skipped; rows after start are captured
        sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                "DROP TABLE IF EXISTS cdc_poll_demo",
                "CREATE TABLE cdc_poll_demo(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))",
                "INSERT INTO cdc_poll_demo VALUES (0, NOW() - INTERVAL 1 DAY, 'baseline')");

        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("polling_example")
                .username(mysqlUser())
                .password(mysqlPassword())
                .jdbcUrl(mysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("cdc_poll_demo")
                .timestampColumn("updated_at")
                .batchSize(20)
                .pollingIntervalMs(200)
                .build();

        CDCConnector connector = CDCConnectorFactory.createDatabasePolling(config);
        try {
            connector.start().join();
            log.info("Database polling CDC connector started, baseline row skipped");

            sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                    "INSERT INTO cdc_poll_demo VALUES (1, NOW(), 'live1')",
                    "INSERT INTO cdc_poll_demo VALUES (2, NOW(), 'live2')");

            List<ChangeEvent> events = drain(connector, 2, 15_000);
            assertInsertsFrom("cdc_poll_demo", events, 2);
        } finally {
            connector.stop().join();
            sql(mysqlUrl(), mysqlUser(), mysqlPassword(), "DROP TABLE IF EXISTS cdc_poll_demo");
            log.info("Database polling CDC connector stopped");
        }
    }

    @Test
    void testCDCManagerExample() throws InterruptedException {
        assumeMysql();

        CDCManager manager = new CDCManager();
        try {
            // primary connector: binlog when the privilege exists, otherwise a second
            // polling connector — the managed multi-connector lifecycle is what's exercised
            final String managedTable;
            if (mysqlHasReplicationPrivilege()) {
                managedTable = "cdc_mgr_binlog";
                sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                        "DROP TABLE IF EXISTS " + managedTable,
                        "CREATE TABLE " + managedTable + "(id INT PRIMARY KEY, item VARCHAR(64))");
                manager.addConnector(CDCConnectorFactory.create(
                        CDCConnectorFactory.ConnectorType.MYSQL_BINLOG,
                        CDCConfigurationBuilder.forMySQLBinlog("mysql_mgr")
                                .username(mysqlUser())
                                .password(mysqlPassword())
                                .mysqlHostname(mysqlHost())
                                .mysqlPort(mysqlPort())
                                .mysqlServerId(17402)
                                .build()));
            } else {
                managedTable = "cdc_mgr_poll2";
                sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                        "DROP TABLE IF EXISTS " + managedTable,
                        "CREATE TABLE " + managedTable
                                + "(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))",
                        "INSERT INTO " + managedTable
                                + " VALUES (0, NOW() - INTERVAL 1 DAY, 'baseline')");
                manager.addConnector(CDCConnectorFactory.create(
                        CDCConnectorFactory.ConnectorType.DATABASE_POLLING,
                        CDCConfigurationBuilder.forDatabasePolling("polling_mgr2")
                                .username(mysqlUser())
                                .password(mysqlPassword())
                                .jdbcUrl(mysqlUrl())
                                .driverClass("com.mysql.cj.jdbc.Driver")
                                .tables(managedTable)
                                .timestampColumn("updated_at")
                                .pollingIntervalMs(200)
                                .build()));
            }

            // second connector: timestamp polling (works for any MySQL user)
            sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                    "DROP TABLE IF EXISTS cdc_mgr_poll",
                    "CREATE TABLE cdc_mgr_poll(id INT PRIMARY KEY, updated_at TIMESTAMP, item VARCHAR(64))",
                    "INSERT INTO cdc_mgr_poll VALUES (0, NOW() - INTERVAL 1 DAY, 'baseline')");
            manager.addConnector(CDCConnectorFactory.create(
                    CDCConnectorFactory.ConnectorType.DATABASE_POLLING,
                    CDCConfigurationBuilder.forDatabasePolling("polling_mgr")
                            .username(mysqlUser())
                            .password(mysqlPassword())
                            .jdbcUrl(mysqlUrl())
                            .driverClass("com.mysql.cj.jdbc.Driver")
                            .tables("cdc_mgr_poll")
                            .timestampColumn("updated_at")
                            .pollingIntervalMs(200)
                            .build()));

            assertEquals(2, manager.getConnectorCount());

            manager.start().join();
            assertEquals(2, manager.getRunningConnectorCount());

            // rows after start surface through the manager with health and metrics
            sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                    "INSERT INTO " + managedTable + (managedTable.endsWith("binlog")
                            ? " VALUES (1, 'm1')" : " VALUES (1, NOW(), 'm1')"),
                    "INSERT INTO cdc_mgr_poll VALUES (1, NOW(), 'm2')");

            List<ChangeEvent> managedEvents = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (managedEvents.size() < 1 && System.currentTimeMillis() < deadline) {
                Map<String, List<ChangeEvent>> all = manager.pollAll();
                managedEvents.addAll(all.getOrDefault("polling_mgr", List.of()));
                if (managedEvents.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            assertFalse(managedEvents.isEmpty(), "the polling connector must capture its row via the manager");
            for (ChangeEvent event : managedEvents) {
                assertEquals("cdc_mgr_poll", event.getTable());
            }

            Map<String, CDCHealthStatus> health = manager.getHealthStatusAll();
            assertEquals(2, health.size());
            health.forEach((name, status) -> assertNotNull(status.getStatus(), "health of " + name));

            Map<String, CDCMetrics> metrics = manager.getMetricsAll();
            assertEquals(2, metrics.size());

            sql(mysqlUrl(), mysqlUser(), mysqlPassword(),
                    "DROP TABLE IF EXISTS " + managedTable,
                    "DROP TABLE IF EXISTS cdc_mgr_poll");
        } finally {
            manager.stop().join();
            log.info("CDC manager stopped");
        }
    }
}
