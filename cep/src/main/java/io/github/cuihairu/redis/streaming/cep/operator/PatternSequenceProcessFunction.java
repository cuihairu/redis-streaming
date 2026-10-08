package io.github.cuihairu.redis.streaming.cep.operator;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import io.github.cuihairu.redis.streaming.cep.PatternSequenceMatcher;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Operatorization of the CEP sequence matcher: adapts {@link PatternSequenceMatcher} to a
 * {@link KeyedProcessFunction} so that per-key pattern matching runs inside a DataStream
 * pipeline on either execution engine. See {@code docs/Join-CEP-Operators-Design.md}.
 *
 * <p>Each key gets its own matcher instance — sequences of different keys never mix. The
 * {@code within(...)} deadline of the pattern is enforced by the matcher's event-driven
 * cleanup (expired partial matches are dropped on every {@code process(event, timestamp)}
 * call); the operator registers no engine timers. Partial-match state lives in the operator
 * instance (partition-local, not checkpointed in this phase).
 *
 * <p>Matchers are tracked per key up to {@code maxTrackedKeys} (default 10000, 0 =
 * unbounded); the least recently touched key's matcher is evicted beyond the cap so high
 * key cardinality cannot grow the map without bound. Evicting a key only loses its
 * in-progress partial matches — completed matches are emitted as they happen.
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
    private final transient Map<K, TrackedMatcher> matchers = new LinkedHashMap<>();

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
        TrackedMatcher tracked = matchers.compute(key, (k, existing) -> {
            TrackedMatcher t = existing != null ? existing : new TrackedMatcher();
            t.lastTouched = processingTime;
            return t;
        });
        for (PatternSequenceMatcher.CompleteMatch<T> match : tracked.matcher.process(value, timestamp)) {
            out.collect(new EventSequence<>(match.getEvents(), match.getStartTimestamp(), match.getEndTimestamp()));
        }
        // Evict after the event is matched: the just-processed key's partial count is only
        // accurate now (before process() a fresh matcher looks idle and would evict the key
        // currently being handled instead of a genuinely idle one).
        if (maxTrackedKeys > 0) {
            evictBeyondCap();
        }
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
            matchers.remove(oldestKey);
        }
    }

    /** Number of keys currently tracked with a matcher instance (exposed for tests/ops). */
    public int getTrackedKeyCount() {
        return matchers.size();
    }

    private final class TrackedMatcher implements Serializable {
        private static final long serialVersionUID = 1L;
        final PatternSequenceMatcher<T> matcher = new PatternSequenceMatcher<>(patternSequence, 0);
        long lastTouched;
    }
}
