package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamJoinerMaxStateSizeTest {

    private record Left(String key, long ts) {
    }

    private record Right(String key, long ts) {
    }

    @Test
    void enforcesMaxStateSizeByEvictingOldest() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(2)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key() + ":" + r.key());

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 1));
        joiner.processLeft(new Left("k3", 2));

        assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() <= 2);

        // Joining with the newest left should still be possible after eviction.
        assertEquals(1, joiner.processRight(new Right("k3", 3)).size());
        assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() <= 2);
    }
}
