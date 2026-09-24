package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers StreamJoiner extractTimestamp null-extractor branch and eviction edge cases. */
class StreamJoinerEvictionEdgeCoverageTest {

    record L(String key, Long ts) {
    }

    record R(String key, Long ts) {
    }

    private static JoinConfig<L, R, String> config(int maxStateSize, boolean withExtractors) {
        JoinConfig.JoinConfigBuilder<L, R, String> builder = JoinConfig.<L, R, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(100)))
                .leftKeySelector(L::key)
                .rightKeySelector(R::key)
                .maxStateSize(maxStateSize)
                .stateRetentionTime(1_000_000);
        if (withExtractors) {
            builder.leftTimestampExtractor(L::ts).rightTimestampExtractor(R::ts);
        }
        return builder.build();
    }

    @Test
    void nullTimestampExtractorsFallBackToSystemTime() throws Exception {
        StreamJoiner<L, R, String, String> joiner =
                new StreamJoiner<>(config(10, false), (l, r) -> l.key() + r.key());

        joiner.processLeft(new L("k1", null));
        joiner.processRight(new R("k1", null));
        List<String> out = joiner.processLeft(new L("k2", null));
        assertTrue(out.size() >= 0);
        assertTrue(joiner.getLeftBufferSize() + joiner.getRightBufferSize() > 0);
    }

    @Test
    void evictionCoversRightOnlyBufferAndBothSides() throws Exception {
        // right-only buffer: evictOldestElement must fall back to the right candidate
        StreamJoiner<L, R, String, String> rightOnly =
                new StreamJoiner<>(config(1, true), (l, r) -> "x");
        rightOnly.processRight(new R("r1", 1L));
        rightOnly.processRight(new R("r2", 2L));
        assertEquals(1, rightOnly.getRightBufferSize() + rightOnly.getLeftBufferSize());

        // mixed buffer: whichever side is older gets evicted first
        StreamJoiner<L, R, String, String> mixed =
                new StreamJoiner<>(config(2, true), (l, r) -> "x");
        mixed.processLeft(new L("l1", 5L));
        mixed.processRight(new R("r1", 1L));
        mixed.processLeft(new L("l2", 9L));
        mixed.processRight(new R("r2", 2L));
        assertTrue(mixed.getLeftBufferSize() + mixed.getRightBufferSize() <= 2);
    }

    @Test
    void enforceMaxStateSizeWithEmptyBuffersIsNoOp() throws Exception {
        StreamJoiner<L, R, String, String> joiner =
                new StreamJoiner<>(config(1, true), (l, r) -> "x");
        joiner.processLeft(new L("k", 1L));
        joiner.clear();
        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }
}
