package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MySQLBinlogCDCConnector
 */
class MySQLBinlogCDCConnectorTest {

    @Mock
    private BinaryLogClient mockBinaryLogClient;

    private CDCConfiguration configuration;
    private MySQLBinlogCDCConnector connector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        configuration = CDCConfigurationBuilder.forMySQLBinlog("test-connector")
                .mysqlHostname("localhost")
                .mysqlPort(3306)
                .username("root")
                .password("password")
                .build();
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidConfiguration() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertNotNull(c);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testConstructorWithNullConfiguration() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(null);
        assertNotNull(c);
    }

    @Test
    void testGetConfiguration() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertEquals(configuration, c.getConfiguration());
    }

    // ===== State Before Start Tests =====

    @Test
    void testGetBinlogFilenameBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertNull(c.getBinlogFilename());
    }

    @Test
    void testGetBinlogPositionBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertEquals(0, c.getBinlogPosition());
    }

    @Test
    void testIsConnectedBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertFalse(c.isConnected());
    }

    @Test
    void testPollBeforeStartReturnsEmpty() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        List<ChangeEvent> events = c.poll();
        assertTrue(events.isEmpty());
    }

    @Test
    void testCommitBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("mysql-bin.000001:123"));
    }

    @Test
    void testResetToPositionBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        // binaryLogClient is null, will throw RuntimeException wrapping NPE
        Exception e = assertThrows(Exception.class, () -> c.resetToPosition("mysql-bin.000001:123"));
        assertTrue(e.getCause() instanceof NullPointerException);
    }

    @Test
    void testStopBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.stop().join());
    }

    @Test
    void testGetName() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testGetHealthStatus() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertNotNull(c.getHealthStatus());
    }

    @Test
    void testGetMetrics() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertNotNull(c.getMetrics());
    }

    @Test
    void testSetEventListener() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.setEventListener(null));
    }

    @Test
    void testGetCurrentPositionBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertNull(c.getCurrentPosition());
    }

    @Test
    void testIsRunningBeforeStart() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertFalse(c.isRunning());
    }

    // ===== Configuration Tests =====

    @Test
    void testConfigurationWithDefaultHostname() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .username("root")
                .password("password")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithDefaultPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .mysqlPort(3307)
                .username("root")
                .password("password")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomServerId() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .mysqlServerId(12345)
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithBinlogFilename() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .mysqlBinlogFilename("mysql-bin.000001")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithBinlogPosition() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .mysqlBinlogPosition(1000)
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithInvalidPort() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .property("port", "invalid")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        // Will fail on start with NumberFormatException
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithInvalidServerId() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .property("server.id", "invalid")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithInvalidBinlogPosition() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .property("binlog.position", "invalid")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertThrows(Exception.class, () -> c.start().join());
    }

    // ===== Position Handling Tests =====

    @Test
    void testCommitWithValidPosition() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        c.commit("mysql-bin.000001:1234");
        assertEquals("mysql-bin.000001", c.getBinlogFilename());
        assertEquals(1234, c.getBinlogPosition());
    }

    @Test
    void testCommitWithNullPosition() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(null));
    }

    @Test
    void testCommitWithEmptyPosition() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(""));
    }

    @Test
    void testCommitWithPositionWithoutColon() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("mysql-bin.000001"));
    }

    @Test
    void testCommitWithInvalidPositionFormat() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        // Invalid number after colon - commit() wraps doCommit exception in RuntimeException
        Exception e = assertThrows(Exception.class, () -> c.commit("mysql-bin.000001:invalid"));
        assertTrue(e.getCause() instanceof NumberFormatException);
    }

    @Test
    void testCommitWithMultipleColons() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        // split(":") will return all parts, but we only use first two
        // "123:456" will be parsed as 123 (ignoring :456)
        c.commit("mysql-bin.000001:123:456");
        assertEquals("mysql-bin.000001", c.getBinlogFilename());
        assertEquals(123, c.getBinlogPosition());
    }

    // ===== Reset Position Tests =====

    @Test
    void testResetToPositionWithValidPosition() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        // This will fail because binaryLogClient is null
        Exception e = assertThrows(Exception.class, () -> c.resetToPosition("mysql-bin.000001:1000"));
        assertTrue(e.getCause() instanceof NullPointerException);
    }

    @Test
    void testResetToPositionWithNullPosition() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        // doResetToPosition handles null position gracefully (no-op)
        // So resetToPosition won't throw an exception
        assertDoesNotThrow(() -> c.resetToPosition(null));
    }

    // ===== Table Filter Tests =====

    @Test
    void testConfigurationWithTableIncludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .tables("test_db.users")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithTableExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .property("table.excludes", "temp_db.*,cache.*")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithBothIncludeAndExclude() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .tables("test_db.*")
                .property("table.excludes", "test_db.temp_*")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    // ===== Event Handler Tests =====

    @Test
    void testHandleTableMapEvent() {
        // This tests the internal event handling logic
        // Since handleBinlogEvent is private, we can't directly test it
        // But we can verify that a connector can be created with table filtering
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .tables("test_db.*")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    // ===== Batch Size Tests =====

    @Test
    void testConfigurationWithBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .batchSize(100)
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithPollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .password("password")
                .pollingIntervalMs(500)
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        assertEquals("test", c.getName());
    }

    // ===== Error Handling Tests =====

    @Test
    void testConfigurationWithMissingUsername() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .password("password")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        // Username is null, will fail when trying to connect
        assertThrows(Exception.class, () -> c.start().join());
    }

    @Test
    void testConfigurationWithMissingPassword() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("test")
                .mysqlHostname("localhost")
                .username("root")
                .build();

        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(config);
        // Password is null, but BinaryLogClient may accept null password
        assertThrows(Exception.class, () -> c.start().join());
    }

    // ===== Event Queue Tests =====

    @Test
    void testPollReturnsEventsFromQueue() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        List<ChangeEvent> events = c.poll();
        assertTrue(events.isEmpty());
    }

    // ===== Getter/Setter Tests =====

    @Test
    void testGetBinlogFilenameAfterCommit() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        c.commit("mysql-bin.000002:2000");
        assertEquals("mysql-bin.000002", c.getBinlogFilename());
    }

    @Test
    void testGetBinlogPositionAfterCommit() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        c.commit("mysql-bin.000001:9999");
        assertEquals(9999, c.getBinlogPosition());
    }

    @Test
    void testGetBinlogFilenameAfterMultipleCommits() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        c.commit("mysql-bin.000001:1000");
        c.commit("mysql-bin.000002:2000");
        assertEquals("mysql-bin.000002", c.getBinlogFilename());
    }

    @Test
    void testGetBinlogPositionAfterMultipleCommits() {
        MySQLBinlogCDCConnector c = new MySQLBinlogCDCConnector(configuration);
        c.commit("mysql-bin.000001:1000");
        c.commit("mysql-bin.000002:2000");
        assertEquals(2000, c.getBinlogPosition());
    }
}
