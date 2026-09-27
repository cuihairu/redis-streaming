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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDC-H3 integration tests: a real upstream connection is cut underneath a live connector
 * (MySQL: the binlog dump thread is KILLed; PostgreSQL: the walsender is terminated), and the
 * connector must flip health to UNHEALTHY, then reconnect and resume delivering changes.
 *
 * <p>Both push connectors are pull-consumed (only {@code DatabasePollingCDCConnector} schedules
 * its own polling; {@code CDCManager} would drive {@code pollAll()} for these), so the tests run
 * a simple poll-driver thread — which is also what triggers the PG stream rebuild.
 *
 * <p>Skipped (via assumption) unless the database port is reachable, so the default unit
 * build never requires the containers.
 */
@Tag("integration")
class CDCDisconnectReconnectIntegrationTest {

    private static final long DETECT_TIMEOUT_MS = 20_000;
    private static final long RECOVER_TIMEOUT_MS = 20_000;
    private static final long EVENT_TIMEOUT_MS = 20_000;

    // ------------------------------------------------------------------ MySQL

    @Test
    void mySqlServerKillFlipsHealthAndReconnectsEndToEnd() throws Exception {
        String host = host("MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/test_db");
        int port = port("MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/test_db", 3306);
        String rootPassword = System.getenv().getOrDefault("MYSQL_ROOT_PASSWORD", "test_password");
        String adminUrl = String.format("jdbc:mysql://%s:%d/test_db?allowPublicKeyRetrieval=true&useSSL=false",
                host, port);
        Assumptions.assumeTrue(mysqlTestDatabaseAvailable(adminUrl, rootPassword),
                "MySQL test database not usable on " + host + ":" + port
                        + " (unreachable, or not the compose test container: root/test_password denied)");

        String table = "h3m_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        long serverId = 230_000L + ThreadLocalRandom.current().nextLong(9_000);
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("h3-it-mysql")
                        .mysqlHostname(host)
                        .mysqlPort(port)
                        .username("root")
                        .password(rootPassword)
                        .mysqlServerId(serverId)
                        .property("connect.timeout.ms", 5_000)
                        .property("reconnect.backoff.initial.ms", 500)
                        .property("reconnect.backoff.max.ms", 2_000)
                        .build());

        List<ChangeEvent> myTable = new CopyOnWriteArrayList<>();
        List<String> healthTransitions = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onHealthStatusChanged(String connectorName, CDCHealthStatus oldStatus,
                                              CDCHealthStatus newStatus) {
                healthTransitions.add(System.currentTimeMillis() + "ms: " + oldStatus.getStatus()
                        + " -> " + newStatus.getStatus() + " (" + newStatus.getMessage() + ")");
            }
        });

        try (Connection ddl = DriverManager.getConnection(adminUrl, "root", rootPassword)) {
            exec(ddl, "CREATE TABLE `" + table + "` (id INT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(64))");

            connector.start().get(30, TimeUnit.SECONDS);
            // the poll driver starts only after start() completes: running flips true before
            // doStart() finishes, and an early poll would race the binlog client setup
            Thread driver = startPollDriver(connector,
                    e -> e.getFullTableName() != null && e.getFullTableName().endsWith("." + table), myTable);
            try {
                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                        DETECT_TIMEOUT_MS),
                        "connector must reach HEALTHY after start — was: "
                                + connector.getHealthStatus().getMessage());
                assertTrue(connector.isConnected(), "the binlog stream must be live after start");

                exec(ddl, "INSERT INTO `" + table + "` (v) VALUES ('before-kill')");
                assertTrue(await(() -> insertCount(myTable) >= 1, EVENT_TIMEOUT_MS),
                        "the first insert must arrive before the connection is killed");

                // Cut the upstream: kill the binlog dump thread the connector is streaming from
                int killed = killBinlogDumpThreads(adminUrl, rootPassword);
                assertTrue(killed >= 1, "at least one binlog dump thread must have been killed");
                healthTransitions.add(System.currentTimeMillis() + "ms: binlog dump thread killed");

                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.UNHEALTHY,
                        DETECT_TIMEOUT_MS),
                        "the kill must be detectable through health (old code stayed HEALTHY forever)"
                                + " — transitions: " + healthTransitions);

                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                        RECOVER_TIMEOUT_MS),
                        "the connector must reconnect with backoff — was: "
                                + connector.getHealthStatus().getMessage() + "; transitions: " + healthTransitions);
                assertTrue(connector.isConnected(), "the binlog stream must be live again");

                exec(ddl, "INSERT INTO `" + table + "` (v) VALUES ('after-reconnect')");
                assertTrue(await(() -> insertCount(myTable) >= 2, EVENT_TIMEOUT_MS),
                        "changes written after the reconnect must be delivered (resume from the live watermark)");
            } finally {
                driver.interrupt();
            }
        } finally {
            connector.stop().get(30, TimeUnit.SECONDS);
            try (Connection ddl = DriverManager.getConnection(adminUrl, "root", rootPassword)) {
                exec(ddl, "DROP TABLE IF EXISTS `" + table + "`");
            } catch (Exception ignore) {
                // cleanup is best-effort — the container is ephemeral
            }
        }
    }

    private static int killBinlogDumpThreads(String adminUrl, String password) throws SQLException {
        try (Connection admin = DriverManager.getConnection(adminUrl, "root", password);
             Statement st = admin.createStatement()) {
            // collect first: executing KILL on the same statement would close the open ResultSet
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM information_schema.processlist WHERE command LIKE 'Binlog Dump%'")) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            for (long id : ids) {
                st.executeUpdate("KILL " + id);
            }
            return ids.size();
        }
    }

    // ------------------------------------------------------------------ PostgreSQL

    @Test
    void pgWalsenderKillFlipsHealthAndReconnectsEndToEnd() throws Exception {
        String url = System.getenv().getOrDefault("POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/test_db");
        String host = host("POSTGRES_URL", url);
        int port = port("POSTGRES_URL", url, 5432);
        String database = database("POSTGRES_URL", url, "test_db");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "test_user");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "test_password");
        String ddlUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        Assumptions.assumeTrue(postgresLogicalAvailable(ddlUrl, user, password),
                "PostgreSQL test database with wal_level=logical not usable on " + host + ":" + port
                        + " (unreachable, wrong credentials, or not configured for logical replication)");

        String table = "h3p_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        String slot = "h3_slot_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(
                CDCConfigurationBuilder.forPostgreSQLLogicalReplication("h3-it-pg")
                        .postgresqlHostname(host)
                        .postgresqlPort(port)
                        .postgresqlDatabase(database)
                        .postgresqlSlotName(slot)
                        .postgresqlStatusInterval(1_000)
                        .username(user)
                        .password(password)
                        .property("reconnect.backoff.ms", 500)
                        .build());

        List<ChangeEvent> myTable = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<String> healthTransitions = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(error);
            }

            @Override
            public void onHealthStatusChanged(String connectorName, CDCHealthStatus oldStatus,
                                              CDCHealthStatus newStatus) {
                healthTransitions.add(System.currentTimeMillis() + "ms: " + oldStatus.getStatus()
                        + " -> " + newStatus.getStatus() + " (" + newStatus.getMessage() + ")");
            }
        });

        try (Connection ddl = DriverManager.getConnection(ddlUrl, user, password)) {
            exec(ddl, "CREATE TABLE " + table + " (id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY, v VARCHAR(64))");

            connector.start().get(30, TimeUnit.SECONDS);
            // poll() also drives the CDC-H3 stream rebuild — this thread is the heartbeat.
            // Started only after start() completes: running flips true before doStart finishes,
            // and an early poll would race slot creation.
            Thread driver = startPollDriver(connector,
                    e -> e.getFullTableName() != null && e.getFullTableName().endsWith("." + table), myTable);
            try {
                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                        DETECT_TIMEOUT_MS),
                        "connector must reach HEALTHY after start — was: "
                                + connector.getHealthStatus().getMessage());
                assertTrue(connector.isStreamActive(), "the replication stream must be live after start");

                exec(ddl, "INSERT INTO " + table + " (v) VALUES ('before-kill')");
                assertTrue(await(() -> insertCount(myTable) >= 1, EVENT_TIMEOUT_MS),
                        "the first insert must arrive before the walsender is terminated");

                // Cut the upstream: terminate every walsender backend (only ours exists here)
                exec(ddl, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE backend_type = 'walsender'");
                healthTransitions.add(System.currentTimeMillis() + "ms: walsender terminated");

                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.UNHEALTHY,
                        DETECT_TIMEOUT_MS),
                        "the terminated stream must be detectable through health (old code stayed HEALTHY forever)"
                                + " — transitions: " + healthTransitions);
                assertTrue(errors.size() >= 1, "the stream failure must surface as a connector error");

                assertTrue(await(() -> connector.getHealthStatus().getStatus() == CDCHealthStatus.Status.HEALTHY,
                        RECOVER_TIMEOUT_MS),
                        "the next poll must rebuild the stream — was: " + connector.getHealthStatus().getMessage()
                                + "; transitions: " + healthTransitions);
                assertTrue(connector.isStreamActive(), "the replication stream must be live again");

                exec(ddl, "INSERT INTO " + table + " (v) VALUES ('after-reconnect')");
                assertTrue(await(() -> insertCount(myTable) >= 2, EVENT_TIMEOUT_MS),
                        "changes written after the reconnect must be delivered (resume at the last received LSN)");
            } finally {
                driver.interrupt();
            }
        } finally {
            connector.stop().get(30, TimeUnit.SECONDS);
            try (Connection ddl = DriverManager.getConnection(ddlUrl, user, password);
                 Statement st = ddl.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('" + slot + "')");
            } catch (Exception ignore) {
                // cleanup is best-effort — the container is ephemeral
            }
            try (Connection ddl = DriverManager.getConnection(ddlUrl, user, password)) {
                exec(ddl, "DROP TABLE IF EXISTS " + table);
            } catch (Exception ignore) {
                // same
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * CDCManager-equivalent: drive {@code connector.poll()} from a daemon thread and collect
     * events matching the predicate (the poll connectors deliver by return value, not by
     * listener, when consumed directly).
     */
    private static Thread startPollDriver(CDCConnector connector,
                                          Predicate<ChangeEvent> match,
                                          List<ChangeEvent> sink) {
        Thread driver = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (ChangeEvent event : connector.poll()) {
                        if (event != null && match.test(event)) {
                            sink.add(event);
                        }
                    }
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignore) {
                    // poll() already reports its own failures through health/listeners
                }
            }
        }, "h3-poll-driver-" + connector.getName());
        driver.setDaemon(true);
        driver.start();
        return driver;
    }

    /**
     * The port being open is not enough: any unrelated database may squat on it, so verify
     * the connection actually authenticates — otherwise a foreign MySQL would turn the
     * assumption skip into a hard failure.
     */
    private static boolean mysqlTestDatabaseAvailable(String adminUrl, String rootPassword) {
        try (Connection connection = DriverManager.getConnection(adminUrl, "root", rootPassword)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Verify credentials AND logical replication support (a plain database on the port is
     * useless for this test and would fail mid-run instead of skipping).
     */
    private static boolean postgresLogicalAvailable(String ddlUrl, String user, String password) {
        try (Connection connection = DriverManager.getConnection(ddlUrl, user, password);
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SHOW wal_level")) {
            return rs.next() && "logical".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }

    private static int insertCount(List<ChangeEvent> events) {
        return (int) events.stream().filter(e -> e.getEventType() == ChangeEvent.EventType.INSERT).count();
    }

    /** Pulls host and port out of a JDBC URL env var (falling back to the given default URL). */
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
        Matcher m = Pattern.compile("jdbc:\\w+://[^/]+/(\\w+)").matcher(url);
        return m.find() ? m.group(1) : fallback;
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            boolean isResultSet = st.execute(sql);
            // pg_terminate_backend returns a result set; drain it so errors are not lost
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
