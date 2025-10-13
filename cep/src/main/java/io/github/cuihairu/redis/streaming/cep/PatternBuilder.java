package io.github.cuihairu.redis.streaming.cep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for creating complex event patterns.
 *
 * @param <T> The type of events
 */
public class PatternBuilder<T> {

    private final List<Pattern<T>> patterns;

    private PatternBuilder() {
        this.patterns = new ArrayList<>();
    }

    /**
     * Start building a new pattern
     *
     * @param <T> The event type
     * @return A new pattern builder
     */
    public static <T> PatternBuilder<T> create() {
        return new PatternBuilder<>();
    }

    /**
     * Add a pattern condition
     *
     * @param pattern The pattern to add
     * @return This builder
     */
    public PatternBuilder<T> where(Pattern<T> pattern) {
        patterns.add(pattern);
        return this;
    }

    /**
     * Add a pattern that follows the previous one
     *
     * @param pattern The pattern to add
     * @return This builder
     */
    public PatternBuilder<T> followedBy(Pattern<T> pattern) {
        patterns.add(pattern);
        return this;
    }

    /**
     * Build a pattern that matches if ANY of the conditions match
     *
     * @return The combined pattern
     */
    public Pattern<T> any() {
        if (patterns.isEmpty()) {
            return event -> false;
        }
        return event -> patterns.stream().anyMatch(p -> p.matches(event));
    }

    /**
     * Build a pattern that matches if ALL conditions match
     *
     * @return The combined pattern
     */
    public Pattern<T> all() {
        if (patterns.isEmpty()) {
            return event -> true;
        }
        return event -> patterns.stream().allMatch(p -> p.matches(event));
    }

    /**
     * Build the pattern (defaults to ALL)
     *
     * @return The combined pattern
     */
    public Pattern<T> build() {
        return all();
    }

    /**
     * Build a pattern configuration with time window
     *
     * @param timeWindow The time window
     * @return A pattern configuration
     */
    public PatternConfig<T> withTimeWindow(Duration timeWindow) {
        return PatternConfig.<T>builder()
                .pattern(build())
                .timeWindow(timeWindow)
                .build();
    }

    /**
     * Build a contiguous pattern configuration
     *
     * @param timeWindow The time window
     * @return A pattern configuration
     */
    public PatternConfig<T> withContiguousTimeWindow(Duration timeWindow) {
        return PatternConfig.<T>builder()
                .pattern(build())
                .timeWindow(timeWindow)
                .contiguous(true)
                .build();
    }
}
