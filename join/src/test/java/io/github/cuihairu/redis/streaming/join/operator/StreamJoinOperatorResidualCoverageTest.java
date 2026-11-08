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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residual error/edge branches of {@link StreamJoinOperator} not exercised by the main and
 * waiting-mode suites: null join-key rejection, the inner-join event-time no-op, and the
 * corrupt-buffer-state restore failure.
 */
class StreamJoinOperatorResidualCoverageTest {

    /** Minimal keyed-state context mirroring the waiting-mode fixture. */
    private static final class TimedContext implements KeyedProcessFunction.Context {
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

    private static JoinFunction<String, String, String> fn() {
        return (l, r) -> (l == null ? "null" : l) + "+" + (r == null ? "null" : r);
    }

    private static JoinConfig<String, String, String> config(JoinType type) {
        return JoinConfig.<String, String, String>builder()
                .joinType(type)
                .joinWindow(JoinWindow.afterOnly(Duration.ofMillis(1000)))
                .leftKeySelector(l -> l)
                .rightKeySelector(r -> r)
                .build();
    }

    @Test
    void nullJoinKeyIsRejectedWithSideNamingMessage() {
        KeyedProcessFunction<String, Envelope<String, String, String>, String> fn =
                StreamJoinOperator.asKeyedProcessFunction(config(JoinType.LEFT), fn());
        TimedContext ctx = new TimedContext();
        List<String> out = new ArrayList<>();

        // The envelope's join key is null; processElement must reject it before any state use,
        // naming the offending side (left) so the caller can locate the bad selector.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fn.processElement(null, Envelope.forLeft(null, 100L, "p"), ctx, out::add));
        assertTrue(ex.getMessage().contains("left key selector returned null"),
                "message names the left side, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("join keys must be non-null"),
                "message states the non-null contract, got: " + ex.getMessage());
        assertTrue(out.isEmpty(), "no row may be emitted for a null-key element");
    }

    @Test
    void innerJoinEventTimeIsANoOp() throws Exception {
        // An inner join never emits unmatched outer rows, so its event-time close must return
        // immediately without touching state or emitting.
        KeyedProcessFunction<String, Envelope<String, String, String>, String> fn =
                StreamJoinOperator.asKeyedProcessFunction(config(JoinType.INNER), fn());
        TimedContext ctx = new TimedContext();
        List<String> out = new ArrayList<>();

        fn.onEventTime(1000L, "k", ctx, out::add);
        assertTrue(out.isEmpty(), "inner join must emit nothing at the close time");
    }

    @Test
    void corruptBufferStateFailsWithDescriptiveError() throws Exception {
        // The outer-join close only reads buffered state when unmatched rows are deferred
        // (emitUnmatchedImmediately=false); with the default immediate mode onEventTime
        // returns before touching state, so the corrupt restore path needs the deferred mode.
        KeyedProcessFunction<String, Envelope<String, String, String>, String> fn =
                StreamJoinOperator.asKeyedProcessFunction(
                        JoinConfig.<String, String, String>builder()
                                .joinType(JoinType.LEFT)
                                .joinWindow(JoinWindow.afterOnly(Duration.ofMillis(1000)))
                                .leftKeySelector(l -> l)
                                .rightKeySelector(r -> r)
                                .emitUnmatchedImmediately(false)
                                .build(),
                        fn());
        TimedContext ctx = new TimedContext();
        // A checkpointed buffer document that cannot be parsed must surface as a descriptive
        // RuntimeException naming the join state, not a silent Jackson error.
        ctx.store.computeIfAbsent("__operator:join:buffers", k -> new HashMap<>())
                .put("k", "{not-valid-json");
        List<String> out = new ArrayList<>();

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                fn.onEventTime(1000L, "k", ctx, out::add));
        assertTrue(ex.getMessage().contains("Failed to restore join buffer state"),
                "wrapper message names the buffer restore, got: " + ex.getMessage());
        assertTrue(out.isEmpty());
    }
}
