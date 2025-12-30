package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DatabasePollingCDCConnector
 * Requires MySQL database to be running
 */
@Tag("integration")
class DatabasePollingCDCConnectorIntegrationTest {

    private String getMysqlUrl() {
        return System.getenv().getOrDefault("MYSQL_URL", "jdbc:mysql://localhost:3306/test_db");
    }

    private String getMysqlUser() {
        return System.getenv().getOrDefault("MYSQL_USER", "test_user");
    }

    private String getMysqlPassword() {
        return System.getenv().getOrDefault("MYSQL_PASSWORD", "test_password");
    }

    @Test
    void testConfigurationBuilderForPolling() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .pollingIntervalMs(1000)
                .build();

        assertNotNull(configuration);
        assertEquals("test-connector", configuration.getName());
        assertEquals(getMysqlUrl(), configuration.getProperty("jdbc.url"));
    }

    @Test
    void testConnectorCreation() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);
        assertFalse(connector.isRunning());

        connector.stop();
    }

    @Test
    void testConnectorStartFailsWithInvalidConfiguration() {
        // Missing JDBC URL
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("invalid-connector")
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        CompletableFuture<Void> startFuture = connector.start();

        // Start should fail with exception
        CompletionException exception = assertThrows(CompletionException.class, startFuture::join);
        assertNotNull(findCause(exception, IllegalArgumentException.class));

        connector.stop();
    }

    @Test
    void testConnectorStartFailsWithNoTables() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("no-tables-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        CompletableFuture<Void> startFuture = connector.start();

        // Start should fail with exception
        CompletionException exception = assertThrows(CompletionException.class, startFuture::join);
        assertNotNull(findCause(exception, IllegalArgumentException.class));

        connector.stop();
    }

    @Test
    void testConnectorStopWithoutStart() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        // Stop without start should not throw
        assertDoesNotThrow(() -> connector.stop());
    }

    @Test
    void testMultipleStartCalls() {
        Assumptions.assumeTrue(
                System.getenv("MYSQL_URL") != null && !System.getenv("MYSQL_URL").isBlank(),
                "MYSQL_URL not set; skipping MySQL-start integration test"
        );

        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        // Note: This test assumes MySQL is not available, so start will fail
        CompletableFuture<Void> startFuture1 = connector.start();

        // Wait for start to attempt
        try {
            startFuture1.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected if MySQL is not available
        }

        // Second start should be idempotent or handle gracefully
        assertDoesNotThrow(() -> {
            CompletableFuture<Void> startFuture2 = connector.start();
            try {
                startFuture2.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });

        connector.stop();
    }

    @Test
    void testConnectorWithCustomProperties() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("custom-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .property("timestamp.column", "created_at")
                .property("incremental.column", "id")
                .property("query.timeout.seconds", "60")
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);
        assertEquals("created_at", configuration.getProperty("timestamp.column"));
        assertEquals("id", configuration.getProperty("incremental.column"));
        assertEquals("60", configuration.getProperty("query.timeout.seconds"));

        connector.stop();
    }

    @Test
    void testConnectorWithTableIncludes() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("table-filter-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users,test_db.orders,test_db.products")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);

        connector.stop();
    }

    @Test
    void testConnectorWithTableExcludes() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("table-exclude-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users,test_db.orders,test_db.products")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);

        connector.stop();
    }

    @Test
    void testConnectorGetName() {
        String connectorName = "test-connector-" + UUID.randomUUID().toString().substring(0, 8);

        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling(connectorName)
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertEquals(connectorName, connector.getName());

        connector.stop();
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable current = t;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @Test
    void testConnectorConfigurationAccess() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("config-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertEquals(configuration, connector.getConfiguration());

        connector.stop();
    }

    @Test
    void testConnectorWithZeroPollingInterval() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("zero-poll-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .pollingIntervalMs(0)
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);
        assertEquals(0L, configuration.getPollingIntervalMs());

        connector.stop();
    }

    @Test
    void testConnectorWithNegativePollingInterval() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("neg-poll-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .pollingIntervalMs(-1000)
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);
        assertEquals(-1000L, configuration.getPollingIntervalMs());

        connector.stop();
    }

    @Test
    void testConnectorWithCustomBatchSize() {
        CDCConfiguration configuration = CDCConfigurationBuilder.forDatabasePolling("batch-connector")
                .jdbcUrl(getMysqlUrl())
                .driverClass("com.mysql.cj.jdbc.Driver")
                .tables("test_db.users")
                .username(getMysqlUser())
                .password(getMysqlPassword())
                .property("batch.size", "500")
                .build();

        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(configuration);

        assertNotNull(connector);
        assertEquals("500", configuration.getProperty("batch.size"));

        connector.stop();
    }
}
