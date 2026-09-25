package io.github.cuihairu.redis.streaming.cdc.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the null/blank column-name guard of
 * {@code DriverManagerMySQLColumnNameResolver#queryColumnNames}: rows with NULL or blank
 * COLUMN_NAME values must be dropped from the resolved column list.
 */
class DriverManagerColumnNameResolverGapCoverageTest {

    private Driver registered;

    @AfterEach
    void unregister() throws Exception {
        if (registered != null) {
            DriverManager.deregisterDriver(registered);
            registered = null;
        }
    }

    @Test
    void nullAndBlankColumnNamesAreDropped() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, true, true, true, false);
        when(rs.getString(1)).thenReturn(null, "", "  ", "id", "name");

        Driver driver = mock(Driver.class);
        when(driver.acceptsURL(anyString())).thenReturn(true);
        when(driver.connect(anyString(), any(Properties.class))).thenReturn(connection);
        registered = driver;
        DriverManager.registerDriver(driver);

        DriverManagerMySQLColumnNameResolver resolver =
                new DriverManagerMySQLColumnNameResolver("jdbc:fake:c100d", "user", "pass", 5);

        assertEquals(List.of("id", "name"), resolver.resolve("db", "t"));
        resolver.close();
    }
}
