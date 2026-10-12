package io.github.cuihairu.redis.streaming.join.operator;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the operator's process function directly through a keyed-state context (same
 * contract both engines provide via {@code ctx.getState}) and pins envelope-path behavior
 * against the raw {@link StreamJoiner} selector path: the two must produce identical
 * outputs for identical element sequences. The shared fake store also stands in for a
 * restart: a fresh operator instance pointed at the same store must resume from the
 * previous instance's buffered state.
 */
class StreamJoinOperatorTest {

    /** Minimal keyed value state: one JSON document per (state name, current key). */
    private static final class StatefulContext implements KeyedProcessFunction.Context {
        final Map<String, Map<String, String>> store = new HashMap<>();
        String currentKey = "k";

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

        @Override
        public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
            return new ValueState<>() {
                @Override
                @SuppressWarnings("unchecked")
                public S value() {
                    Map<String, String> byKey = store.get(stateDescriptor.getName());
                    String json = byKey == null ? null : byKey.get(currentKey);
                    return json == null ? stateDescriptor.getDefaultValue() : (S) json;
                }

                @Override
                public void update(S value) {
                    store.computeIfAbsent(stateDescriptor.getName(), k -> new HashMap<>())
                            .put(currentKey, (String) value);
                }

                @Override
                public void clear() {
                    Map<String, String> byKey = store.get(stateDescriptor.getName());
                    if (byKey != null) {
                        byKey.remove(currentKey);
                    }
                }
            };
        }
    }

    private static Envelope<String, String, String> left(String key, long ts, String payload) {
        return Envelope.forLeft(key, ts, payload);
    }

    private static Envelope<String, String, String> right(String key, long ts, String payload) {
        return Envelope.forRight(key, ts, payload);
    }

    private static <L, R, T> List<T> feed(KeyedProcessFunction<String, Envelope<String, L, R>, T> fn,
                                          StatefulContext ctx,
                                          List<Envelope<String, L, R>> envelopes) throws Exception {
        List<T> out = new ArrayList<>();
        KeyedProcessFunction.Collector<T> collector = out::add;
        for (Envelope<String, L, R> e : envelopes) {
            ctx.currentKey = e.getJoinKey();
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
                new StatefulContext(),
                List.of(left("k1", 1000L, "k1:left"), right("k1", 5000L, "k1:right")));
        assertEquals(viaJoiner, viaOperator);
        assertEquals(List.of("k1:left+k1:right"), viaOperator);

        // right arrives first, then left — operator buffers and pairs the same way
        List<String> reversed = feed(StreamJoinOperator.asKeyedProcessFunction(innerConfig(), fn),
                new StatefulContext(),
                List.of(right("k1", 5000L, "k1:right"), left("k1", 1000L, "k1:left")));
        assertEquals(List.of("k1:left+k1:right"), reversed);

        // outside the window: no output
        List<String> outside = feed(StreamJoinOperator.asKeyedProcessFunction(innerConfig(), fn),
                new StatefulContext(),
                List.of(left("k1", 1000L, "k1:left"), right("k1", 60_000L, "k1:right")));
        assertTrue(outside.isEmpty());
    }

    @Test
    void differentKeysNeverMix() throws Exception {
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(innerConfig(), (l, r) -> l + "+" + r);

        List<String> out = feed(op, new StatefulContext(), List.of(
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

        List<String> out = feed(op, new StatefulContext(), List.of(
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
                () -> feed(op, new StatefulContext(), List.of(bad)));
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

        List<String> out = feed(op, new StatefulContext(), List.of(
                left("k", 1000L, "k:left"),
                right("k", 3000L, "k:right")));
        // full outer over an after-only window: left emitted unmatched immediately, right
        // arrives inside the window and pairs
        assertEquals(List.of("k:left+null", "k:left+k:right"), out);
    }

    @Test
    void buffersSurviveAFreshFunctionInstance() throws Exception {
        // One store = one engine's keyed state; a restart constructs a NEW function instance
        // but finds the same documents.
        StatefulContext ctx = new StatefulContext();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> first =
                StreamJoinOperator.asKeyedProcessFunction(innerConfig(), (l, r) -> l + "+" + r);

        // left arrives, no right yet — buffered in state, nothing emitted
        assertTrue(feed(first, ctx, List.of(left("k", 1000L, "k:left"))).isEmpty());

        // restart: a fresh instance over the same state completes the pair
        KeyedProcessFunction<String, Envelope<String, String, String>, String> restarted =
                StreamJoinOperator.asKeyedProcessFunction(innerConfig(), (l, r) -> l + "+" + r);
        List<String> out = feed(restarted, ctx, List.of(right("k", 5000L, "k:right")));
        assertEquals(List.of("k:left+k:right"), out);
    }

    @Test
    void retentionCleanupDropsExpiredElementsFromKeyedState() throws Exception {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(1)))
                .leftKeySelector(l -> l.split(":")[0])
                .rightKeySelector(r -> r.split(":")[0])
                .stateRetentionTime(2000L)
                .build();
        StatefulContext ctx = new StatefulContext();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r);

        // unmatched left: emitted immediately (B-36) and buffered
        assertEquals(List.of("k:left+null"), feed(op, ctx, List.of(left("k", 1000L, "k:left"))));
        String afterLeft = ctx.store.get("__operator:join:buffers").get("k");
        assertEquals(1, MAPPER.readValue(afterLeft, StreamJoinOperator.JoinKeyBuffers.class).left.size());

        // a right arrival far past retention sweeps the stale left element out of the
        // key's document (the arriving element itself is always retention-fresh)
        feed(op, ctx, List.of(right("k", 1_000_000L, "k:right")));
        StreamJoinOperator.JoinKeyBuffers doc =
                MAPPER.readValue(ctx.store.get("__operator:join:buffers").get("k"), StreamJoinOperator.JoinKeyBuffers.class);
        assertTrue(doc.left.isEmpty(), "expired left must be dropped from state");
        assertEquals(1, doc.right.size());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void perKeyMaxStateSizeEvictsOldestWithinTheKey() throws Exception {
        JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
                .leftKeySelector(l -> l.split(":")[0])
                .rightKeySelector(r -> r.split(":")[0])
                .maxStateSize(3)
                .build();
        StatefulContext ctx = new StatefulContext();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r);

        // three lefts fit the cap; the right's arrival pushes the pair to 4 elements, the
        // oldest left (ts=1000) is evicted, and the two newer lefts still match
        List<String> out = feed(op, ctx, List.of(
                left("k", 1000L, "k:old"),
                left("k", 2000L, "k:mid"),
                left("k", 3000L, "k:new"),
                right("k", 4000L, "k:right")));
        assertEquals(List.of("k:mid+k:right", "k:new+k:right"), out);
    }

    @Test
    void pojoPayloadsRoundTripThroughStateJson() throws Exception {
        JoinConfig<Order, Order, String> config = JoinConfig.<Order, Order, String>innerJoin(
                Order::id, Order::id, JoinWindow.ofSize(Duration.ofSeconds(10)));
        StatefulContext ctx = new StatefulContext();
        KeyedProcessFunction<String, Envelope<String, Order, Order>, String> op =
                StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l.id() + ":" + l.amount() + "+" + r.amount());

        // separate function instances = the buffered left crosses a JSON round-trip
        assertTrue(feed(op, ctx, List.of(Envelope.forLeft("o1", 1000L, new Order("o1", 42)))).isEmpty());
        List<String> out = feed(op, ctx, List.of(Envelope.forRight("o1", 2000L, new Order("o1", 7))));
        assertEquals(List.of("o1:42+7"), out);
    }

    record Order(String id, int amount) {
    }
}
