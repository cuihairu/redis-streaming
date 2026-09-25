package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractQueue;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fault-injection and boundary coverage for {@link DatabasePollingCDCConnector}: start-time
 * validation, driver-less data sources, empty result sets, malformed commit positions and the
 * concurrent-drain guard inside {@code doPoll()}.
 */
@Timeout(30)
class DatabasePollingCDCConnectorFaultCoverageTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static DatabasePollingCDCConnector connector(CDCConfiguration cfg) {
        return new DatabasePollingCDCConnector(cfg);
    }

    private static String memUrl(String name) {
        return "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    @Test
    void doStartRejectsMissingTableList() {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl(memUrl("c100d_notables"))
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> connector(cfg).doStart());
        assertTrue(e.getMessage().contains("At least one table"));
    }

    @Test
    void doStartRejectsTableListFilteredToEmpty() {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl(memUrl("c100d_filtered"))
                .tables("t1")
                .property("table.excludes", List.of("t1"))
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> connector(cfg).doStart());
        assertTrue(e.getMessage().contains("No tables left"));
    }

    @Test
    void doStartWithoutDriverClassStillConnects() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl(memUrl("c100d_nodriver"))
                .tables("t1")
                .property("snapshot.enabled", true)
                .pollingIntervalMs(0)
                .build();

        DatabasePollingCDCConnector c = connector(cfg);
        try {
            c.doStart();
            assertTrue(c.isDataSourceAvailable());
            assertEquals(List.of("t1"), c.getTables());
        } finally {
            c.doStop();
        }
    }

    @Test
    void doStartWithExplicitDriverClass() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl(memUrl("c100d_withdriver"))
                .tables("db1.t1")
                .property("driver.class", "org.h2.Driver")
                .property("snapshot.enabled", true)
                .property("snapshot.mode", null)
                .pollingIntervalMs(0)
                .build();

        DatabasePollingCDCConnector c = connector(cfg);
        try {
            c.doStart();
            assertTrue(c.isDataSourceAvailable());
            assertEquals(List.of("db1.t1"), c.getTables());
        } finally {
            c.doStop();
        }
    }

    @Test
    void getLastPolledValueReturnsNullForEmptyResultSet() throws Exception {
        DatabasePollingCDCConnector c = connector(CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").build());
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);

        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT MAX(updated_at) FROM db.t")).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Method m = DatabasePollingCDCConnector.class
                .getDeclaredMethod("getLastPolledValue", Connection.class, String.class);
        m.setAccessible(true);
        assertNull(m.invoke(c, conn, "db.t"));
    }

    @Test
    void getLastPolledValuePropagatesQueryFailureAfterClosingResources() throws Exception {
        DatabasePollingCDCConnector c = connector(CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").build());
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);

        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT MAX(updated_at) FROM db.t"))
                .thenThrow(new SQLException("bad column"));

        Method m = DatabasePollingCDCConnector.class
                .getDeclaredMethod("getLastPolledValue", Connection.class, String.class);
        m.setAccessible(true);
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> m.invoke(c, conn, "db.t"));
        assertTrue(e.getCause() instanceof SQLException);
    }

    @Test
    void getLastPolledValuePrefersIncrementalColumn() throws Exception {
        DatabasePollingCDCConnector c = connector(CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").build());
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", "inc_id");
        setField(c, "queryTimeoutSeconds", 5);

        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT MAX(inc_id) FROM db.t")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(7L);

        Method m = DatabasePollingCDCConnector.class
                .getDeclaredMethod("getLastPolledValue", Connection.class, String.class);
        m.setAccessible(true);
        assertEquals(7L, m.invoke(c, conn, "db.t"));
    }

    @Test
    void rowsWithNullPositionValueKeepPositionUnchanged() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").batchSize(10).build();
        DatabasePollingCDCConnector c = connector(cfg);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        String query = "SELECT * FROM db.t ORDER BY updated_at";
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(query)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("updated_at");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(anyInt())).thenAnswer(inv -> inv.getArgument(0).equals(1) ? 1 : null);
        when(rs.getObject("updated_at")).thenReturn(null);

        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);
        c.running.set(true);

        assertTrue(c.poll().isEmpty());
        List<ChangeEvent> events = c.poll();

        assertEquals(1, events.size());
        assertEquals("db.t:null", events.get(0).getPosition());
        assertTrue(c.getLastPolledValues().isEmpty(), "null values must not advance the polling position");
        assertNull(c.getCurrentPosition());
    }

    @Test
    void unchangedPositionValueIsNotRewritten() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").batchSize(10).build();
        DatabasePollingCDCConnector c = connector(cfg);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        String query = "SELECT * FROM db.t WHERE updated_at > ? ORDER BY updated_at";
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(query)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("updated_at");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(anyInt())).thenAnswer(inv -> inv.getArgument(0).equals(1) ? 1 : 100L);
        when(rs.getObject("updated_at")).thenReturn(100L);

        Map<String, Object> last = new HashMap<>();
        last.put("db.t", 100L);
        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "incrementalColumn", null);
        setField(c, "queryTimeoutSeconds", 5);
        setField(c, "lastPolledValues", last);
        c.running.set(true);

        assertTrue(c.poll().isEmpty());
        assertEquals(1, c.poll().size());

        assertNull(c.getCurrentPosition(), "equal values must not rewrite the position");
        assertEquals(100L, c.getLastPolledValues().get("db.t"));
    }

    @Test
    void isDataSourceAvailableHandlesDegradedConnections() throws Exception {
        DatabasePollingCDCConnector c = connector(CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").build());

        assertFalse(c.isDataSourceAvailable(), "no data source yet");

        DataSource nullConn = mock(DataSource.class);
        when(nullConn.getConnection()).thenReturn(null);
        setField(c, "dataSource", nullConn);
        assertFalse(c.isDataSourceAvailable(), "connection factory returned null");

        DataSource closed = mock(DataSource.class);
        Connection closedConn = mock(Connection.class);
        when(closed.getConnection()).thenReturn(closedConn);
        when(closedConn.isClosed()).thenReturn(true);
        setField(c, "dataSource", closed);
        assertFalse(c.isDataSourceAvailable(), "connection already closed");

        DataSource open = mock(DataSource.class);
        Connection openConn = mock(Connection.class);
        when(open.getConnection()).thenReturn(openConn);
        when(openConn.isClosed()).thenReturn(false);
        setField(c, "dataSource", open);
        assertTrue(c.isDataSourceAvailable());
    }

    @Test
    void commitAndResetSkipMalformedPositions() throws Exception {
        DatabasePollingCDCConnector c = connector(CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").build());

        c.doCommit(null);
        c.doCommit("no-colon-here");
        c.doCommit(":");
        c.doCommit("tbl:");
        c.doResetToPosition(null);
        c.doResetToPosition("plain");
        c.doResetToPosition(":");
        c.doResetToPosition("tbl:");

        assertTrue(c.getLastPolledValues().isEmpty(), "malformed positions must be ignored");

        c.doCommit("tbl:val");
        assertEquals("val", c.getLastPolledValues().get("tbl"));

        Map<String, Object> fresh = new HashMap<>();
        setField(c, "lastPolledValues", fresh);
        c.doResetToPosition("tbl:val");
        assertEquals("val", c.getLastPolledValues().get("tbl"));
    }

    @Test
    void doPollSkipsEntriesVanishedUnderConcurrentDrain() throws Exception {
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("polling")
                .jdbcUrl("jdbc:noop").tables("db.t").batchSize(3).build();
        DatabasePollingCDCConnector c = connector(cfg);

        // Simulates a concurrent consumer draining the shared queue between the isEmpty()
        // check and the poll() call inside doPoll().
        Queue<ChangeEvent> racy = new AbstractQueue<>() {
            @Override
            public boolean offer(ChangeEvent e) {
                return true;
            }

            @Override
            public ChangeEvent poll() {
                return null;
            }

            @Override
            public ChangeEvent peek() {
                return null;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public Iterator<ChangeEvent> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
        setField(c, "eventQueue", racy);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("db away"));
        setField(c, "dataSource", ds);
        setField(c, "tables", List.of("db.t"));
        setField(c, "timestampColumn", "updated_at");
        setField(c, "queryTimeoutSeconds", 1);
        c.running.set(true);

        assertEquals(List.of(), c.poll());
    }
}
