package io.github.cuihairu.redis.streaming.join.operator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinMatching;
import io.github.cuihairu.redis.streaming.join.TimestampedElement;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operatorization of the stream-stream join: adapts the {@code StreamJoiner} windowed join
 * semantics to a {@link KeyedProcessFunction} so that a join can run inside a DataStream
 * pipeline on either execution engine. See {@code docs/Join-CEP-Operators-Design.md} for
 * the input-model decision (envelope multiplexing through one join-input stream).
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
 * <p>Semantics follow the raw joiner's (window predicate, outer-join double emission,
 * retention/maxStateSize eviction are delegated to the shared per-key core): left-anchored
 * window predicate, outer joins emit the unmatched element immediately and re-emit when a
 * peer arrives later.
 *
 * <p><b>State.</b> Both side buffers of a key are persisted into keyed {@link ValueState}
 * (one JSON document per key, obtained from {@code ctx.getState}), so buffers survive a
 * failover and pairing resumes from the checkpointed state instead of starting empty —
 * the join output is at-least-once over redelivery (a redelivered envelope may pair
 * again; deduplicate downstream if that matters). Two scope adjustments versus the raw
 * joiner's instance-wide bookkeeping, both hygiene-only (the window predicate guards
 * matching, retention/eviction are memory management):
 *
 * <ul>
 * <li>{@code maxStateSize} bounds the elements buffered <em>per key</em> (both sides
 * together) rather than the raw joiner's per-instance total across keys;</li>
 * <li>retention cleanup references the key's own newest timestamp, so an idle key's stale
 * elements live until that key's next event (or eviction) instead of being swept by
 * another key's activity.</li>
 * </ul>
 *
 * <p>Buffered elements are stored as self-describing JSON (runtime class recorded per
 * element), so either side decodes independently after a failover regardless of which
 * side's envelope arrives first.
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
        config.validate();
        return new JoinProcessFunction<>(config, joinFunction);
    }

    private static final class JoinProcessFunction<K, L, R, O>
            implements KeyedProcessFunction<K, Envelope<K, L, R>, O>, Serializable {

        private static final long serialVersionUID = 1L;

        private final JoinConfig<L, R, K> config;
        private final JoinFunction<L, R, O> joinFunction;
        /**
         * Fixed and distinctive: the Redis engine keys state by (job, topic, group, keyBy
         * operatorId, this name), and only a deterministic name lets a restarted job find
         * the previous instance's buffers. Two join functions cannot share a keyed stream
         * (process() returns a DataStream, not a KeyedStream), so one name per keyed stream
         * is collision-free.
         */
        private final String stateName = "__operator:join:buffers";
        private transient ObjectMapper mapper;

        JoinProcessFunction(JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) {
            this.config = config;
            this.joinFunction = joinFunction;
        }

        @Override
        public void processElement(K key, Envelope<K, L, R> value, Context ctx, Collector<O> out) throws Exception {
            // The envelope's key and timestamp are authoritative on the operator path; the
            // stream key (first argument) is only the routing partition the record arrived on.
            requireJoinKey(value);
            ValueState<String> state = ctx.getState(new StateDescriptor<>(stateName, String.class));
            SideBuffers buffers = readBuffers(state);

            List<O> results;
            if (value.getSide() == Envelope.Side.LEFT) {
                L element = value.getLeft();
                long timestamp = value.getTimestamp();
                buffers.left.add(new TimestampedElement<>(element, timestamp));
                evictBeyondCap(buffers);
                results = JoinMatching.matchArrivingLeft(config, joinFunction, element, timestamp, buffers.right);
            } else {
                R element = value.getRight();
                long timestamp = value.getTimestamp();
                buffers.right.add(new TimestampedElement<>(element, timestamp));
                evictBeyondCap(buffers);
                results = JoinMatching.matchArrivingRight(config, joinFunction, element, timestamp, buffers.left);
            }
            cleanup(buffers);
            writeBuffers(state, buffers);
            for (O result : results) {
                out.collect(result);
            }
        }

        private SideBuffers readBuffers(ValueState<String> state) {
            String json = state.value();
            if (json == null || json.isEmpty()) {
                return new SideBuffers(new ArrayList<>(), new ArrayList<>());
            }
            try {
                JoinKeyBuffers doc = mapper().readValue(json, JoinKeyBuffers.class);
                return new SideBuffers(decode(doc.left), decode(doc.right));
            } catch (Exception e) {
                throw new RuntimeException("Failed to restore join buffer state for " + stateName, e);
            }
        }

        private void writeBuffers(ValueState<String> state, SideBuffers buffers) throws Exception {
            if (buffers.left.isEmpty() && buffers.right.isEmpty()) {
                state.clear();
                return;
            }
            JoinKeyBuffers doc = new JoinKeyBuffers();
            doc.left = encode(buffers.left);
            doc.right = encode(buffers.right);
            state.update(mapper().writeValueAsString(doc));
        }

        private List<BufferedElement> encode(List<? extends TimestampedElement<?>> list) throws Exception {
            List<BufferedElement> out = new ArrayList<>(list.size());
            for (TimestampedElement<?> elem : list) {
                BufferedElement encoded = new BufferedElement();
                encoded.timestamp = elem.timestamp();
                if (elem.element() != null) {
                    encoded.type = elem.element().getClass().getName();
                    encoded.json = mapper().writeValueAsString(elem.element());
                }
                out.add(encoded);
            }
            return out;
        }

        @SuppressWarnings("unchecked") // runtime element type is carried per element in the document
        private <T> List<TimestampedElement<T>> decode(List<BufferedElement> list) throws Exception {
            List<TimestampedElement<T>> out = new ArrayList<>(list.size());
            Map<String, Class<?>> classes = new HashMap<>();
            for (BufferedElement encoded : list) {
                T element = null;
                if (encoded.json != null && encoded.type != null) {
                    Class<?> type = classes.computeIfAbsent(encoded.type, this::resolveType);
                    element = (T) mapper().readValue(encoded.json, type);
                }
                out.add(new TimestampedElement<>(element, encoded.timestamp));
            }
            return out;
        }

        private Class<?> resolveType(String name) {
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                if (loader == null) {
                    loader = getClass().getClassLoader();
                }
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Cannot resolve buffered element type " + name, e);
            }
        }

        /** Per-key {@code maxStateSize}: evict the oldest element while the pair holds too many. */
        private void evictBeyondCap(SideBuffers buffers) {
            int cap = config.getMaxStateSize();
            while (buffers.left.size() + buffers.right.size() > cap) {
                if (!JoinMatching.removeOldestAcross(buffers.left, buffers.right)) {
                    return;
                }
            }
        }

        /** Retention hygiene scoped to this key: newest timestamp of the pair is the reference. */
        private void cleanup(SideBuffers buffers) {
            long reference = Math.max(
                    JoinMatching.maxTimestamp(buffers.left), JoinMatching.maxTimestamp(buffers.right));
            if (reference > 0) {
                long retention = config.getStateRetentionTime();
                JoinMatching.removeExpired(buffers.left, reference, retention);
                JoinMatching.removeExpired(buffers.right, reference, retention);
            }
        }

        /**
         * Reject a null join key with a message naming the offending side, mirroring the raw
         * joiner's guard (its buffers cannot hold null keys; here the state store could, but
         * a null key never receives its peers).
         */
        private static void requireJoinKey(Envelope<?, ?, ?> value) {
            if (value.getJoinKey() == null) {
                boolean isLeft = value.getSide() == Envelope.Side.LEFT;
                throw new IllegalArgumentException(
                        (isLeft ? "left" : "right") + " key selector returned null for element "
                                + (isLeft ? value.getLeft() : value.getRight())
                                + " — join keys must be non-null");
            }
        }

        private ObjectMapper mapper() {
            if (mapper == null) {
                mapper = new ObjectMapper()
                        // payloads may carry java.time types (Instant etc.); register the jsr310
                        // module when present, mirroring the mq module's mapper setup
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            }
            return mapper;
        }

        /** The two side buffers of the key currently being processed. */
        private final class SideBuffers {
            final List<TimestampedElement<L>> left;
            final List<TimestampedElement<R>> right;

            SideBuffers(List<TimestampedElement<L>> left, List<TimestampedElement<R>> right) {
                this.left = left;
                this.right = right;
            }
        }
    }

    /** Keyed state document for one join key (JSON-encoded by the operator). */
    public static final class JoinKeyBuffers {
        public List<BufferedElement> left = new ArrayList<>();
        public List<BufferedElement> right = new ArrayList<>();
    }

    /** One buffered element: self-describing JSON so either side decodes independently. */
    public static final class BufferedElement {
        public String type;
        public String json;
        public long timestamp;
    }
}
