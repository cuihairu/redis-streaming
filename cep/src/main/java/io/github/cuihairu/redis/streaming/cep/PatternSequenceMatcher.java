package io.github.cuihairu.redis.streaming.cep;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Advanced pattern matcher that supports complex event sequences with:
 * - Kleene closures (*, +, ?, {n}, {n,m})
 * - Contiguity constraints (strict, relaxed, non-deterministic)
 * - Temporal constraints (within)
 * - Multiple concurrent pattern matches
 *
 * @param <T> The type of events
 */
@Slf4j
public class PatternSequenceMatcher<T> {

    private final PatternSequence<T> patternSequence;
    private final List<PartialMatch<T>> partialMatches;
    private final List<CompleteMatch<T>> completeMatches;

    public PatternSequenceMatcher(PatternSequence<T> patternSequence) {
        patternSequence.validate();
        this.patternSequence = patternSequence;
        this.partialMatches = new ArrayList<>();
        this.completeMatches = new ArrayList<>();
    }

    /**
     * Process an event and update pattern matching state
     *
     * @param event The event to process
     * @param timestamp The event timestamp in milliseconds
     * @return List of complete matches found
     */
    public List<CompleteMatch<T>> process(T event, long timestamp) {
        List<CompleteMatch<T>> newMatches = new ArrayList<>();

        // Clean up expired partial matches
        cleanupExpiredMatches(timestamp);

        boolean eventConsumed = false;
        List<PartialMatch<T>> nextPartials = new ArrayList<>();

        // Extend existing partial matches
        for (PartialMatch<T> partial : partialMatches) {
            int beforeSize = partial.events.size();
            List<PartialMatch<T>> extended = tryExtend(partial, event, timestamp);

            for (PartialMatch<T> ext : extended) {
                eventConsumed |= ext.events.size() > beforeSize;
                if (ext.isComplete()) {
                    CompleteMatch<T> complete = new CompleteMatch<>(
                            ext.events,
                            ext.eventsByStep,
                            ext.startTimestamp,
                            timestamp
                    );
                    newMatches.add(complete);
                    completeMatches.add(complete);
                } else {
                    nextPartials.add(ext);
                }
            }
        }

        partialMatches.clear();
        partialMatches.addAll(nextPartials);

        // Start new matches from this event only if it wasn't used to extend an existing match
        if (!eventConsumed) {
            List<PartialMatch<T>> started = startNewMatches(event, timestamp);
            for (PartialMatch<T> st : started) {
                if (st.isComplete()) {
                    CompleteMatch<T> complete = new CompleteMatch<>(
                            st.events,
                            st.eventsByStep,
                            st.startTimestamp,
                            timestamp
                    );
                    newMatches.add(complete);
                    completeMatches.add(complete);
                } else {
                    partialMatches.add(st);
                }
            }
        }

        log.debug("Processed event, {} new matches, {} partial matches, {} total matches",
            newMatches.size(), partialMatches.size(), completeMatches.size());

        return newMatches;
    }

    /**
     * Try to extend a partial match with a new event
     */
    private List<PartialMatch<T>> tryExtend(PartialMatch<T> partial, T event, long timestamp) {
        List<PartialMatch<T>> results = new ArrayList<>();

        if (partial.isComplete()) {
            return results;
        }

        List<PartialMatch<T>> consumed = consumeEventAtFirstMatch(partial, event, timestamp);
        if (!consumed.isEmpty()) {
            return consumed;
        }

        PatternSequence.PatternStep<T> step = patternSequence.getSteps().get(partial.currentStep);
        if (step.getContiguityType() != PatternSequence.ContiguityType.STRICT) {
            results.add(partial.copy());
        }

        return results;
    }

    private List<PartialMatch<T>> startNewMatches(T event, long timestamp) {
        PartialMatch<T> seed = new PartialMatch<>(timestamp);
        return consumeEventAtFirstMatch(seed, event, timestamp);
    }

    private List<PartialMatch<T>> consumeEventAtFirstMatch(PartialMatch<T> partial, T event, long timestamp) {
        List<PartialMatch<T>> results = new ArrayList<>();

        int stepIndex = partial.currentStep;
        while (stepIndex < patternSequence.size()) {
            PatternSequence.PatternStep<T> step = patternSequence.getSteps().get(stepIndex);
            PatternQuantifier quantifier = step.getQuantifier();
            int currentCount = partial.getCountForStep(stepIndex);

            if (step.getPattern().matches(event)) {
                int newCount = currentCount + 1;
                if (newCount > quantifier.getMaxOccurrences()) {
                    return results;
                }

                PartialMatch<T> extended = partial.copy();
                extended.currentStep = stepIndex;
                extended.addEvent(stepIndex, event, timestamp);

                if (newCount < quantifier.getMaxOccurrences()) {
                    results.add(extended);
                }

                if (newCount >= quantifier.getMinOccurrences()) {
                    PartialMatch<T> advanced = extended.copy();
                    advanced.currentStep = stepIndex + 1;
                    results.add(advanced);
                }
                return results;
            }

            if (quantifier.getMinOccurrences() == 0 && currentCount == 0) {
                stepIndex++;
                continue;
            }
            return results;
        }

        return results;
    }

    /**
     * Remove partial matches that exceeded the time window
     */
    private void cleanupExpiredMatches(long currentTime) {
        if (patternSequence.getTimeWindow() == null) {
            return;
        }

        long windowMillis = patternSequence.getTimeWindow().toMillis();
        partialMatches.removeIf(partial ->
            currentTime - partial.startTimestamp > windowMillis
        );
    }

    /**
     * Get all complete matches found so far
     */
    public List<CompleteMatch<T>> getCompleteMatches() {
        return new ArrayList<>(completeMatches);
    }

    /**
     * Get active partial matches
     */
    public int getPartialMatchCount() {
        return partialMatches.size();
    }

    /**
     * Clear all matches
     */
    public void clear() {
        partialMatches.clear();
        completeMatches.clear();
    }

    /**
     * Represents a partial match in progress
     */
    @Getter
    private class PartialMatch<E> {
        private final List<E> events;
        private final Map<Integer, List<E>> eventsByStep;
        private final long startTimestamp;
        private int currentStep;

        public PartialMatch(long startTimestamp) {
            this.events = new ArrayList<>();
            this.eventsByStep = new HashMap<>();
            this.startTimestamp = startTimestamp;
            this.currentStep = 0;
        }

        public void addEvent(int step, E event, long timestamp) {
            events.add(event);
            eventsByStep.computeIfAbsent(step, k -> new ArrayList<>()).add(event);
        }

        public int getCountForStep(int step) {
            return eventsByStep.getOrDefault(step, Collections.emptyList()).size();
        }

        public boolean isComplete() {
            return currentStep >= patternSequence.size();
        }

        public PartialMatch<E> copy() {
            PartialMatch<E> copy = new PartialMatch<>(startTimestamp);
            copy.events.addAll(this.events);
            this.eventsByStep.forEach((k, v) ->
                copy.eventsByStep.put(k, new ArrayList<>(v))
            );
            copy.currentStep = this.currentStep;
            return copy;
        }
    }

    /**
     * Represents a complete pattern match
     */
    @Getter
    public static class CompleteMatch<E> {
        private final List<E> events;
        private final Map<Integer, List<E>> eventsByStep;
        private final long startTimestamp;
        private final long endTimestamp;

        public CompleteMatch(List<E> events, Map<Integer, List<E>> eventsByStep,
                           long startTimestamp, long endTimestamp) {
            this.events = new ArrayList<>(events);
            this.eventsByStep = new HashMap<>();
            eventsByStep.forEach((k, v) -> this.eventsByStep.put(k, new ArrayList<>(v)));
            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;
        }

        public long getDuration() {
            return endTimestamp - startTimestamp;
        }

        /**
         * Get events for a specific step by name
         */
        public List<E> getEventsForStep(String stepName, PatternSequence<E> sequence) {
            for (int i = 0; i < sequence.getSteps().size(); i++) {
                if (sequence.getSteps().get(i).getName().equals(stepName)) {
                    return eventsByStep.getOrDefault(i, Collections.emptyList());
                }
            }
            return Collections.emptyList();
        }

        @Override
        public String toString() {
            return "CompleteMatch{" +
                "events=" + events.size() +
                ", duration=" + getDuration() + "ms" +
                '}';
        }
    }
}
