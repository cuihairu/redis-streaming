package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the residual {@link StreamJoiner} branches: LEFT/RIGHT/FULL_OUTER
 * unmatched emissions, the out-of-window rejection on the right side, the
 * defensive {@code removeAt} guards and the eviction stop-guard on empty state.
 */
class StreamJoinerResidualCoverageTest {

    record L(String key, long ts) {
    }

    record R(String key, long ts) {
    }

    private static JoinConfig<L, R, String> config(JoinType type) {
        return JoinConfig.<L, R, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(L::key)
                .rightKeySelector(R::key)
                .leftTimestampExtractor(L::ts)
                .rightTimestampExtractor(R::ts)
                .maxStateSize(100)
                .build();
    }

    private static StreamJoiner<L, R, String, String> joiner(JoinType type) {
        return new StreamJoiner<>(config(type),
                (l, r) -> r == null ? l.key() + "->null" : l == null ? "null->" + r.key() : l.key() + "->" + r.key());
    }

    @Test
    void leftJoinEmitsNullRightForUnmatchedLeft() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.LEFT);
        List<String> out = j.processLeft(new L("k", 1000));
        assertEquals(List.of("k->null"), out, "an unmatched left must emit its null-right partner");
    }

    @Test
    void leftJoinEmitsSingleJoinedPairWhenRightArrivesFirst() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.LEFT);
        assertTrue(j.processRight(new R("k", 1050)).isEmpty(), "LEFT joins never emit from the right side alone");

        List<String> out = j.processLeft(new L("k", 1000));
        assertEquals(List.of("k->k"), out, "the matched pair must produce exactly one joined output");
    }

    @Test
    void rightJoinEmitsNullLeftForUnmatchedRight() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.RIGHT);
        List<String> out = j.processRight(new R("k", 1000));
        assertEquals(List.of("null->k"), out, "an unmatched right must emit its null-left partner");
    }

    @Test
    void rightJoinEmitsSingleJoinedPairWhenLeftArrivesFirst() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.RIGHT);
        assertTrue(j.processLeft(new L("k", 1000)).isEmpty(), "RIGHT joins never emit from the left side alone");

        List<String> out = j.processRight(new R("k", 1050));
        assertEquals(List.of("k->k"), out);
    }

    @Test
    void fullOuterEmitsNullRightForUnmatchedLeft() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.FULL_OUTER);
        List<String> out = j.processLeft(new L("k", 1000));
        assertEquals(List.of("k->null"), out);
    }

    @Test
    void innerJoinStaysSilentWithoutMatch() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.INNER);
        assertTrue(j.processLeft(new L("k", 1000)).isEmpty());
    }

    @Test
    void rightSideRejectsLeftElementsOutsideTheWindow() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.INNER);
        j.processLeft(new L("k", 1000));

        List<String> out = j.processRight(new R("k", 10_000_000));
        assertTrue(out.isEmpty(), "a left element far outside the window must not join");
    }

    @Test
    void removeAtGuardsAgainstMissingKeysAndBadIndexes() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.INNER);
        Map<Object, List<Object>> buffer = new HashMap<>();
        List<Object> list = new ArrayList<>(List.of("a", "b"));
        buffer.put("k", list);

        Method removeAt = StreamJoiner.class.getDeclaredMethod("removeAt", Map.class, Object.class, int.class);
        removeAt.setAccessible(true);

        removeAt.invoke(j, buffer, "missing", 0);
        removeAt.invoke(j, buffer, "k", -1);
        removeAt.invoke(j, buffer, "k", 2);
        assertEquals(List.of("a", "b"), list, "guarded calls must leave the buffer untouched");

        removeAt.invoke(j, buffer, "k", 0);
        assertEquals(List.of("b"), list, "removing one of two elements keeps the key");

        removeAt.invoke(j, buffer, "k", 0);
        assertTrue(buffer.isEmpty(), "dropping the last element must drop the key too");
    }

    @Test
    void evictionStopsSafelyWhenNoElementIsLeft() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.INNER);

        Field configField = StreamJoiner.class.getDeclaredField("config");
        configField.setAccessible(true);
        JoinConfig<?, ?, ?> cfg = (JoinConfig<?, ?, ?>) configField.get(j);
        Field maxField = JoinConfig.class.getDeclaredField("maxStateSize");
        maxField.setAccessible(true);
        maxField.set(cfg, -1);

        Method enforce = StreamJoiner.class.getDeclaredMethod("enforceMaxStateSize");
        enforce.setAccessible(true);
        assertDoesNotThrow(() -> enforce.invoke(j), "the eviction loop must stop once nothing is left to evict");

        Method evict = StreamJoiner.class.getDeclaredMethod("evictOldestElement");
        evict.setAccessible(true);
        assertFalse((Boolean) evict.invoke(j), "evicting from empty state reports failure");
    }

    @Test
    void partialEvictionKeepsTheKeyAlive() throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(JoinType.INNER);
        j.processLeft(new L("k", 1000));
        j.processLeft(new L("k", 1001));

        assertEquals(2, j.getLeftBufferSize());

        Field configField = StreamJoiner.class.getDeclaredField("config");
        configField.setAccessible(true);
        JoinConfig<?, ?, ?> cfg = (JoinConfig<?, ?, ?>) configField.get(j);
        Field maxField = JoinConfig.class.getDeclaredField("maxStateSize");
        maxField.setAccessible(true);
        maxField.set(cfg, 1);

        Method enforce = StreamJoiner.class.getDeclaredMethod("enforceMaxStateSize");
        enforce.setAccessible(true);
        enforce.invoke(j);

        assertEquals(1, j.getLeftBufferSize(), "only the oldest element is evicted");
    }
}
