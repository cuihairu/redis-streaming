package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the null-join-key guard: the selector returning null used to
 * surface as a bare NPE from the ConcurrentHashMap buffers, with no hint which side
 * produced it.
 */
class StreamJoinerNullKeyGuardTest {

    @Test
    void leftSelectorReturningNullIsRejectedWithSideName() {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(s -> null)
                .rightKeySelector(s -> s)
                .build();
        StreamJoiner<String, String, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> joiner.processLeft("evt"));
        assertTrue(ex.getMessage().contains("left"), ex.getMessage());
        assertEquals(0, joiner.getLeftBufferSize());
    }

    @Test
    void rightSelectorReturningNullIsRejectedWithSideName() {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(s -> s)
                .rightKeySelector(s -> null)
                .build();
        StreamJoiner<String, String, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> joiner.processRight("evt"));
        assertTrue(ex.getMessage().contains("right"), ex.getMessage());
        assertEquals(0, joiner.getRightBufferSize());
    }
}
