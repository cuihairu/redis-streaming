package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Covers JdbcBrokerPersistence.toJson/escapeJson header-serialization branches.
 */
class JdbcBrokerPersistenceJsonEscapingTest {

    @Mock
    private javax.sql.DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;

    private JdbcBrokerPersistence persistence;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        persistence = new JdbcBrokerPersistence(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.getGeneratedKeys()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(42L);
    }

    @Test
    void appendEscapesQuotesAndBackslashesInHeaders() throws Exception {
        Message m = new Message();
        m.setTopic("t");
        m.setTimestamp(Instant.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("k\"1", "v\\2");
        headers.put("plain", "ok");
        m.setHeaders(headers);

        assertEquals("42", persistence.append("t", 0, m));
        verify(preparedStatement).setString(eq(5), argThat((String json) -> json != null
                && json.startsWith("{")
                && json.endsWith("}")
                && json.contains("\"k\\\"1\":\"v\\\\2\"")
                && json.contains("\"plain\":\"ok\"")));
    }

    @Test
    void appendSerializesBytePayloadAndSkipsEmptyHeaders() throws Exception {
        Message m = new Message();
        m.setTopic("t");
        m.setTimestamp(Instant.now());
        m.setHeaders(Map.of());
        m.setPayload(new byte[]{1, 2, 3});

        assertEquals("42", persistence.append("t", 0, m));
        verify(preparedStatement).setString(eq(5), isNull());
        verify(preparedStatement).setBytes(eq(6), eq(new byte[]{1, 2, 3}));

        Message m2 = new Message();
        m2.setTopic("t");
        m2.setTimestamp(Instant.now());
        m2.setPayload(123);
        assertEquals("42", persistence.append("t", 0, m2));
        verify(preparedStatement, times(2)).setString(eq(5), isNull());
    }

    @Test
    void appendToleratesNullValuesInsideHeaders() throws Exception {
        Message m = new Message();
        m.setTopic("t");
        m.setTimestamp(Instant.now());
        Map<String, String> headers = new HashMap<>();
        headers.put("a", "b");
        headers.put("c", null); // escapeJson(null) -> ""
        m.setHeaders(headers);

        assertEquals("42", persistence.append("t", 0, m));
        verify(preparedStatement).setString(eq(5), anyString());
    }
}
