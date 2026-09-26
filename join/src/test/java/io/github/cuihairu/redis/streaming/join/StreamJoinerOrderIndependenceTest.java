package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for order-dependent matching with asymmetric join windows (B-02).
 *
 * <p>The window predicate must be anchored on the LEFT timestamp on both code paths
 * ({@code rightTs - leftTs} within {@code [-before, +after]}). The old processLeft path
 * anchored on the buffered right element instead, so {@code afterOnly}/{@code beforeOnly}
 * windows matched or dropped a pair depending on which element arrived first.
 */
class StreamJoinerOrderIndependenceTest {

    private static final long T = 100_000L;

    record L(String key, long ts) {
    }

    record R(String key, long ts) {
    }

    private static StreamJoiner<L, R, String, String> joiner(JoinWindow window) {
        JoinConfig<L, R, String> config = JoinConfig.<L, R, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(L::key)
                .rightKeySelector(R::key)
                .leftTimestampExtractor(L::ts)
                .rightTimestampExtractor(R::ts)
                .maxStateSize(100)
                .build();
        return new StreamJoiner<>(config, (l, r) -> l.key() + "->" + r.key());
    }

    /** Feeds L(T) then R(T + offset) and returns the total joined pairs. */
    private static int joinLeftFirst(JoinWindow window, long rightTs) throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(window);
        List<String> first = j.processLeft(new L("k", T));
        List<String> second = j.processRight(new R("k", rightTs));
        assertTrue(first.isEmpty(), "an INNER join never emits from one side alone");
        return second.size();
    }

    /** Feeds R(T + offset) then L(T) and returns the total joined pairs. */
    private static int joinRightFirst(JoinWindow window, long rightTs) throws Exception {
        StreamJoiner<L, R, String, String> j = joiner(window);
        List<String> first = j.processRight(new R("k", rightTs));
        List<String> second = j.processLeft(new L("k", T));
        assertTrue(first.isEmpty(), "an INNER join never emits from one side alone");
        return second.size();
    }

    private static void assertBothOrders(JoinWindow window, long rightTs, int expected) throws Exception {
        assertEquals(expected, joinLeftFirst(window, rightTs),
                "left-first order must evaluate the window relative to the left timestamp");
        assertEquals(expected, joinRightFirst(window, rightTs),
                "right-first order must evaluate the very same predicate");
    }

    @Test
    void afterOnlyWindowMatchesRightElementInTheFutureOnBothOrders() throws Exception {
        // window: rightTs - leftTs must lie in [0, +10s]; +5s qualifies
        assertBothOrders(JoinWindow.afterOnly(Duration.ofSeconds(10)), T + 5_000, 1);
    }

    @Test
    void afterOnlyWindowRejectsRightElementInThePastOnBothOrders() throws Exception {
        // -5s is outside [0, +10s] — the old code matched it on the left-first path
        assertBothOrders(JoinWindow.afterOnly(Duration.ofSeconds(10)), T - 5_000, 0);
    }

    @Test
    void beforeOnlyWindowMatchesRightElementInThePastOnBothOrders() throws Exception {
        // window: rightTs - leftTs must lie in [-10s, 0]; -5s qualifies
        assertBothOrders(JoinWindow.beforeOnly(Duration.ofSeconds(10)), T - 5_000, 1);
    }

    @Test
    void beforeOnlyWindowRejectsRightElementInTheFutureOnBothOrders() throws Exception {
        // +5s is outside [-10s, 0] — the old code matched it on the right-first path
        assertBothOrders(JoinWindow.beforeOnly(Duration.ofSeconds(10)), T + 5_000, 0);
    }

    @Test
    void asymmetricMixedWindowHonorsBothBoundsOnBothOrders() throws Exception {
        JoinWindow window = JoinWindow.of(Duration.ofSeconds(3), Duration.ofSeconds(7));
        assertBothOrders(window, T + 7_000, 1);  // exactly at the upper bound
        assertBothOrders(window, T - 3_000, 1);  // exactly at the lower bound
        assertBothOrders(window, T + 7_001, 0);  // beyond the upper bound
        assertBothOrders(window, T - 3_001, 0);  // beyond the lower bound
    }

    @Test
    void symmetricWindowBehaviourIsUnchangedByTheFix() throws Exception {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(10));
        assertBothOrders(window, T + 10_000, 1);
        assertBothOrders(window, T - 10_000, 1);
        assertBothOrders(window, T + 10_001, 0);
        assertBothOrders(window, T - 10_001, 0);
    }
}
