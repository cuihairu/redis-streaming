package io.github.cuihairu.redis.streaming.registry.filter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers FilterBuilder.op null/empty-operator branch (all fluent methods pass non-empty operators).
 */
class FilterBuilderOpTest {

    @Test
    void opFallsBackToPlainKeyWhenOperatorMissing() throws Exception {
        Method op = FilterBuilder.class.getDeclaredMethod("op", String.class, String.class);
        op.setAccessible(true);
        assertEquals("k", op.invoke(null, "k", null));
        assertEquals("k", op.invoke(null, "k", ""));
        assertEquals("k:==", op.invoke(null, "k", "=="));
    }
}
