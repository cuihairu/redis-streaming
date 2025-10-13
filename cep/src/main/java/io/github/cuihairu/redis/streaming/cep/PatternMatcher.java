package io.github.cuihairu.redis.streaming.cep;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Matcher for detecting event patterns in a stream.
 *
 * @param <T> The type of events
 */
public class PatternMatcher<T> {

    private final PatternConfig<T> config;
    private final List<EventSequence<T>> activeSequences;

    public PatternMatcher(PatternConfig<T> config) {
        config.validate();
        this.config = config;
        this.activeSequences = new ArrayList<>();
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

        // Check if event matches pattern
        if (config.getPattern().matches(event)) {
            // Create new sequence with this event
            List<T> events = new ArrayList<>();
            events.add(event);
            EventSequence<T> newSeq = new EventSequence<>(events, timestamp, timestamp);
            completedSequences.add(newSeq);

            // If event reuse is allowed, also add to active sequences for extension
            if (config.isAllowEventReuse()) {
                activeSequences.add(newSeq);
                extendSequences(event, timestamp);
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
