package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabasePollingCDCConnectorBehaviorTest {

    @Test
    void parseTableListSkipsBlankSegments() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop")
                .tables("t1, ,t2,, t3 ")
                .build();

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(cfg);
        Method m = DatabasePollingCDCConnector.class.getDeclaredMethod("parseTableList", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) m.invoke(c, "t1, ,t2,, t3 ");

        assertEquals(List.of("t1", "t2", "t3"), tables);
    }

    @Test
    void initializeLastPolledValuesReadsMaxPerTable() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop")
                .tables("db.t")
                .build();

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rsMax = mock(ResultSet.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT MAX(updated_at) FROM db.t")).thenReturn(rsMax);
        when(rsMax.next()).thenReturn(true, false);
        when(rsMax.getObject(1)).thenReturn(100L);

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(cfg);
        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);

        invoke(c, "initializeLastPolledValues");

        assertEquals(100L, c.getLastPolledValues().get("db.t"));
    }

    @Test
    void pollFillsQueueAndSecondPollReturnsEventsAndUpdatesPosition() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop")
                .tables("db.t")
                .batchSize(10)
                .build();

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rsMax = mock(ResultSet.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT MAX(updated_at) FROM db.t")).thenReturn(rsMax);
        when(rsMax.next()).thenReturn(true, false);
        when(rsMax.getObject(1)).thenReturn(100L);

        String pollQuery = "SELECT * FROM db.t WHERE updated_at > ? ORDER BY updated_at";
        when(conn.prepareStatement(pollQuery)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(3);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("name");
        when(md.getColumnLabel(3)).thenReturn("updated_at");

        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(inv -> rowIndex.incrementAndGet() < 2);
        when(rs.getObject(anyInt())).thenAnswer(inv -> {
            int col = inv.getArgument(0);
            int r = rowIndex.get();
            return switch (col) {
                case 1 -> (r == 0) ? 1 : 2;
                case 2 -> (r == 0) ? "a" : "b";
                case 3 -> (r == 0) ? 150L : 200L;
                default -> null;
            };
        });
        when(rs.getObject(eq("updated_at"))).thenAnswer(inv -> rowIndex.get() == 0 ? 150L : 200L);

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(cfg);
        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);

        c.running.set(true);
        invoke(c, "initializeLastPolledValues");

        // First poll triggers scan and fills internal queue; second poll returns those events.
        assertTrue(c.poll().isEmpty());
        List<ChangeEvent> out = c.poll();

        assertEquals(2, out.size());
        assertEquals(ChangeEvent.EventType.INSERT, out.get(0).getEventType());
        assertEquals("db", out.get(0).getDatabase());
        assertEquals("t", out.get(0).getTable());
        assertEquals("1", out.get(0).getKey());
        assertEquals(Map.of("id", 1, "name", "a", "updated_at", 150L), out.get(0).getAfterData());

        assertNotNull(c.getCurrentPosition());
        assertTrue(c.getCurrentPosition().startsWith("db.t:"));

        verify(ps, org.mockito.Mockito.atLeastOnce()).setQueryTimeout(5);
        verify(ps, org.mockito.Mockito.atLeastOnce()).setObject(1, 100L);
    }

    @Test
    void pollTablesForChangesNotifiesListenerOnSqlException() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop")
                .tables("db.t")
                .build();

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("nope"));

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(cfg);
        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 1);
        c.running.set(true);

        CDCEventListener listener = mock(CDCEventListener.class);
        c.setEventListener(listener);

        assertTrue(c.poll().isEmpty());

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(listener).onConnectorError(eq("polling"), captor.capture());
        assertTrue(captor.getValue() instanceof SQLException);
    }

    @Test
    void isDataSourceAvailableHandlesSqlException() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop")
                .tables("db.t")
                .build();

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("boom"));

        DatabasePollingCDCConnector c = new DatabasePollingCDCConnector(cfg);
        setField(c, "dataSource", ds);
        assertFalse(c.isDataSourceAvailable());
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
