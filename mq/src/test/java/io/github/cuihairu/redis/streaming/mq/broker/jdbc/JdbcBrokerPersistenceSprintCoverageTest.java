package io.github.cuihairu.redis.streaming.mq.broker.jdbc;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link JdbcBrokerPersistence#append}: failure wrapping and
 * generated-key handling.
 */
class JdbcBrokerPersistenceSprintCoverageTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement ps;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(ps);
        when(ps.getGeneratedKeys()).thenReturn(rs);
    }

    private static Message message() {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        return m;
    }

    @Test
    void appendWrapsExecuteUpdateFailure() throws Exception {
        doThrow(new SQLException("insert boom")).when(ps).executeUpdate();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new JdbcBrokerPersistence(dataSource).append("t", 0, message()));
        assertEquals("JDBC append failed", e.getMessage());
        assertEquals(SQLException.class, e.getCause().getClass());
    }

    @Test
    void appendWrapsGeneratedKeysCloseFailure() throws Exception {
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(42L);
        doThrow(new SQLException("close boom")).when(rs).close();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new JdbcBrokerPersistence(dataSource).append("t", 0, message()));
        assertEquals("JDBC append failed", e.getMessage());
    }

    @Test
    void appendReturnsGeneratedId() throws Exception {
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(7L);
        assertEquals("7", new JdbcBrokerPersistence(dataSource).append("t", 0, message()));
    }

    @Test
    void appendReturnsNullWithoutGeneratedKeys() throws Exception {
        when(rs.next()).thenReturn(false);
        assertNull(new JdbcBrokerPersistence(dataSource).append("t", 0, message()));
        verify(ps).setString(4, null);
    }
}
