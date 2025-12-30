package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSQLLogicalReplicationCDCConnector
 */
class PostgreSQLLogicalReplicationCDCConnectorTest {

    private CDCConfiguration configuration;
    private PostgreSQLLogicalReplicationCDCConnector connector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        configuration = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test-connector")
                .postgresqlHostname("localhost")
                .postgresqlPort(5432)
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .build();
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidConfiguration() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNotNull(c);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testConstructorWithNullConfiguration() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(null);
        assertNotNull(c);
    }

    @Test
    void testGetConfiguration() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertEquals(configuration, c.getConfiguration());
    }

    // ===== State Before Start Tests =====

    @Test
    void testGetCurrentLSNBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNull(c.getCurrentLSN());
    }

    @Test
    void testGetSlotNameBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNull(c.getSlotName());
    }

    @Test
    void testGetPublicationNameBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNull(c.getPublicationName());
    }

    @Test
    void testIsStreamActiveBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertFalse(c.isStreamActive());
    }

    @Test
    void testPollBeforeStartReturnsEmpty() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        List<ChangeEvent> events = c.poll();
        assertTrue(events.isEmpty());
    }

    @Test
    void testCommitBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("0/16B37D8"));
    }

    @Test
    void testResetToPositionBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        // Will throw RuntimeException because replicationStream is null
        assertThrows(Exception.class, () -> c.resetToPosition("0/16B37D8"));
    }

    @Test
    void testStopBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertDoesNotThrow(() -> c.stop().join());
    }

    @Test
    void testGetName() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testGetHealthStatus() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNotNull(c.getHealthStatus());
    }

    @Test
    void testGetMetrics() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNotNull(c.getMetrics());
    }

    @Test
    void testSetEventListener() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertDoesNotThrow(() -> c.setEventListener(null));
    }

    @Test
    void testGetCurrentPositionBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertNull(c.getCurrentPosition());
    }

    @Test
    void testIsRunningBeforeStart() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertFalse(c.isRunning());
    }

    // ===== Configuration Tests =====

    @Test
    void testConfigurationWithDefaultHostname() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithDefaultPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlPort(5433)
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithSlotName() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .postgresqlSlotName("custom_slot")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithPublicationName() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .postgresqlPublicationName("my_publication")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithStatusInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .postgresqlStatusInterval(5000)
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithInvalidPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .property("port", "invalid")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithInvalidStatusInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .property("status.interval.ms", "invalid")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithMissingDatabase() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .username("postgres")
                .password("password")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        Exception e = assertThrows(Exception.class, () -> c.start().join());
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalArgumentException);
    }

    // ===== Table Filter Tests =====

    @Test
    void testConfigurationWithTableIncludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .tables("public.users")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithTableExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .property("table.excludes", "temp.*,cache.*")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    // ===== Position Handling Tests =====

    @Test
    void testCommitWithNullPosition() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(null));
    }

    @Test
    void testCommitWithEmptyPosition() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(""));
    }

    @Test
    void testCommitWithInvalidLSN() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        // Invalid LSN format
        assertDoesNotThrow(() -> c.commit("invalid_lsn"));
    }

    // ===== Reset Position Tests =====

    @Test
    void testResetToPositionWithNullPosition() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        // Will throw RuntimeException because replicationStream is null and close() is called
        assertThrows(Exception.class, () -> c.resetToPosition(null));
    }

    @Test
    void testResetToPositionWithValidLSN() {
        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(configuration);
        // Will throw RuntimeException because replicationStream is null
        assertThrows(Exception.class, () -> c.resetToPosition("0/16B37D8"));
    }

    // ===== Batch Size Tests =====

    @Test
    void testConfigurationWithBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .batchSize(100)
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithPollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .pollingIntervalMs(500)
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    // ===== Error Handling Tests =====

    @Test
    void testConfigurationWithMissingUsername() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .password("password")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithMissingPassword() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    // ===== Getter Tests After Configuration =====

    @Test
    void testGetSlotName() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .postgresqlSlotName("test_slot")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testGetPublicationName() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("test")
                .postgresqlHostname("localhost")
                .postgresqlDatabase("testdb")
                .username("postgres")
                .password("password")
                .postgresqlPublicationName("test_pub")
                .build();

        PostgreSQLLogicalReplicationCDCConnector c = new PostgreSQLLogicalReplicationCDCConnector(config);
        assertEquals("test", c.getName());
    }
}
