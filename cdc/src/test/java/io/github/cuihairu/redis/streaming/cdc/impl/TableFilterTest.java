package io.github.cuihairu.redis.streaming.cdc.impl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableFilterTest {

    @Test
    void allowAllWhenNoIncludesOrExcludes() {
        TableFilter filter = TableFilter.from(List.of(), List.of());
        assertTrue(filter.allowed("db", "orders"));
        assertTrue(filter.allowed(null, "orders"));
    }

    @Test
    void includesMatchEitherTableOrDbDotTable() {
        TableFilter filter = TableFilter.from(List.of("orders", "shop.*"), List.of());

        assertTrue(filter.allowed("any", "orders"));
        assertTrue(filter.allowed("shop", "orders"));
        assertTrue(filter.allowed("shop", "users"));

        assertFalse(filter.allowed("other", "users"));
        assertFalse(filter.allowed(null, "users"));
    }

    @Test
    void excludesOverrideIncludes() {
        TableFilter filter = TableFilter.from(List.of("*"), List.of("tmp_*", "shop.secret"));

        assertTrue(filter.allowed("shop", "orders"));
        assertFalse(filter.allowed("shop", "tmp_orders"));
        assertFalse(filter.allowed("shop", "secret"));
        assertTrue(filter.allowed("other", "secret"));
    }

    @Test
    void ignoresNullAndBlankPatterns() {
        TableFilter filter = TableFilter.from(Arrays.asList(" ", null, "orders"), Arrays.asList("", "  "));
        assertTrue(filter.allowed("db", "orders"));
        assertFalse(filter.allowed("db", "users"));
    }
}
