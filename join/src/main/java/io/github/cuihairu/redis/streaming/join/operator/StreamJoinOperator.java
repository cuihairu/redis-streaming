package io.github.cuihairu.redis.streaming.join.operator;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;

import java.io.Serializable;
import java.util.List;

/**
 * Operatorization of the stream-stream join: adapts the {@link StreamJoiner} windowed
 * join semantics to a {@link KeyedProcessFunction} so that a join can run inside a
 * DataStream pipeline on either execution engine. See
 * {@code docs/Join-CEP-Operators-Design.md} for the input-model decision (envelope
 * multiplexing through one join-input stream) and the phase 1 consistency boundary.
 *
 * <p>Usage — the left and right sources map their records into {@link Envelope}s and are
 * merged into one keyed stream (in-memory: concatenate the sources; Redis runtime: both
 * producers write tagged envelopes to one join-input topic), then:
 *
 * <pre>{@code
 * env.fromMqTopic(joinInputTopic, group)
 *     .map(m -> objectMapper.readValue(m.getPayload(), Envelope.class))
 *     .keyBy(Envelope::getJoinKey)
 *     .process(StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "-" + r))
 *     .addSink(...);
 * }</pre>
 *
 * <p>Semantics are exactly {@link StreamJoiner}'s (the operator delegates to one joiner
 * instance whose internal buffers are keyed by {@link Envelope#getJoinKey()}): left-anchored
 * window predicate, outer joins emit the unmatched element immediately and re-emit when a
 * peer arrives later, retention/maxStateSize eviction per {@link JoinConfig}. Buffer state
 * lives in the operator instance (partition-local, not checkpointed in this phase).
 */
public final class StreamJoinOperator {

    private StreamJoinOperator() {
    }

    /**
     * Build the keyed process function for the join.
     *
     * @param config       join configuration (type, window, retention bounds); validated on construction
     * @param joinFunction the pairing function; invoked with a {@code null} half for unmatched outer-join emissions
     * @param <K>          join key type
     * @param <L>          left element type
     * @param <R>          right element type
     * @param <O>          output element type
     * @return a keyed process function consuming {@link Envelope}s and emitting joined outputs
     */
    public static <K, L, R, O> KeyedProcessFunction<K, Envelope<K, L, R>, O> asKeyedProcessFunction(
            JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) {
        return new JoinProcessFunction<>(config, joinFunction);
    }

    private static final class JoinProcessFunction<K, L, R, O>
            implements KeyedProcessFunction<K, Envelope<K, L, R>, O>, Serializable {

        private static final long serialVersionUID = 1L;

        private final StreamJoiner<L, R, K, O> joiner;

        JoinProcessFunction(JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) {
            this.joiner = new StreamJoiner<>(config, joinFunction);
        }

        @Override
        public void processElement(K key, Envelope<K, L, R> value, Context ctx, Collector<O> out) throws Exception {
            // The envelope's key and timestamp are authoritative on the operator path; the
            // stream key (first argument) is only the routing partition the record arrived on.
            List<O> results;
            if (value.getSide() == Envelope.Side.LEFT) {
                results = joiner.processLeft(value.getLeft(), value.getJoinKey(), value.getTimestamp());
            } else {
                results = joiner.processRight(value.getRight(), value.getJoinKey(), value.getTimestamp());
            }
            for (O result : results) {
                out.collect(result);
            }
        }
    }
}
