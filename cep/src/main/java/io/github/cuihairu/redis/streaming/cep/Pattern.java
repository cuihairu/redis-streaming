package io.github.cuihairu.redis.streaming.cep;

import java.io.Serializable;
import java.util.function.Predicate;

/**
 * Represents a pattern condition for matching events in CEP.
 *
 * @param <T> The type of events to match
 */
public interface Pattern<T> extends Serializable {

    /**
     * Check if an event matches this pattern
     *
     * @param event The event to check
     * @return true if the event matches
     */
    boolean matches(T event);

    /**
     * Create a simple pattern from a predicate
     *
     * @param predicate The predicate to match
     * @param <T> The event type
     * @return A pattern
     */
    static <T> Pattern<T> of(Predicate<T> predicate) {
        return predicate::test;
    }

    /**
     * Combine this pattern with another using AND logic
     *
     * @param other The other pattern
     * @return A combined pattern
     */
    default Pattern<T> and(Pattern<T> other) {
        return event -> this.matches(event) && other.matches(event);
    }

    /**
     * Combine this pattern with another using OR logic
     *
     * @param other The other pattern
     * @return A combined pattern
     */
    default Pattern<T> or(Pattern<T> other) {
        return event -> this.matches(event) || other.matches(event);
    }

    /**
     * Negate this pattern
     *
     * @return A negated pattern
     */
    default Pattern<T> negate() {
        return event -> !this.matches(event);
    }
}
