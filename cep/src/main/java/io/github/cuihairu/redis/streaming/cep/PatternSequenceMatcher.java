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

        // Try to extend existing partial matches
        List<PartialMatch<T>> newPartialMatches = new ArrayList<>();
        Iterator<PartialMatch<T>> iterator = partialMatches.iterator();

        while (iterator.hasNext()) {
            PartialMatch<T> partial = iterator.next();
            List<PartialMatch<T>> extended = tryExtend(partial, event, timestamp);

            for (PartialMatch<T> ext : extended) {
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
                    newPartialMatches.add(ext);
                }
            }

            // Remove completed or failed matches
            if (extended.isEmpty() || extended.stream().anyMatch(PartialMatch::isComplete)) {
                iterator.remove();
            }
        }

        partialMatches.addAll(newPartialMatches);

        // Try to start new matches from this event
        PatternSequence.PatternStep<T> firstStep = patternSequence.getSteps().get(0);

        // Handle zeroOrMore or optional first pattern - can skip directly to next step
        if (firstStep.getQuantifier().getMinOccurrences() == 0 && patternSequence.size() > 1) {
            PartialMatch<T> skipFirst = new PartialMatch<>(timestamp);
            skipFirst.currentStep = 1;  // Skip to second step

            // Try matching against second step
            PatternSequence.PatternStep<T> secondStep = patternSequence.getSteps().get(1);
            if (secondStep.getPattern().matches(event)) {
                skipFirst.addEvent(1, event, timestamp);

                // Check if this completes the pattern
                if (secondStep.getQuantifier().matches(1) && patternSequence.size() == 2) {
                    // Pattern completed
                    CompleteMatch<T> complete = new CompleteMatch<>(
                        skipFirst.events,
                        skipFirst.eventsByStep,
                        timestamp,
                        timestamp
                    );
                    newMatches.add(complete);
                    completeMatches.add(complete);
                } else {
                    partialMatches.add(skipFirst);
                }
            }
        }

        if (firstStep.getPattern().matches(event)) {
            PartialMatch<T> newPartial = new PartialMatch<>(timestamp);
            newPartial.addEvent(0, event, timestamp);

            if (firstStep.getQuantifier().matches(1) && patternSequence.size() == 1) {
                // Single-step pattern completed immediately
                CompleteMatch<T> complete = new CompleteMatch<>(
                    newPartial.events,
                    newPartial.eventsByStep,
                    timestamp,
                    timestamp
                );
                newMatches.add(complete);
                completeMatches.add(complete);
            } else {
                partialMatches.add(newPartial);
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
        int currentStep = partial.currentStep;

        if (currentStep >= patternSequence.size()) {
            return results; // Already complete
        }

        PatternSequence.PatternStep<T> step = patternSequence.getSteps().get(currentStep);
        boolean matches = step.getPattern().matches(event);
        PatternQuantifier quantifier = step.getQuantifier();
        int currentCount = partial.getCountForStep(currentStep);

        if (matches) {
            // Event matches current step
            PartialMatch<T> extended = partial.copy();
            extended.addEvent(currentStep, event, timestamp);
            int newCount = currentCount + 1;

            if (newCount >= quantifier.getMinOccurrences()) {
                // Satisfied minimum occurrences
                if (newCount <= quantifier.getMaxOccurrences()) {
                    // Can stay on current step for more matches
                    if (newCount < quantifier.getMaxOccurrences()) {
                        results.add(extended); // Stay on current step
                    }

                    // Try moving to next step
                    PartialMatch<T> advanced = extended.copy();
                    advanced.currentStep++;
                    results.add(advanced);
                } else {
                    // Exceeded max occurrences - failed match
                    return results;
                }
            } else {
                // Need more occurrences on current step
                results.add(extended);
            }
        } else {
            // Event doesn't match current step
            PatternSequence.ContiguityType contiguity = step.getContiguityType();

            // Check if current step can be skipped (min occurrences is 0 or already satisfied)
            if (currentCount >= quantifier.getMinOccurrences()) {
                // Current step is satisfied, try to move to next step
                PartialMatch<T> skipped = partial.copy();
                skipped.currentStep++;

                if (skipped.currentStep < patternSequence.size()) {
                    // Try matching against next step
                    PatternSequence.PatternStep<T> nextStep = patternSequence.getSteps().get(skipped.currentStep);
                    if (nextStep.getPattern().matches(event)) {
                        skipped.addEvent(skipped.currentStep, event, timestamp);

                        // Check if pattern is now complete
                        int nextCount = skipped.getCountForStep(skipped.currentStep);
                        if (nextCount >= nextStep.getQuantifier().getMinOccurrences() &&
                            skipped.currentStep == patternSequence.size() - 1) {
                            // Last step completed - advance to mark as complete
                            skipped.currentStep++;
                        }
                        results.add(skipped);
                    } else {
                        // Next step doesn't match either
                        // Only keep alive if next step has relaxed contiguity
                        if (nextStep.getContiguityType() != PatternSequence.ContiguityType.STRICT) {
                            results.add(skipped); // Keep alive for relaxed contiguity
                        }
                    }
                } else {
                    // Already at last step and it's satisfied
                    results.add(skipped);
                }
            }

            // Handle contiguity for current step
            if (contiguity == PatternSequence.ContiguityType.STRICT) {
                // Strict contiguity - match failed if we haven't satisfied minimum
                if (currentCount < quantifier.getMinOccurrences()) {
                    return results;
                }
                // If we've satisfied minimum, the skip logic above handles progression
            } else {
                // Relaxed/non-deterministic - keep partial match alive (skip this event)
                // Only if we haven't exceeded max occurrences
                if (currentCount < quantifier.getMaxOccurrences()) {
                    results.add(partial);
                }
            }
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
