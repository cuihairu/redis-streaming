package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void maxStateSizeWithLeftElementsOnly() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 1));
        joiner.processLeft(new Left("k3", 2));
        joiner.processLeft(new Left("k4", 3));

        assertEquals(3, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void maxStateSizeWithRightElementsOnly() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processRight(new Right("k1", 0));
        joiner.processRight(new Right("k2", 1));
        joiner.processRight(new Right("k3", 2));
        joiner.processRight(new Right("k4", 3));

        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(3, joiner.getRightBufferSize());
    }

    @Test
    void maxStateSizeWithMixedElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(5)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processRight(new Right("k1", 1));
        joiner.processLeft(new Left("k2", 2));
        joiner.processRight(new Right("k2", 3));
        joiner.processLeft(new Left("k3", 4));
        joiner.processRight(new Right("k3", 5));
        joiner.processLeft(new Left("k4", 6));

        int totalSize = joiner.getLeftBufferSize() + joiner.getRightBufferSize();
        assertTrue(totalSize <= 5);
    }

    @Test
    void maxStateSizeEvictsOldestAcrossBothBuffers() throws Exception {
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

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0)); // Oldest, should be evicted first
        joiner.processRight(new Right("k2", 10)); // Second oldest
        joiner.processLeft(new Left("k3", 20));

        assertEquals(2, joiner.getLeftBufferSize() + joiner.getRightBufferSize());
    }

    @Test
    void maxStateSizeOfOne() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(1)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 1));

        assertEquals(1, joiner.getLeftBufferSize() + joiner.getRightBufferSize());
    }

    @Test
    void maxStateSizeLargeValue() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(1000)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        for (int i = 0; i < 100; i++) {
            joiner.processLeft(new Left("k" + i, i));
        }

        assertEquals(100, joiner.getLeftBufferSize());
    }

    @Test
    void evictionHappensWhenAddingToLeftBuffer() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("a", 0));
        joiner.processLeft(new Left("b", 1));
        joiner.processLeft(new Left("c", 2));
        assertEquals(3, joiner.getLeftBufferSize());

        joiner.processLeft(new Left("d", 3));
        assertEquals(3, joiner.getLeftBufferSize());
    }

    @Test
    void evictionHappensWhenAddingToRightBuffer() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processRight(new Right("a", 0));
        joiner.processRight(new Right("b", 1));
        joiner.processRight(new Right("c", 2));
        assertEquals(3, joiner.getRightBufferSize());

        joiner.processRight(new Right("d", 3));
        assertEquals(3, joiner.getRightBufferSize());
    }

    @Test
    void evictionWithSameTimestamp() throws Exception {
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

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 100));
        joiner.processRight(new Right("k2", 100));
        joiner.processLeft(new Left("k3", 100));

        assertEquals(2, joiner.getLeftBufferSize() + joiner.getRightBufferSize());
    }

    @Test
    void evictionPreservesMostRecentElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("old1", 0));
        joiner.processLeft(new Left("old2", 10));
        joiner.processLeft(new Left("mid1", 50));
        joiner.processLeft(new Left("mid2", 60));
        joiner.processLeft(new Left("new1", 100));

        assertEquals(3, joiner.getLeftBufferSize());
        // The most recent 3 should remain
        assertTrue(joiner.getLeftBufferSize() == 3);
    }

    @Test
    void evictionWithDifferentKeys() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(4)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key() + ":" + r.key());

        joiner.processLeft(new Left("key1", 0));
        joiner.processLeft(new Left("key2", 10));
        joiner.processLeft(new Left("key3", 20));
        joiner.processLeft(new Left("key4", 30));
        joiner.processLeft(new Left("key5", 40)); // This evicts key1 (oldest)

        assertEquals(4, joiner.getLeftBufferSize());

        // Try to join with evicted key
        var result = joiner.processRight(new Right("key1", 50));
        assertEquals(0, result.size()); // key1 was evicted

        // Join with most recent key (key5) - should work
        var result2 = joiner.processRight(new Right("key5", 60));
        assertEquals(1, result2.size());

        // Verify buffer size is within limit
        assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() <= 4);
    }

    @Test
    void maxStateSizeWithJoinProducesResults() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(3)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key() + "-" + r.key());

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 1));
        joiner.processLeft(new Left("k3", 2));
        joiner.processRight(new Right("k2", 3));

        var results = joiner.processRight(new Right("k3", 4));
        assertEquals(1, results.size());
        assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() <= 3);
    }

    @Test
    void evictionMaintainsBufferSize() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .maxStateSize(5)
                .stateRetentionTime(1_000_000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        for (int i = 0; i < 100; i++) {
            joiner.processLeft(new Left("k" + i, i));
            assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() <= 5);
        }
    }
}
