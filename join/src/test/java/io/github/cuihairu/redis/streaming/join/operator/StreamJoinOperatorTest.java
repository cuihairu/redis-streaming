package io.github.cuihairu.redis.streaming.join.operator;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the operator's process function directly (trivial context/collector) and pins
 * envelope-path behavior against the raw {@link StreamJoiner} selector path: the two must
 * produce identical outputs for identical element sequences.
 */
class StreamJoinOperatorTest {

    private static Envelope<String, String, String> left(String key, long ts, String payload) {
        return Envelope.forLeft(key, ts, payload);
    }

    private static Envelope<String, String, String> right(String key, long ts, String payload) {
        return Envelope.forRight(key, ts, payload);
    }

    private static <T> List<T> feed(KeyedProcessFunction<String, Envelope<String, String, String>, T> fn,
                                    List<Envelope<String, String, String>> envelopes) throws Exception {
        List<T> out = new ArrayList<>();
        KeyedProcessFunction.Collector<T> collector = out::add;
        KeyedProcessFunction.Context ctx = new KeyedProcessFunction.Context() {
            @Override
            public long currentProcessingTime() {
                return 0;
            }

            @Override
            public long currentWatermark() {
                return 0;
            }

            @Override
            public void registerProcessingTimeTimer(long time) {
            }

            @Override
            public void registerEventTimeTimer(long time) {
            }
        };
        for (Envelope<String, String, String> e : envelopes) {
            fn.processElement(e.getJoinKey(), e, ctx, collector);
        }
        return out;
    }

    private static JoinConfig<String, String, String> innerConfig() {
        return JoinConfig.<String, String, String>innerJoin(
                l -> l.split(":")[0], r -> r.split(":")[0], JoinWindow.ofSize(Duration.ofSeconds(10)));
    }

    @Test
    void innerJoinPairsBothArrivalOrdersLikeStreamJoiner() throws Exception {
        JoinFunction<String, String, String> fn = (l, r) -> l + "+" + r;
        StreamJoiner<String, String, String, String> joiner = new StreamJoiner<>(innerConfig(), fn);

        // left arrives first, then right — operator output must equal the raw joiner's
        List<String> viaJoiner = new ArrayList<>();
        viaJoiner.addAll(joiner.processLeft("k1:left"));
        viaJoiner.addAll(joiner.processRight("k1:right"));
        List<String> viaOperator = feed(StreamJoinOperator.asKeyedProcessFunction(innerConfig(), fn),
                List.of(left("k1", 1000L, "k1:left"), right("k1", 5000L, "k1:right")));
        assertEquals(viaJoiner, viaOperator);
        assertEquals(List.of("k1:left+k1:right"), viaOperator);

        // right arrives first, then left — operator buffers and pairs the same way
        List<String> reversed = feed(StreamJoinOperator.asKeyedProcessFunction(innerConfig(), fn),
                List.of(right("k1", 5000L, "k1:right"), left("k1", 1000L, "k1:left")));
        assertEquals(List.of("k1:left+k1:right"), reversed);

        // outside the window: no output
        List<String> outside = feed(StreamJoinOperator.asKeyedProcessFunction(innerConfig(), fn),
                List.of(left("k1", 1000L, "k1:left"), right("k1", 60_000L, "k1:right")));
        assertTrue(outside.isEmpty());
    }

    @Test
    void differentKeysNeverMix() throws Exception {
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(innerConfig(), (l, r) -> l + "+" + r);

        List<String> out = feed(op, List.of(
                left("a", 1000L, "a:left"),
                left("b", 1000L, "b:left"),
                right("b", 2000L, "b:right"),
                right("a", 2000L, "a:right")));
        assertEquals(List.of("b:left+b:right", "a:left+a:right"), out);
    }

    @Test
    void leftJoinEmitsUnmatchedImmediatelyThenMatchLater() throws Exception {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>leftJoin(
                l -> l.split(":")[0], r -> r.split(":")[0], JoinWindow.ofSize(Duration.ofSeconds(10)));
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r);

        List<String> out = feed(op, List.of(
                left("k", 1000L, "k:left"),
                right("k", 5000L, "k:right")));
        // B-36 semantics: unmatched left emitted immediately, matched pair emitted again later
        assertEquals(List.of("k:left+null", "k:left+k:right"), out);
    }

    @Test
    void nullJoinKeyIsRejectedWithSideName() {
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(innerConfig(), (l, r) -> l + "+" + r);
        Envelope<String, String, String> bad = Envelope.forLeft(null, 1000L, "payload");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> feed(op, List.of(bad)));
        assertTrue(e.getMessage().contains("left"), e.getMessage());
    }

    @Test
    void joinTypeAndWindowFlowsThroughFromConfig() throws Exception {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.FULL_OUTER)
                .joinWindow(JoinWindow.afterOnly(Duration.ofSeconds(10)))
                .leftKeySelector(l -> l.split(":")[0])
                .rightKeySelector(r -> r.split(":")[0])
                .build();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r);

        List<String> out = feed(op, List.of(
                left("k", 1000L, "k:left"),
                right("k", 3000L, "k:right")));
        // full outer over an after-only window: left emitted unmatched immediately, right
        // arrives inside the window and pairs
        assertEquals(List.of("k:left+null", "k:left+k:right"), out);
    }
}
