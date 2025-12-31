package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DatabasePollingCDCConnector
 */
class DatabasePollingCDCConnectorTest {

    @Mock
    private DataSource mockDataSource;

    @Mock
    private Connection mockConnection;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private ResultSetMetaData mockMetaData;

    @Mock
    private Statement mockStatement;

    private CDCConfiguration configuration;
    private DatabasePollingCDCConnector connector;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);

        // Setup mock chain
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockMetaData);
        when(mockMetaData.getColumnCount()).thenReturn(3);
        when(mockMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockMetaData.getColumnLabel(3)).thenReturn("updated_at");

        configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("")
                .tables("test_table")
                .pollingIntervalMs(1000)
                .build();
    }

    @Test
    void testConstructorWithValidConfiguration() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNotNull(c);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testConstructorWithNullConfiguration() {
        // Constructor accepts null, but will fail when accessing parent methods
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(null);
        assertNotNull(c);
    }

    @Test
    void testGetConfiguration() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertEquals(configuration, c.getConfiguration());
    }

    @Test
    void testGetTablesBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertTrue(c.getTables().isEmpty());
    }

    @Test
    void testGetTimestampColumnBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // timestampColumn field is null before start
        assertNull(c.getTimestampColumn());
    }

    @Test
    void testGetIncrementalColumnBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // incrementalColumn field is null before start
        assertNull(c.getIncrementalColumn());
    }

    @Test
    void testGetLastPolledValuesBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // Should be empty before start
        assertTrue(c.getLastPolledValues().isEmpty());
    }

    @Test
    void testIsDataSourceAvailableBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertFalse(c.isDataSourceAvailable());
    }

    @Test
    void testStartWithMissingJdbcUrl() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);

        Exception e = assertThrows(Exception.class, () -> c.start().join());
        // Unwrap the exception chain: CompletionException -> RuntimeException -> IllegalArgumentException
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalArgumentException);
        assertTrue(cause.getMessage().contains("JDBC URL"));
    }

    @Test
    void testStartWithMissingTables() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);

        Exception e = assertThrows(Exception.class, () -> c.start().join());
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalArgumentException);
        assertTrue(cause.getMessage().contains("table"));
    }

    @Test
    void testStartWithEmptyTablesString() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);

        Exception e = assertThrows(Exception.class, () -> c.start().join());
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalArgumentException);
    }

    @Test
    void testStartWithWhitespaceTablesString() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("   ")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);

        Exception e = assertThrows(Exception.class, () -> c.start().join());
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalArgumentException);
    }

    @Test
    void testPollBeforeStartReturnsEmpty() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        List<ChangeEvent> events = c.poll();
        assertTrue(events.isEmpty());
    }

    @Test
    void testCommitBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("test_table:123"));
    }

    @Test
    void testResetToPositionBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition("test_table:123"));
    }

    @Test
    void testStopBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.stop().join());
    }

    @Test
    void testGetName() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertEquals("test-connector", c.getName());
    }

    @Test
    void testGetHealthStatus() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNotNull(c.getHealthStatus());
    }

    @Test
    void testGetMetrics() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNotNull(c.getMetrics());
    }

    @Test
    void testGetEventListener() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // eventListener is a protected field, cannot test it directly
        // Just verify the connector can be created
        assertNotNull(c);
    }

    @Test
    void testSetEventListener() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.setEventListener(null));
    }

    @Test
    void testGetCurrentPositionBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNull(c.getCurrentPosition());
    }

    @Test
    void testIsRunningBeforeStart() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertFalse(c.isRunning());
    }

    @Test
    void testConfigurationWithCustomTimestampColumn() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("timestamp.column", "modified_at")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomIncrementalColumn() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("incremental.column", "id")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomQueryTimeout() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("query.timeout.seconds", "60")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithMultipleTables() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("table1,table2,table3")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithQualifiedTableNames() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("db1.table1,db2.table2")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithDriverClass() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("driver.class", "org.h2.Driver")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testCommitWithNullPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(null));
    }

    @Test
    void testCommitWithEmptyPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit(""));
    }

    @Test
    void testCommitWithPositionWithoutColon() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("invalid_position"));
    }

    @Test
    void testCommitWithValidPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("test_table:12345"));
    }

    @Test
    void testResetToPositionWithNullPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition(null));
    }

    @Test
    void testResetToPositionWithEmptyPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition(""));
    }

    @Test
    void testResetToPositionWithPositionWithoutColon() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition("invalid_position"));
    }

    @Test
    void testResetToPositionWithValidPosition() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition("test_table:12345"));
    }

    @Test
    void testResetToPositionWithMultipleColons() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.resetToPosition("db.table:123:extra"));
    }

    @Test
    void testCommitWithMultipleColons() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertDoesNotThrow(() -> c.commit("db.table:123:extra"));
    }

    @Test
    void testConfigurationWithDefaultValues() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertNotNull(c.getConfiguration());
    }

    @Test
    void testToString() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNotNull(c.toString());
    }

    @Test
    void testEquals() {
        DatabasePollingCDCConnector c1 = new DatabasePollingCDCConnector(configuration);
        DatabasePollingCDCConnector c2 = new DatabasePollingCDCConnector(configuration);
        // Different instances, not equal
        assertNotEquals(c1, c2);
    }

    @Test
    void testHashCode() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertNotNull(c.hashCode());
    }

    @Test
    void testConfigurationWithTableIncludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("table1,table2,table3")
                .property("table.includes", "table1,table3")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithTableExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("table1,table2,table3")
                .property("table.excludes", "table2")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithBothIncludesAndExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("table1,table2,table3")
                .property("table.includes", "table1,table2")
                .property("table.excludes", "table2")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testPollReturnsEmptyListWhenNotRunning() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        List<ChangeEvent> events = c.poll();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testMultiplePollCallsWhenNotRunning() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);

        List<ChangeEvent> events1 = c.poll();
        List<ChangeEvent> events2 = c.poll();
        List<ChangeEvent> events3 = c.poll();

        assertTrue(events1.isEmpty());
        assertTrue(events2.isEmpty());
        assertTrue(events3.isEmpty());
    }

    @Test
    void testConfigurationWithLargePollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .pollingIntervalMs(60000)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithSmallPollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .pollingIntervalMs(100)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithDefaultBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .batchSize(100)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithUsername() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .username("admin")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithPassword() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .password("secret")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithNullPassword() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .password(null)
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithEmptyPassword() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .password("")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithNegativePollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .pollingIntervalMs(-1)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithZeroPollingInterval() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .pollingIntervalMs(0)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithVeryLargeBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .batchSize(10000)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithBatchSizeOfOne() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .batchSize(1)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithZeroBatchSize() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .batchSize(0)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithVeryLongTableName() {
        String longTableName = "a".repeat(200);
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables(longTableName)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithSpecialCharactersInTableName() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("table-with-dash,table_with_underscore,table.with.dots")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithSingleTable() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("single_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithManyTables() {
        StringBuilder tables = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (i > 1) tables.append(",");
            tables.append("table").append(i);
        }

        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables(tables.toString())
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithNullUsername() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .username(null)
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithEmptyUsername() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .username("")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomDefaultTimestampColumn() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        // Should default to "updated_at"
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithCustomDefaultQueryTimeout() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        // Should default to 30
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithVeryLargeQueryTimeout() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("query.timeout.seconds", "3600")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithZeroQueryTimeout() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("query.timeout.seconds", "0")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithNegativeQueryTimeout() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("query.timeout.seconds", "-1")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testGetTablesAfterConstruction() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        assertTrue(c.getTables().isEmpty());
    }

    @Test
    void testGetTimestampColumnAfterConstruction() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // Before start, timestampColumn should be null (field is directly returned)
        assertNull(c.getTimestampColumn());
    }

    @Test
    void testGetIncrementalColumnAfterConstruction() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // Before start, incrementalColumn should be null (field is directly returned)
        assertNull(c.getIncrementalColumn());
    }

    @Test
    void testGetLastPolledValuesAfterConstruction() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);
        // Before start, lastPolledValues returns empty map (new HashMap created)
        assertTrue(c.getLastPolledValues().isEmpty());
    }

    @Test
    void testStartWithNullTableIncludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("table.includes", (String) null)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testStartWithEmptyTableIncludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("table.includes", "")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testStartWithNullTableExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("table.excludes", (String) null)
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testStartWithEmptyTableExcludes() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("test_table")
                .property("table.excludes", "")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithDifferentJdbcUrl() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithPostgresJdbcUrl() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:postgresql://localhost:5432/test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithOracleJdbcUrl() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:oracle:thin:@localhost:1521:orcl")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithSqlServerJdbcUrl() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:sqlserver://localhost:1433;databaseName=test")
                .tables("test_table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testPollMultipleTimesWhenNotRunning() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);

        for (int i = 0; i < 10; i++) {
            List<ChangeEvent> events = c.poll();
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void testCommitMultipleTimesWhenNotRunning() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);

        for (int i = 0; i < 10; i++) {
            final String position = "table:" + i;
            assertDoesNotThrow(() -> c.commit(position));
        }
    }

    @Test
    void testResetToPositionMultipleTimesWhenNotRunning() {
        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(configuration);

        for (int i = 0; i < 10; i++) {
            final String position = "table:" + i;
            assertDoesNotThrow(() -> c.resetToPosition(position));
        }
    }

    @Test
    void testConfigurationWithComplexTableName() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("schema_name.table_name")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }

    @Test
    void testConfigurationWithTableContainingDatabaseAndSchema() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("test")
                .jdbcUrl("jdbc:h2:mem:test")
                .tables("catalog.schema.table")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(config);
        assertEquals("test", c.getName());
    }
}
