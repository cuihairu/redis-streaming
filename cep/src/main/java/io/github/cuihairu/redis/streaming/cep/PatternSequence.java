package io.github.cuihairu.redis.streaming.cep;

import lombok.Getter;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a sequence of patterns with temporal and contiguity constraints.
 * Supports complex event pattern operations like followedBy, within, etc.
 *
 * @param <T> The type of events
 */
@Getter
public class PatternSequence<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<PatternStep<T>> steps;
    private Duration timeWindow;
    private boolean contiguous;

    private PatternSequence() {
        this.steps = new ArrayList<>();
        this.contiguous = false;
    }

    /**
     * Start a new pattern sequence
     */
    public static <T> PatternSequence<T> begin() {
        return new PatternSequence<>();
    }

    /**
     * Start with a named pattern
     */
    public static <T> PatternSequence<T> begin(String name, Pattern<T> pattern) {
        PatternSequence<T> seq = new PatternSequence<>();
        seq.where(name, pattern);
        return seq;
    }

    /**
     * Add a pattern condition to the current step
     */
    public PatternSequence<T> where(String name, Pattern<T> pattern) {
        PatternStep<T> step = new PatternStep<>(name, pattern, ContiguityType.RELAXED);
        steps.add(step);
        return this;
    }

    /**
     * Add a pattern that must immediately follow the previous one
     * (strict contiguity - no events allowed in between)
     */
    public PatternSequence<T> next(String name, Pattern<T> pattern) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Cannot use next() as first pattern. Use begin() instead.");
        }
        PatternStep<T> step = new PatternStep<>(name, pattern, ContiguityType.STRICT);
        steps.add(step);
        return this;
    }

    /**
     * Add a pattern that follows the previous one with relaxed contiguity
     * (other events may appear in between)
     */
    public PatternSequence<T> followedBy(String name, Pattern<T> pattern) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Cannot use followedBy() as first pattern. Use begin() instead.");
        }
        PatternStep<T> step = new PatternStep<>(name, pattern, ContiguityType.RELAXED);
        steps.add(step);
        return this;
    }

    /**
     * Add a pattern that follows the previous one with non-deterministic relaxed contiguity
     * (allows multiple matches for the same pattern)
     */
    public PatternSequence<T> followedByAny(String name, Pattern<T> pattern) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Cannot use followedByAny() as first pattern. Use begin() instead.");
        }
        PatternStep<T> step = new PatternStep<>(name, pattern, ContiguityType.NON_DETERMINISTIC);
        steps.add(step);
        return this;
    }

    /**
     * Apply a quantifier to the last pattern step (Kleene closure)
     */
    public PatternSequence<T> times(PatternQuantifier quantifier) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("No pattern to apply quantifier to");
        }
        PatternStep<T> lastStep = steps.get(steps.size() - 1);
        lastStep.setQuantifier(quantifier);
        return this;
    }

    /**
     * Apply oneOrMore quantifier to the last pattern (+)
     */
    public PatternSequence<T> oneOrMore() {
        return times(PatternQuantifier.oneOrMore());
    }

    /**
     * Apply zeroOrMore quantifier to the last pattern (*)
     */
    public PatternSequence<T> zeroOrMore() {
        return times(PatternQuantifier.zeroOrMore());
    }

    /**
     * Apply optional quantifier to the last pattern (?)
     */
    public PatternSequence<T> optional() {
        return times(PatternQuantifier.optional());
    }

    /**
     * Apply times quantifier to the last pattern
     */
    public PatternSequence<T> times(int count) {
        return times(PatternQuantifier.exactly(count));
    }

    /**
     * Apply times range quantifier to the last pattern
     */
    public PatternSequence<T> times(int min, int max) {
        return times(PatternQuantifier.times(min, max));
    }

    /**
     * Set a time constraint - pattern must complete within this duration
     */
    public PatternSequence<T> within(Duration timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    /**
     * Validate the pattern sequence
     */
    public void validate() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Pattern sequence cannot be empty");
        }
    }

    /**
     * Get the number of pattern steps
     */
    public int size() {
        return steps.size();
    }

    /**
     * Represents a single step in the pattern sequence
     */
    @Getter
    public static class PatternStep<T> implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final Pattern<T> pattern;
        private final ContiguityType contiguityType;
        private PatternQuantifier quantifier;

        public PatternStep(String name, Pattern<T> pattern, ContiguityType contiguityType) {
            this.name = name;
            this.pattern = pattern;
            this.contiguityType = contiguityType;
            this.quantifier = PatternQuantifier.exactly(1); // Default
        }

        public void setQuantifier(PatternQuantifier quantifier) {
            this.quantifier = quantifier;
        }

        @Override
        public String toString() {
            return name + quantifier.toString() + " [" + contiguityType + "]";
        }
    }

    /**
     * Contiguity type between pattern steps
     */
    public enum ContiguityType {
        /**
         * Strict contiguity - next event must immediately follow (no events in between)
         */
        STRICT,

        /**
         * Relaxed contiguity - allows other events in between
         */
        RELAXED,

        /**
         * Non-deterministic relaxed contiguity - allows multiple matches
         */
        NON_DETERMINISTIC
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PatternSequence[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(steps.get(i));
        }
        if (timeWindow != null) {
            sb.append("] within ").append(timeWindow);
        } else {
            sb.append("]");
        }
        return sb.toString();
    }
}
