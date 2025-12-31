package io.github.cuihairu.redis.streaming.cdc.impl;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySQLColumnNameResolverTest {

    @Test
    void resolverQueriesInformationSchemaAndCachesResults() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("id", "name");

        TestDriver driver = new TestDriver(connection);
        DriverManager.registerDriver(driver);
        try {
            MySQLColumnNameResolver resolver = new DriverManagerMySQLColumnNameResolver("jdbc:test:mysql", "u", "p", 2);

            List<String> first = resolver.resolve("db", "t");
            assertEquals(List.of("id", "name"), first);

            List<String> second = resolver.resolve("db", "t");
            assertEquals(List.of("id", "name"), second);

            verify(connection, times(1)).prepareStatement(anyString());
            verify(ps, times(1)).setQueryTimeout(2);
            verify(ps, times(1)).setString(1, "db");
            verify(ps, times(1)).setString(2, "t");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void resolverReturnsEmptyForInvalidInputs() {
        MySQLColumnNameResolver resolver = new DriverManagerMySQLColumnNameResolver("jdbc:test:mysql", "u", "p", 2);
        assertTrue(resolver.resolve(null, "t").isEmpty());
        assertTrue(resolver.resolve("db", null).isEmpty());
        assertTrue(resolver.resolve(" ", "t").isEmpty());
        assertTrue(resolver.resolve("db", " ").isEmpty());
    }

    private static final class TestDriver implements Driver {
        private final Connection connection;

        private TestDriver(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:test:mysql");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("test");
        }
    }
}

