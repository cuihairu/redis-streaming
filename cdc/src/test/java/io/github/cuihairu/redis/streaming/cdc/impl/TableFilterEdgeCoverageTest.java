package io.github.cuihairu.redis.streaming.cdc.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases for {@link TableFilter#allowed(String, String)}: null database/table arguments and
 * wildcard include/exclude matching.
 */
class TableFilterEdgeCoverageTest {

    @Test
    void nullTableFallsBackToEmptyName() {
        TableFilter filter = TableFilter.from(null, null);
        assertTrue(filter.allowed("db", null), "no patterns: everything is allowed");

        TableFilter includes = TableFilter.from(List.of("orders*"), null);
        assertFalse(includes.allowed("db", null), "null table cannot match an include pattern");

        TableFilter excludes = TableFilter.from(null, List.of("orders"));
        assertTrue(excludes.allowed("db", null), "null table is not excluded by a plain name");
    }

    @Test
    void nullOrEmptyDatabaseMatchesOnTableName() {
        TableFilter filter = TableFilter.from(List.of("orders"), null);
        assertTrue(filter.allowed(null, "orders"));
        assertTrue(filter.allowed("", "orders"));
        assertTrue(filter.allowed("shop", "orders"));
        assertFalse(filter.allowed("shop", "payments"));
    }

    @Test
    void excludesWinOverIncludes() {
        TableFilter filter = TableFilter.from(List.of("*"), List.of("*.secret"));
        assertTrue(filter.allowed("db", "orders"));
        assertFalse(filter.allowed("db", "secret"), "full name db.secret is excluded");
    }
}
