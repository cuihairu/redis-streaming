package io.github.cuihairu.redis.streaming.cep.operator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import io.github.cuihairu.redis.streaming.cep.PatternSequenceMatcher;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Operatorization of the CEP sequence matcher: adapts {@link PatternSequenceMatcher} to a
 * {@link KeyedProcessFunction} so that per-key pattern matching runs inside a DataStream
 * pipeline on either execution engine. See {@code docs/Join-CEP-Operators-Design.md}.
 *
 * <p>Each key gets its own matcher instance — sequences of different keys never mix. The
 * {@code within(...)} deadline of the pattern is enforced by the matcher's event-driven
 * cleanup (expired partial matches are dropped on every {@code process(event, timestamp)}
 * call); the operator registers no engine timers.
 *
 * <p><b>State.</b> Per-key partial matches are persisted into keyed {@link ValueState}
 * (one JSON document per key, obtained from {@code ctx.getState}) so a failover replays the
 * matcher from its checkpointed partials instead of starting over. The matcher instance in
 * memory is a write-through cache: it is hydrated from state on first sight of a key
 * (failover recovery) and flushed to state after every event. Two consequences worth
 * knowing:
 *
 * <ul>
 * <li>Every event writes state — that is the price of durability (one state write per
 * event per key, in addition to the element itself crossing the pipeline);</li>
 * <li>complete-match history is <em>not</em> checkpointed: it is inspection-only on the
 * matcher, and {@link PatternSequenceMatcher#process(Object, long)} delivers every match
 * to the pipeline as it happens.</li>
 * </ul>
 *
 * <p>Events are stored as JSON with the runtime class observed on the first event, so the
 * first event must be a representative instance of the event type (heterogeneous event
 * subtypes after the first event degrade to the first observed class — the same
 * convention the Redis runtime uses for window state).
 *
 * <p>Matchers are tracked per key up to {@code maxTrackedKeys} (default 10000, 0 =
 * unbounded); the least recently touched key's matcher is evicted beyond the cap so high
 * key cardinality cannot grow the cache without bound. Eviction only drops the in-memory
 * cache entry; the checkpointed partials of an evicted key stay put (per-key state access
 * cannot reach another key's document) and are discarded by the next flush of that key or
 * reused after a failover. Completed matches are emitted as they happen, so evicting a key
 * only loses its in-progress partial matches within this instance.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * env.fromMqTopic(eventsTopic, group)
 *     .map(m -> objectMapper.readValue(m.getPayload(), Event.class))
 *     .keyBy(Event::getUserId)
 *     .process(new PatternSequenceProcessFunction<>(pattern, Event::getTimestamp))
 *     .addSink(...);   // receives an EventSequence per completed match
 * }</pre>
 *
 * @param <K> The type of the key
 * @param <T> The type of events
 */
public final class PatternSequenceProcessFunction<K, T>
        implements KeyedProcessFunction<K, T, EventSequence<T>>, Serializable {

    private static final long serialVersionUID = 1L;

    /** Default cap on the number of per-key matchers kept (see class javadoc). */
    public static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    private final PatternSequence<T> patternSequence;
    private final Function<T, Long> timestampExtractor;
    private final int maxTrackedKeys;
    /**
     * Fixed and distinctive: the Redis engine keys state by (job, topic, group, keyBy
     * operatorId, this name), and only a deterministic name lets a restarted job find the
     * previous instance's partial matches. Two process functions cannot share a keyed
     * stream (process() returns a DataStream, not a KeyedStream), so one name per keyed
     * stream is collision-free.
     */
    private final String stateName = "__operator:cep:partials";
    private final transient Map<K, TrackedMatcher> matchers = new LinkedHashMap<>();
    /** Keys whose in-memory matcher was evicted: their stale state must not be re-hydrated. */
    private final transient Set<K> evictedKeys = new HashSet<>();
    private transient ObjectMapper mapper;
    private transient com.fasterxml.jackson.databind.JavaType stateType;

    /**
     * @param patternSequence    the pattern to match
     * @param timestampExtractor extracts the event timestamp fed to the matcher; {@code null}
     *                           falls back to {@code System.currentTimeMillis()} at processing time
     */
    public PatternSequenceProcessFunction(PatternSequence<T> patternSequence,
                                          Function<T, Long> timestampExtractor) {
        this(patternSequence, timestampExtractor, DEFAULT_MAX_TRACKED_KEYS);
    }

    /**
     * @param maxTrackedKeys cap on per-key matchers; {@code 0} disables the cap
     * @throws IllegalArgumentException if {@code maxTrackedKeys} is negative
     */
    public PatternSequenceProcessFunction(PatternSequence<T> patternSequence,
                                          Function<T, Long> timestampExtractor,
                                          int maxTrackedKeys) {
        this.patternSequence = patternSequence;
        this.timestampExtractor = timestampExtractor;
        if (maxTrackedKeys < 0) {
            throw new IllegalArgumentException("maxTrackedKeys cannot be negative");
        }
        this.maxTrackedKeys = maxTrackedKeys;
    }

    @Override
    public void processElement(K key, T value, Context ctx, Collector<EventSequence<T>> out) throws Exception {
        long timestamp = timestampExtractor != null
                ? timestampExtractor.apply(value)
                : System.currentTimeMillis();
        long processingTime = ctx.currentProcessingTime();

        // State is only usable once the event type is known (its runtime class drives the
        // JSON read/write of the buffered events). A null event has no class to capture and
        // would also break the pattern predicates, so it skips state entirely.
        boolean stateful = value != null;
        if (stateful && stateType == null) {
            @SuppressWarnings("unchecked")
            Class<T> eventClass = (Class<T>) value.getClass();
            stateType = TypeFactory.defaultInstance().constructParametricType(
                    PersistedMatcherState.class, eventClass);
        }

        TrackedMatcher tracked = matchers.get(key);
        if (tracked == null) {
            tracked = new TrackedMatcher();
            if (stateful && !evictedKeys.contains(key)) {
                hydrate(tracked, key, ctx);
            }
            evictedKeys.remove(key);
            matchers.put(key, tracked);
        }
        tracked.lastTouched = processingTime;

        if (stateful) {
            try {
                for (PatternSequenceMatcher.CompleteMatch<T> match : tracked.matcher.process(value, timestamp)) {
                    out.collect(new EventSequence<>(match.getEvents(), match.getStartTimestamp(), match.getEndTimestamp()));
                }
                flush(tracked, key, ctx);
            } catch (Exception e) {
                throw new RuntimeException("CEP pattern sequence processing failed", e);
            }
        } else {
            for (PatternSequenceMatcher.CompleteMatch<T> match : tracked.matcher.process(value, timestamp)) {
                out.collect(new EventSequence<>(match.getEvents(), match.getStartTimestamp(), match.getEndTimestamp()));
            }
        }
        // Evict after the event is matched: the just-processed key's partial count is only
        // accurate now (before process() a fresh matcher looks idle and would evict the key
        // currently being handled instead of a genuinely idle one).
        if (maxTrackedKeys > 0) {
            evictBeyondCap();
        }
    }

    /** Restores the key's checkpointed partial matches into a fresh matcher. */
    private void hydrate(TrackedMatcher tracked, K key, Context ctx) {
        ValueState<String> state = ctx.getState(new StateDescriptor<>(stateName, String.class));
        String json = state.value();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            PersistedMatcherState<T> persisted = mapper().readValue(json, stateType);
            if (persisted != null && persisted.partials != null) {
                tracked.matcher.restorePartialMatches(persisted.partials);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore CEP pattern state for " + stateName, e);
        }
    }

    /** Writes the matcher's partial matches back to keyed state (clearing it when empty). */
    private void flush(TrackedMatcher tracked, K key, Context ctx) throws Exception {
        ValueState<String> state = ctx.getState(new StateDescriptor<>(stateName, String.class));
        List<PatternSequenceMatcher.PartialMatchState<T>> partials = tracked.matcher.exportPartialMatches();
        if (partials.isEmpty()) {
            state.clear();
            return;
        }
        PersistedMatcherState<T> persisted = new PersistedMatcherState<>();
        persisted.partials = partials;
        state.update(mapper().writeValueAsString(persisted));
    }

    private ObjectMapper mapper() {
        if (mapper == null) {
            mapper = new ObjectMapper()
                    // events may carry java.time types (Instant etc.); register the jsr310
                    // module when present, mirroring the mq module's mapper setup
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        return mapper;
    }

    private void evictBeyondCap() {
        int over = matchers.size() - maxTrackedKeys;
        if (over <= 0) {
            return;
        }
        // Prefer evicting idle keys (no in-progress partials — they hold nothing but the
        // matcher shell); fall back to the least recently touched key otherwise. This runs
        // only once the cap is exceeded, not on the per-record hot path.
        Iterator<Map.Entry<K, TrackedMatcher>> it = matchers.entrySet().iterator();
        while (over > 0 && it.hasNext()) {
            Map.Entry<K, TrackedMatcher> entry = it.next();
            if (entry.getValue().matcher.getPartialMatchCount() == 0) {
                evict(entry.getKey());
                it.remove();
                over--;
            }
        }
        while (over > 0 && !matchers.isEmpty()) {
            evictOldestTouched();
            over--;
        }
    }

    private void evictOldestTouched() {
        K oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<K, TrackedMatcher> entry : matchers.entrySet()) {
            if (entry.getValue().lastTouched < oldest) {
                oldest = entry.getValue().lastTouched;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            evict(oldestKey);
            matchers.remove(oldestKey);
        }
    }

    /** Marks a key's in-memory matcher as evicted so its stale checkpointed state is dropped,
     * not resurrected, the next time the key shows up in this instance. */
    private void evict(K key) {
        evictedKeys.add(key);
    }

    /** Number of keys currently tracked with a matcher instance (exposed for tests/ops). */
    public int getTrackedKeyCount() {
        return matchers.size();
    }

    /** Keyed state document: the partial matches of one key (public members for Jackson). */
    public static final class PersistedMatcherState<T> {
        public List<PatternSequenceMatcher.PartialMatchState<T>> partials;
    }

    private final class TrackedMatcher implements Serializable {
        private static final long serialVersionUID = 1L;
        final PatternSequenceMatcher<T> matcher = new PatternSequenceMatcher<>(patternSequence, 0);
        long lastTouched;
    }
}
