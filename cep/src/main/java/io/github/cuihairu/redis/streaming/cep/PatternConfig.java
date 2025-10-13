package io.github.cuihairu.redis.streaming.cep;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * Configuration for pattern matching with time constraints.
 *
 * @param <T> The type of events
 */
@Data
@Builder
public class PatternConfig<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The pattern to match
     */
    private final Pattern<T> pattern;

    /**
     * Maximum time window for pattern matching
     */
    @Builder.Default
    private final Duration timeWindow = Duration.ofMinutes(1);

    /**
     * Whether patterns must be contiguous
     */
    @Builder.Default
    private final boolean contiguous = false;

    /**
     * Maximum number of events in a sequence
     */
    @Builder.Default
    private final int maxSequenceLength = 100;

    /**
     * Whether to allow event reuse across sequences
     */
    @Builder.Default
    private final boolean allowEventReuse = false;

    /**
     * Validate the configuration
     */
    public void validate() {
        if (pattern == null) {
            throw new IllegalArgumentException("Pattern must be specified");
        }
        if (timeWindow == null || timeWindow.isNegative()) {
            throw new IllegalArgumentException("Time window must be positive");
        }
        if (maxSequenceLength <= 0) {
            throw new IllegalArgumentException("Max sequence length must be positive");
        }
    }

    /**
     * Get time window in milliseconds
     *
     * @return Time window in ms
     */
    public long getTimeWindowMillis() {
        return timeWindow.toMillis();
    }
}
