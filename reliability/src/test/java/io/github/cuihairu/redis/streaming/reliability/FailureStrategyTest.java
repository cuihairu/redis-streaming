package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FailureStrategy enum
 */
class FailureStrategyTest {

    @Test
    void testAllStrategiesExist() {
        // Given & When
        FailureStrategy[] strategies = FailureStrategy.values();

        // Then
        assertEquals(5, strategies.length);
    }

    @Test
    void testFailFastStrategy() {
        // Given & When
        FailureStrategy strategy = FailureStrategy.FAIL_FAST;

        // Then
        assertNotNull(strategy);
        assertEquals("FAIL_FAST", strategy.name());
    }

    @Test
    void testRetryStrategy() {
        // Given & When
        FailureStrategy strategy = FailureStrategy.RETRY;

        // Then
        assertNotNull(strategy);
        assertEquals("RETRY", strategy.name());
    }

    @Test
    void testSkipStrategy() {
        // Given & When
        FailureStrategy strategy = FailureStrategy.SKIP;

        // Then
        assertNotNull(strategy);
        assertEquals("SKIP", strategy.name());
    }

    @Test
    void testDeadLetterQueueStrategy() {
        // Given & When
        FailureStrategy strategy = FailureStrategy.DEAD_LETTER_QUEUE;

        // Then
        assertNotNull(strategy);
        assertEquals("DEAD_LETTER_QUEUE", strategy.name());
    }

    @Test
    void testIgnoreStrategy() {
        // Given & When
        FailureStrategy strategy = FailureStrategy.IGNORE;

        // Then
        assertNotNull(strategy);
        assertEquals("IGNORE", strategy.name());
    }

    @Test
    void testValueOf() {
        // Given & When
        FailureStrategy failFast = FailureStrategy.valueOf("FAIL_FAST");
        FailureStrategy retry = FailureStrategy.valueOf("RETRY");
        FailureStrategy skip = FailureStrategy.valueOf("SKIP");
        FailureStrategy dlq = FailureStrategy.valueOf("DEAD_LETTER_QUEUE");
        FailureStrategy ignore = FailureStrategy.valueOf("IGNORE");

        // Then
        assertEquals(FailureStrategy.FAIL_FAST, failFast);
        assertEquals(FailureStrategy.RETRY, retry);
        assertEquals(FailureStrategy.SKIP, skip);
        assertEquals(FailureStrategy.DEAD_LETTER_QUEUE, dlq);
        assertEquals(FailureStrategy.IGNORE, ignore);
    }

    @Test
    void testValueOfWithInvalidName() {
        // Given & When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            FailureStrategy.valueOf("INVALID");
        });
    }

    @Test
    void testEnumIsSerializable() {
        // Given & When
        boolean isSerializable = FailureStrategy.FAIL_FAST instanceof java.io.Serializable;

        // Then
        assertTrue(isSerializable);
    }

    @Test
    void testEnumOrder() {
        // Given & When
        FailureStrategy[] strategies = FailureStrategy.values();

        // Then - verify the order matches declaration
        assertEquals(FailureStrategy.FAIL_FAST, strategies[0]);
        assertEquals(FailureStrategy.RETRY, strategies[1]);
        assertEquals(FailureStrategy.SKIP, strategies[2]);
        assertEquals(FailureStrategy.DEAD_LETTER_QUEUE, strategies[3]);
        assertEquals(FailureStrategy.IGNORE, strategies[4]);
    }

    @Test
    void testEnumEquality() {
        // Given & When
        FailureStrategy strategy1 = FailureStrategy.RETRY;
        FailureStrategy strategy2 = FailureStrategy.RETRY;
        FailureStrategy strategy3 = FailureStrategy.SKIP;

        // Then
        assertEquals(strategy1, strategy2);
        assertNotEquals(strategy1, strategy3);
        assertEquals(strategy1.hashCode(), strategy2.hashCode());
    }

    @Test
    void testEnumOrdinal() {
        // Given & When & Then
        assertEquals(0, FailureStrategy.FAIL_FAST.ordinal());
        assertEquals(1, FailureStrategy.RETRY.ordinal());
        assertEquals(2, FailureStrategy.SKIP.ordinal());
        assertEquals(3, FailureStrategy.DEAD_LETTER_QUEUE.ordinal());
        assertEquals(4, FailureStrategy.IGNORE.ordinal());
    }

    @Test
    void testToString() {
        // Given & When
        String str = FailureStrategy.DEAD_LETTER_QUEUE.toString();

        // Then
        assertEquals("DEAD_LETTER_QUEUE", str);
    }

    @Test
    void testEnumConstants() {
        // Given & When
        FailureStrategy[] constants = FailureStrategy.class.getEnumConstants();

        // Then
        assertNotNull(constants);
        assertEquals(5, constants.length);
    }
}
