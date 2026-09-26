package io.github.cuihairu.redis.streaming.cep;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Matcher for detecting event patterns in a stream.
 *
 * <p>Retention: with {@code allowEventReuse(true)} every matching event both starts a new
 * sequence and extends all existing ones, so the number of tracked partial sequences used
 * to double per event and OOMed within ~30 events (B-10). Only the most recent
 * {@link #PatternMatcher(PatternConfig, int) maxActiveSequences} partial sequences are
 * kept (default {@value #DEFAULT_MAX_ACTIVE_SEQUENCES}); the completed output of
 * {@link #process(Object, long)} is unaffected.
 *
 * @param <T> The type of events
 */
public class PatternMatcher<T> {

    /** Number of active (partial) sequences kept when no explicit cap is configured. */
    public static final int DEFAULT_MAX_ACTIVE_SEQUENCES = 1000;

    private final PatternConfig<T> config;
    private final List<EventSequence<T>> activeSequences;
    private final int maxActiveSequences;

    public PatternMatcher(PatternConfig<T> config) {
        this(config, DEFAULT_MAX_ACTIVE_SEQUENCES);
    }

    /**
     * @param maxActiveSequences how many partial sequences to keep for extension; the
     *                           newest ones are kept, 0 disables extension tracking
     *                           entirely (completed output is still produced)
     * @throws IllegalArgumentException if {@code maxActiveSequences} is negative
     */
    public PatternMatcher(PatternConfig<T> config, int maxActiveSequences) {
        config.validate();
        if (maxActiveSequences < 0) {
            throw new IllegalArgumentException("maxActiveSequences cannot be negative");
        }
        this.config = config;
        this.activeSequences = new ArrayList<>();
        this.maxActiveSequences = maxActiveSequences;
    }

    /**
     * Process an event and detect patterns
     *
     * @param event The event to process
     * @param timestamp The event timestamp
     * @return List of completed sequences that match the pattern
     */
    public List<EventSequence<T>> process(T event, long timestamp) {
        List<EventSequence<T>> completedSequences = new ArrayList<>();

        // Clean up expired sequences
        cleanupExpiredSequences(timestamp);

        // If contiguous is required, any non-matching event breaks current sequences
        if (config.isContiguous() && !config.getPattern().matches(event)) {
            activeSequences.clear();
            return completedSequences;
        }

        // Check if event matches pattern
        if (config.getPattern().matches(event)) {
            // Create new sequence with this event
            List<T> events = new ArrayList<>();
            events.add(event);
            EventSequence<T> newSeq = new EventSequence<>(events, timestamp, timestamp);
            completedSequences.add(newSeq);

            // If event reuse is allowed, also add to active sequences for extension.
            // Without the active-sequence cap this doubling per event grew as 2^N (B-10).
            if (config.isAllowEventReuse() && maxActiveSequences > 0) {
                activeSequences.add(newSeq);
                extendSequences(event, timestamp);
                trimActiveSequences();
            }
        }

        return completedSequences;
    }

    /**
     * Process an event for sequence detection
     *
     * @param event The event to process
     * @return List of completed sequences
     */
    public List<EventSequence<T>> process(T event) {
        return process(event, System.currentTimeMillis());
    }

    /**
     * Extend existing sequences with a new event
     *
     * @param event The new event
     * @param timestamp The event timestamp
     */
    private void extendSequences(T event, long timestamp) {
        List<EventSequence<T>> toAdd = new ArrayList<>();

        for (EventSequence<T> seq : activeSequences) {
            if (seq.size() < config.getMaxSequenceLength()) {
                EventSequence<T> extended = new EventSequence<>(
                        seq.getEventsCopy(),
                        seq.getStartTime(),
                        timestamp
                );
                extended.addEvent(event);
                toAdd.add(extended);
            }
        }

        activeSequences.addAll(toAdd);
    }

    /**
     * Keep only the newest {@code maxActiveSequences} partial sequences; the oldest are
     * dropped first. Newest-first retention: the most recent partials are the likeliest
     * to be extended by upcoming events.
     */
    private void trimActiveSequences() {
        int excess = activeSequences.size() - maxActiveSequences;
        if (excess > 0) {
            activeSequences.subList(0, excess).clear();
        }
    }

    /**
     * Remove sequences that have exceeded the time window
     *
     * @param currentTime The current timestamp
     */
    private void cleanupExpiredSequences(long currentTime) {
        Iterator<EventSequence<T>> iterator = activeSequences.iterator();
        while (iterator.hasNext()) {
            EventSequence<T> seq = iterator.next();
            if (currentTime - seq.getStartTime() > config.getTimeWindowMillis()) {
                iterator.remove();
            }
        }
    }

    /**
     * Get the number of active sequences
     *
     * @return The count of active sequences
     */
    public int getActiveSequenceCount() {
        return activeSequences.size();
    }

    /**
     * Clear all active sequences
     */
    public void clear() {
        activeSequences.clear();
    }

    /**
     * Get the configuration
     *
     * @return The pattern configuration
     */
    public PatternConfig<T> getConfig() {
        return config;
    }
}
