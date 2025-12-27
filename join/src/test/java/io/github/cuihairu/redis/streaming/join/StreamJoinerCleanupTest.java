package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamJoinerCleanupTest {

    private record Left(String key, long ts) {}
    private record Right(String key, long ts) {}

    @Test
    void cleanupRemovesElementsOlderThanRetentionWindow() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(10)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k", 0));
        joiner.processRight(new Right("k", 0));
        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(1, joiner.getRightBufferSize());

        joiner.processLeft(new Left("k", 100));

        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }
}

