package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed unit coverage for {@code DatabasePollingCDCConnector#doStart} branches:
 * table filtering, timestamp vs incremental columns, query-timeout parsing and
 * data-source availability (no Redis required).
 */
class DatabasePollingCDCConnectorH2CoverageTest {

    private static String newDb() {
        return "jdbc:h2:mem:gap" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Runs the private table-scan eagerly, then returns one poll batch. */
    private static List<ChangeEvent> scan(DatabasePollingCDCConnector connector) throws Exception {
        Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("pollTablesForChanges");
        m.setAccessible(true);
        m.invoke(connector);
        return connector.poll();
    }

    @Test
    void doStartWithExplicitTimestampAndQueryTimeout() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE audit(id INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, msg VARCHAR(64))");
            exec(keeper, "INSERT INTO audit(id, msg) VALUES (1, 'seed')");

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-gap")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .queryTimeout(5)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.AUDIT")
                            .timestampColumn("updated_at")
                            .build());

            connector.start().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(connector.isRunning());
            assertTrue(connector.isDataSourceAvailable());
            assertEquals(List.of("PUBLIC.AUDIT"), connector.getTables());

            exec(keeper, "INSERT INTO audit(id, msg, updated_at) VALUES (2, 'new', TIMESTAMP '2030-01-01 00:00:00')");
            List<ChangeEvent> events = scan(connector);
            assertEquals(1, events.size());
            assertEquals("AUDIT", events.get(0).getTable(), "table name without schema prefix");
            assertNotNull(events.get(0).getKey());
            assertNotNull(events.get(0).getTimestamp());

            connector.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(connector.isDataSourceAvailable(), "pool closed on stop");
        }
    }

    @Test
    void doStartWithIncrementalColumnBaseline() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE inc(id INT PRIMARY KEY, val VARCHAR(16))");
            exec(keeper, "INSERT INTO inc(id, val) VALUES (1, 'old')");

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-inc")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.INC")
                            .incrementalColumn("id")
                            .build());

            connector.start().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, connector.getLastPolledValues().get("PUBLIC.INC"), "baseline at MAX(id)");

            exec(keeper, "INSERT INTO inc(id, val) VALUES (2, 'new')");
            List<ChangeEvent> events = scan(connector);
            assertEquals(1, events.size());
            connector.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void tableFilteringByIncludesAndExcludes() throws Exception {
        String db = newDb();
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            exec(keeper, "CREATE TABLE keep_me(id INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            exec(keeper, "CREATE TABLE drop_me(id INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                    CDCConfigurationBuilder.forDatabasePolling("h2-filter")
                            .username("sa").password("")
                            .pollingIntervalMs(0)
                            .jdbcUrl(db)
                            .driverClass("org.h2.Driver")
                            .tables("PUBLIC.KEEP_ME,PUBLIC.DROP_ME")
                            .build());
            // property-driven include/exclude lists
            CDCConfigurationBuilder builder = CDCConfigurationBuilder.forDatabasePolling("h2-filter2")
                    .username("sa").password("")
                    .pollingIntervalMs(0)
                    .jdbcUrl(db)
                    .driverClass("org.h2.Driver")
                    .tables("PUBLIC.KEEP_ME,PUBLIC.DROP_ME");
            builder.property("table.includes", List.of("PUBLIC.KEEP_ME"));
            builder.property("table.excludes", List.of("PUBLIC.DROP_ME"));
            DatabasePollingCDCConnector filtered = new DatabasePollingCDCConnector(builder.build());

            filtered.start().get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("PUBLIC.KEEP_ME"), filtered.getTables());
            filtered.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);

            connector.stop().get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void filterEverythingLeavesNoTables() {
        CDCConfigurationBuilder builder = CDCConfigurationBuilder.forDatabasePolling("h2-none")
                .username("sa").password("")
                .pollingIntervalMs(0)
                .jdbcUrl(newDb())
                .driverClass("org.h2.Driver")
                .tables("PUBLIC.A");
        builder.property("table.excludes", List.of("PUBLIC.A"));
        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(builder.build());

        Exception ex = assertThrows(Exception.class,
                () -> connector.start().get(15, java.util.concurrent.TimeUnit.SECONDS));
        Throwable root = ex.getCause() != null ? ex.getCause().getCause() : ex;
        assertTrue(String.valueOf(root.getMessage()).contains("No tables left"), String.valueOf(root));
    }

    @Test
    void missingJdbcUrlOrTablesFailsFast() {
        DatabasePollingCDCConnector noUrl = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-nourl")
                        .tables("T").pollingIntervalMs(0).build());
        Exception ex1 = assertThrows(Exception.class,
                () -> noUrl.start().get(15, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(ex1.getCause().getCause() instanceof IllegalArgumentException,
                String.valueOf(ex1.getCause().getCause()));

        DatabasePollingCDCConnector noTables = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-notables")
                        .jdbcUrl(newDb()).pollingIntervalMs(0).build());
        Exception ex2 = assertThrows(Exception.class,
                () -> noTables.start().get(15, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(ex2.getCause().getCause() instanceof IllegalArgumentException,
                String.valueOf(ex2.getCause().getCause()));
    }

    @Test
    void parseTableListHandlesBlanks() throws Exception {
        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-parse")
                        .jdbcUrl("jdbc:noop").tables("a").build());
        Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("parseTableList", String.class);
        m.setAccessible(true);
        assertEquals(List.of(), m.invoke(connector, (Object) null));
        assertEquals(List.of(), m.invoke(connector, "  "));
        assertEquals(List.of("x", "y"), m.invoke(connector, " x , ,y "));
    }

    @Test
    void generateKeyAndMetadataHelpers() throws Exception {
        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(
                CDCConfigurationBuilder.forDatabasePolling("h2-key")
                        .jdbcUrl("jdbc:noop").tables("db.t").build());
        Method generateKey = DatabasePollingCDCConnector.class.getDeclaredMethod("generateKey", java.util.Map.class);
        generateKey.setAccessible(true);
        assertEquals("7", generateKey.invoke(connector, java.util.Map.of("id", 7)));
        assertEquals("9", generateKey.invoke(connector, java.util.Map.of("pk", 9)));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("k2", "b");
        row.put("k1", "a");
        Object key = generateKey.invoke(connector, row);
        assertNotNull(key);
        assertTrue(String.valueOf(key).contains("a"), String.valueOf(key));

        Method setMeta = DatabasePollingCDCConnector.class.getDeclaredMethod(
                "setEventMetadata", ChangeEvent.class, String.class, Object.class);
        setMeta.setAccessible(true);
        ChangeEvent event = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", java.util.Map.of());
        setMeta.invoke(connector, event, "db.t", 42);
        assertEquals("h2-key", event.getSource());
        assertEquals("db.t:42", event.getPosition());
        assertNotNull(event.getTimestamp());
    }
}
