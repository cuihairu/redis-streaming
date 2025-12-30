package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JdbcBrokerPersistence
 */
class JdbcBrokerPersistenceTest {

    @Mock
    private javax.sql.DataSource mockDataSource;

    @Mock
    private Connection mockConnection;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private ResultSet mockResultSet;

    private JdbcBrokerPersistence persistence;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        persistence = new JdbcBrokerPersistence(mockDataSource);

        // Default mock behavior
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString(), any(String[].class))).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);
        when(mockPreparedStatement.getGeneratedKeys()).thenReturn(mockResultSet);
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidDataSource() {
        assertNotNull(persistence);
    }

    @Test
    void testConstructorWithNullDataSource() {
        // Constructor accepts null, but using it will throw an exception
        JdbcBrokerPersistence p = new JdbcBrokerPersistence(null);
        // DataSource.getConnection() will throw NPE, wrapped in RuntimeException
        assertThrows(RuntimeException.class, () -> p.append("topic", 0, new Message()));
    }

    // ===== Append Tests =====

    @Test
    void testAppendWithBasicMessage() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(123L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("123", id);
        verify(mockPreparedStatement).setString(1, "test-topic");
        verify(mockPreparedStatement).setInt(2, 0);
        verify(mockPreparedStatement).executeUpdate();
    }

    @Test
    void testAppendWithAllFields() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey("test-key");
        message.setPayload("test-payload");
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        message.setHeaders(headers);
        message.setTimestamp(Instant.ofEpochMilli(123456789L));

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(456L);

        String id = persistence.append("test-topic", 1, message);

        assertEquals("456", id);
        verify(mockPreparedStatement).setString(1, "test-topic");
        verify(mockPreparedStatement).setInt(2, 1);
        verify(mockPreparedStatement).setString(4, "test-key");
        // Headers JSON should contain both entries
        verify(mockPreparedStatement).setString(5, "{\"header1\":\"value1\",\"header2\":\"value2\"}");
    }

    @Test
    void testAppendWithNullTopic() throws SQLException {
        Message message = new Message();
        message.setTopic(null);
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(789L);

        String id = persistence.append(null, 0, message);

        assertEquals("789", id);
        verify(mockPreparedStatement).setString(1, null);
    }

    @Test
    void testAppendWithNullKey() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey(null);
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(100L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("100", id);
        verify(mockPreparedStatement).setString(4, null);
    }

    @Test
    void testAppendWithNullPayload() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(200L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("200", id);
        verify(mockPreparedStatement).setBytes(6, null);
    }

    @Test
    void testAppendWithEmptyHeaders() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setHeaders(java.util.Map.of());

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(300L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("300", id);
        // Empty headers should result in null JSON
        verify(mockPreparedStatement).setString(5, null);
    }

    @Test
    void testAppendWithNullHeaders() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setHeaders(null);

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(400L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("400", id);
        verify(mockPreparedStatement).setString(5, null);
    }

    @Test
    void testAppendWithHeadersContainingSpecialCharacters() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setHeaders(java.util.Map.of("key\"with\"quotes", "value\\with\\backslash"));

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(500L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("500", id);
        // Special characters should be escaped
        verify(mockPreparedStatement).setString(5, "{\"key\\\"with\\\"quotes\":\"value\\\\with\\\\backslash\"}");
    }

    @Test
    void testAppendWithByteArrayPayload() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        message.setPayload(payload);

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(600L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("600", id);
        verify(mockPreparedStatement).setBytes(6, payload);
    }

    @Test
    void testAppendWithStringPayload() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("string payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(700L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("700", id);
        verify(mockPreparedStatement).setBytes(6, "string payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void testAppendWithNegativePartitionId() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(800L);

        String id = persistence.append("test-topic", -1, message);

        assertEquals("800", id);
        verify(mockPreparedStatement).setInt(2, -1);
    }

    @Test
    void testAppendWithLargePartitionId() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(900L);

        String id = persistence.append("test-topic", 9999, message);

        assertEquals("900", id);
        verify(mockPreparedStatement).setInt(2, 9999);
    }

    // ===== Error Handling Tests =====

    @Test
    void testAppendWhenConnectionFails() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockDataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        assertThrows(RuntimeException.class, () -> persistence.append("test-topic", 0, message));
    }

    @Test
    void testAppendWhenPrepareStatementFails() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockConnection.prepareStatement(anyString(), any(String[].class)))
                .thenThrow(new SQLException("Prepare failed"));

        assertThrows(RuntimeException.class, () -> persistence.append("test-topic", 0, message));
    }

    @Test
    void testAppendWhenExecuteUpdateFails() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockPreparedStatement.executeUpdate()).thenThrow(new SQLException("Execute failed"));

        assertThrows(RuntimeException.class, () -> persistence.append("test-topic", 0, message));
    }

    @Test
    void testAppendWhenNoGeneratedKeys() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockResultSet.next()).thenReturn(false);

        String id = persistence.append("test-topic", 0, message);

        assertNull(id);
    }

    @Test
    void testAppendWhenGetGeneratedKeysFails() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        when(mockPreparedStatement.getGeneratedKeys()).thenThrow(new SQLException("Get keys failed"));

        assertThrows(RuntimeException.class, () -> persistence.append("test-topic", 0, message));
    }

    @Test
    void testAppendWithNullTimestamp() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setTimestamp(null);

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(1000L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("1000", id);
        // Verify timestamp was set (should use current time when null)
        verify(mockPreparedStatement).setTimestamp(eq(3), any(java.sql.Timestamp.class));
    }

    @Test
    void testAppendWithValidTimestamp() throws SQLException {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setTimestamp(Instant.ofEpochMilli(999999999L));

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(1100L);

        String id = persistence.append("test-topic", 0, message);

        assertEquals("1100", id);
        verify(mockPreparedStatement).setTimestamp(3, java.sql.Timestamp.from(Instant.ofEpochMilli(999999999L)));
    }

    // ===== Multiple Appends Tests =====

    @Test
    void testMultipleAppends() throws SQLException {
        Message message1 = new Message();
        message1.setTopic("topic1");
        message1.setPayload("payload1");

        Message message2 = new Message();
        message2.setTopic("topic2");
        message2.setPayload("payload2");

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(1L).thenReturn(2L);

        String id1 = persistence.append("topic1", 0, message1);
        String id2 = persistence.append("topic2", 1, message2);

        assertEquals("1", id1);
        assertEquals("2", id2);
        verify(mockPreparedStatement, times(2)).executeUpdate();
    }
}
