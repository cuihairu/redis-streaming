package io.github.cuihairu.redis.streaming.join.operator;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 waiting/retract semantics for the outer join: with
 * {@code JoinConfig.emitUnmatchedImmediately=false} the unmatched outer-join row is held
 * until the element's window can no longer accept a peer, so a key emits exactly one row
 * per join partner instead of the B-36 immediate null row plus a second row when the peer
 * arrives. Pairs are still emitted on arrival; only the null row waits for the close time
 * ({@code ts + after} on the left, {@code ts - before} on the right).
 *
 * <p>The default ({@code emitUnmatchedImmediately=true}) keeps the historical immediate
 * double emission and is pinned by one test here so the two modes cannot silently drift.
 */
class StreamJoinOperatorWaitingTest {

    /** Keyed-state context that also records registered event-time timer times. */
    private static final class TimedContext implements KeyedProcessFunction.Context {
        final Map<String, Map<String, String>> store = new HashMap<>();
        final List<Long> eventTimers = new ArrayList<>();
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
            eventTimers.add(time);
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

    private static JoinConfig<String, String, String> waitingConfig(JoinType type) {
        return JoinConfig.<String, String, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.afterOnly(Duration.ofMillis(1000)))
                .leftKeySelector(l -> l.split(":")[0])
                .rightKeySelector(r -> r.split(":")[0])
                .emitUnmatchedImmediately(false)
                .build();
    }

    private static JoinConfig<String, String, String> immediateConfig(JoinType type) {
        return JoinConfig.<String, String, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.afterOnly(Duration.ofMillis(1000)))
                .leftKeySelector(l -> l.split(":")[0])
                .rightKeySelector(r -> r.split(":")[0])
                .build();
    }

    private static List<String> feed(KeyedProcessFunction<String, Envelope<String, String, String>, String> fn,
                                     TimedContext ctx,
                                     List<Envelope<String, String, String>> envelopes) throws Exception {
        List<String> out = new ArrayList<>();
        for (Envelope<String, String, String> e : envelopes) {
            ctx.currentKey = e.getJoinKey();
            fn.processElement(e.getJoinKey(), e, ctx, out::add);
        }
        return out;
    }

    /** Fire every recorded event-time timer in order, collecting the close emissions. */
    private static List<String> fireAll(KeyedProcessFunction<String, Envelope<String, String, String>, String> fn,
                                        TimedContext ctx) throws Exception {
        List<String> out = new ArrayList<>();
        for (long time : ctx.eventTimers) {
            fn.onEventTime(time, ctx.currentKey, ctx, out::add);
        }
        return out;
    }

    private static JoinFunction<String, String, String> fn() {
        return (l, r) -> (l == null ? "null" : l) + "+" + (r == null ? "null" : r);
    }

    @Test
    void waitingOuterJoinEmitsTheUnmatchedRowOnlyAtTheCloseTime() throws Exception {
        TimedContext ctx = new TimedContext();

        List<String> onArrival = feed(StreamJoinOperator.asKeyedProcessFunction(
                        waitingConfig(JoinType.LEFT), fn()), ctx,
                List.of(left("k1", 100L, "k1:left")));

        assertEquals(List.of(), onArrival, "the unmatched row must be held back until the close");
        assertEquals(List.of(1100L), ctx.eventTimers);

        assertEquals(List.of("k1:left+null"), fireAll(StreamJoinOperator.asKeyedProcessFunction(
                waitingConfig(JoinType.LEFT), fn()), ctx));
    }

    @Test
    void waitingOuterJoinEmitsThePairOnceAndSuppressesTheNullRow() throws Exception {
        TimedContext ctx = new TimedContext();

        List<String> onArrival = feed(StreamJoinOperator.asKeyedProcessFunction(
                        waitingConfig(JoinType.LEFT), fn()), ctx,
                List.of(left("k1", 100L, "k1:left"), right("k1", 200L, "k1:right")));

        assertEquals(List.of("k1:left+k1:right"), onArrival);

        // both close times fire: the pair is already out and each side still sees its
        // partner, so no null row may be added — one row for the one partner
        assertEquals(List.of(), fireAll(StreamJoinOperator.asKeyedProcessFunction(
                waitingConfig(JoinType.LEFT), fn()), ctx));
    }

    @Test
    void waitingOuterJoinSuppressesTheNullRowWhenThePartnerArrivesBeforeTheEarlierCloseFires() throws Exception {
        // window afterOnly(1000): the right element at ts=1099 closes at 1099 (before=0),
        // the left at ts=100 closes at 1100 — the partner is buffered at both close times
        TimedContext ctx = new TimedContext();

        List<String> onArrival = feed(StreamJoinOperator.asKeyedProcessFunction(
                        waitingConfig(JoinType.FULL_OUTER), fn()), ctx,
                List.of(right("k1", 1099L, "k1:right"), left("k1", 100L, "k1:left")));

        assertEquals(List.of("k1:left+k1:right"), onArrival);
        assertEquals(List.of(), fireAll(StreamJoinOperator.asKeyedProcessFunction(
                waitingConfig(JoinType.FULL_OUTER), fn()), ctx));
    }

    @Test
    void waitingFullOuterEmitsBothSidesNullRowsAtTheirOwnCloseTimes() throws Exception {
        TimedContext ctx = new TimedContext();

        List<String> onArrival = feed(StreamJoinOperator.asKeyedProcessFunction(
                        waitingConfig(JoinType.FULL_OUTER), fn()), ctx,
                List.of(left("k1", 100L, "k1:left"), right("k1", 5000L, "k1:right")));

        assertEquals(List.of(), onArrival);
        assertEquals(List.of(1100L, 5000L), ctx.eventTimers);
        assertEquals(List.of("k1:left+null", "null+k1:right"),
                fireAll(StreamJoinOperator.asKeyedProcessFunction(waitingConfig(JoinType.FULL_OUTER), fn()), ctx));
    }

    @Test
    void waitingInnerJoinRegistersNoCloseTimerAndEmitsNothing() throws Exception {
        TimedContext ctx = new TimedContext();

        List<String> out = feed(StreamJoinOperator.asKeyedProcessFunction(
                        waitingConfig(JoinType.INNER), fn()), ctx,
                List.of(left("k1", 100L, "k1:left")));

        assertEquals(List.of(), out);
        assertTrue(ctx.eventTimers.isEmpty(), "an inner join has no unmatched row to wait for");
    }

    @Test
    void waitingJoinKeepsTheHistoricalLatePeerRow() throws Exception {
        // The null row is emitted at the close time; a peer that arrives only after the close
        // still pairs (the buffered element is not dropped at close, matching the retention
        // model). That late-record double row is the pre-existing corner of the window
        // predicate, unchanged by waiting mode.
        TimedContext ctx = new TimedContext();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> fn =
                StreamJoinOperator.asKeyedProcessFunction(waitingConfig(JoinType.LEFT), fn());

        feed(fn, ctx, List.of(left("k1", 100L, "k1:left")));
        assertEquals(List.of("k1:left+null"), fireAll(fn, ctx));

        List<String> out = new ArrayList<>();
        ctx.currentKey = "k1";
        fn.processElement("k1", right("k1", 200L, "k1:right"), ctx, out::add);
        assertEquals(List.of("k1:left+k1:right"), out);
    }

    @Test
    void theDefaultImmediateModeStillEmitsTheUnmatchedRowOnArrival() throws Exception {
        TimedContext ctx = new TimedContext();

        List<String> out = feed(StreamJoinOperator.asKeyedProcessFunction(
                        immediateConfig(JoinType.LEFT), fn()), ctx,
                List.of(left("k1", 100L, "k1:left")));

        assertEquals(List.of("k1:left+null"), out, "the default keeps the B-36 immediate emission");
        assertTrue(ctx.eventTimers.isEmpty(), "immediate mode registers no close timers");
    }

    @Test
    void aRestartedWaitingJoinDoesNotDuplicateAWaitingRow() throws Exception {
        TimedContext ctx = new TimedContext();
        KeyedProcessFunction<String, Envelope<String, String, String>, String> first =
                StreamJoinOperator.asKeyedProcessFunction(waitingConfig(JoinType.LEFT), fn());

        feed(first, ctx, List.of(left("k1", 100L, "k1:left")));

        // a fresh instance over the same store resumes the buffered element: the pending row
        // is still exactly one, emitted once when the close time fires
        KeyedProcessFunction<String, Envelope<String, String, String>, String> restarted =
                StreamJoinOperator.asKeyedProcessFunction(waitingConfig(JoinType.LEFT), fn());
        assertEquals(List.of("k1:left+null"), fireAll(restarted, ctx));
    }
}
