package io.github.cuihairu.redis.streaming.cdc.impl;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers TableFilter matching edges and DriverManagerMySQLColumnNameResolver resolve/close. */
class TableFilterAndResolverCoverageTest {

    @Test
    void allowedMatchesExactAndWildcardPatterns() {
        TableFilter filter = TableFilter.from(List.of("shop.*"), List.of("shop.audit"));
        assertTrue(filter.allowed("shop", "orders"));
        assertFalse(filter.allowed("shop", "audit"), "excludes win over includes");
        assertFalse(filter.allowed("other", "orders"));

        TableFilter empty = TableFilter.from(null, null);
        assertTrue(empty.allowed("any", "table"), "no patterns means allow all");

        TableFilter tableOnly = TableFilter.from(List.of("users"), null);
        assertTrue(tableOnly.allowed("shop", "users"), "pattern may match bare table name");
        assertFalse(tableOnly.allowed("shop", "orders"));
    }

    @Test
    void resolverQueriesAndCachesColumnNames() throws Exception {
        String db = "jdbc:h2:mem:res" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection keeper = DriverManager.getConnection(db, "sa", "")) {
            try (Statement st = keeper.createStatement()) {
                st.execute("CREATE TABLE person(id INT PRIMARY KEY, name VARCHAR(32))");
            }
            DriverManagerMySQLColumnNameResolver resolver =
                    new DriverManagerMySQLColumnNameResolver(db, "sa", "", 5);
            try {
                List<String> columns = resolver.resolve("PUBLIC", "PERSON");
                assertEquals(List.of("ID", "NAME"), columns);
                // second call is served from cache
                assertEquals(List.of("ID", "NAME"), resolver.resolve("PUBLIC", "PERSON"));
                assertTrue(resolver.resolve(null, "person").isEmpty());
                assertTrue(resolver.resolve("PUBLIC", " ").isEmpty());
            } finally {
                resolver.close();
            }
        }
    }

    @Test
    void resolverFailurePathReturnsEmptyList() {
        DriverManagerMySQLColumnNameResolver resolver =
                new DriverManagerMySQLColumnNameResolver("jdbc:h2:tcp://127.0.0.1:1/nope", "sa", "", 1);
        try {
            assertTrue(resolver.resolve("PUBLIC", "missing").isEmpty(), "connection failure tolerated");
        } finally {
            resolver.close();
        }
    }
}
