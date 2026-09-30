package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
                .stateRetentionTime(20_000) // must cover the window span (validation requires it)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k", 0));
        joiner.processRight(new Right("k", 0));
        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(1, joiner.getRightBufferSize());

        // advancing max seen ts past 0 + retention evicts both t=0 elements; a peer at
        // 20001 no longer matches them anyway (20001 - 0 is outside the 20s window)
        joiner.processLeft(new Left("k", 20_001));

        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void cleanupRemovesOldLeftElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 201));

        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void cleanupRemovesOldRightElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processRight(new Right("k1", 0));
        joiner.processRight(new Right("k2", 201));

        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(1, joiner.getRightBufferSize());
    }

    @Test
    void cleanupPreservesRecentElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 50));
        joiner.processLeft(new Left("k3", 100));

        // all within retention of the max seen ts (100)
        assertEquals(3, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupWithDifferentKeys() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 201));

        assertEquals(1, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupWithMinimalRetentionTime() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // minimum legal value: exactly the window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 201));

        // 201 - 0 > retention: k1 is now outside both the window and the retention
        assertEquals(1, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupAffectsBothBuffers() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processRight(new Right("k1", 0));
        joiner.processLeft(new Left("k2", 201));
        joiner.processRight(new Right("k2", 201));

        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(1, joiner.getRightBufferSize());
    }

    @Test
    void cleanupAfterJoin() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key + "-" + r.key);

        List<String> result1 = joiner.processLeft(new Left("k", 0));
        List<String> result2 = joiner.processRight(new Right("k", 30));
        List<String> result3 = joiner.processRight(new Right("k", 100));

        assertEquals(0, result1.size());
        assertEquals(1, result2.size());
        // Join happens BEFORE cleanup, so result3 still finds the left element at timestamp 0
        assertEquals(1, result3.size());

        // advance the clock: k's left element (t=0) ages past retention while k2 is fresh
        joiner.processLeft(new Left("k2", 201));
        assertEquals(1, joiner.getLeftBufferSize()); // only k2's element remains
    }

    @Test
    void cleanupWithLargeRetentionTime() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(10000)
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 100));
        joiner.processLeft(new Left("k3", 200));

        assertEquals(3, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupRemovesEmptyKeyEntries() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 201));

        // k1 should be cleaned up, k2 should remain
        assertEquals(1, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupWithLeftJoinPreservesUnmatched() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> l.key);

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 201));

        assertEquals(1, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupDoesNotAffectCurrentTimestampElements() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 100));
        joiner.processRight(new Right("k1", 100));
        joiner.processLeft(new Left("k2", 301));

        assertEquals(1, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void cleanupWithMultipleElementsSameKey() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofMillis(100)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(200) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k", 0));
        joiner.processLeft(new Left("k", 350));
        joiner.processLeft(new Left("k", 100));
        joiner.processLeft(new Left("k", 500));

        // relative to max seen (500): 0 and 100 are beyond retention, 350 and 500 remain
        assertEquals(2, joiner.getLeftBufferSize());
    }

    @Test
    void cleanupWithClearRemovesAllState() throws Exception {
        JoinConfig<Left, Right, String> config = JoinConfig.<Left, Right, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(Left::key)
                .rightKeySelector(Right::key)
                .leftTimestampExtractor(Left::ts)
                .rightTimestampExtractor(Right::ts)
                .stateRetentionTime(20_000) // == window span
                .build();

        StreamJoiner<Left, Right, String, String> joiner = new StreamJoiner<>(config, (l, r) -> "x");

        joiner.processLeft(new Left("k1", 0));
        joiner.processLeft(new Left("k2", 50));
        joiner.processRight(new Right("k1", 25));

        assertEquals(2, joiner.getLeftBufferSize());
        assertEquals(1, joiner.getRightBufferSize());

        joiner.clear();

        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }
}

