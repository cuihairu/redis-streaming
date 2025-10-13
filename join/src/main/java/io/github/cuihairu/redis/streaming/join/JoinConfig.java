package io.github.cuihairu.redis.streaming.join;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Configuration for stream join operations.
 *
 * @param <L> The type of left stream elements
 * @param <R> The type of right stream elements
 * @param <K> The type of the join key
 */
@Data
@Builder
public class JoinConfig<L, R, K> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The type of join (INNER, LEFT, RIGHT, FULL_OUTER)
     */
    private final JoinType joinType;

    /**
     * The time window for the join
     */
    private final JoinWindow joinWindow;

    /**
     * Function to extract key from left stream elements
     */
    private final java.util.function.Function<L, K> leftKeySelector;

    /**
     * Function to extract key from right stream elements
     */
    private final java.util.function.Function<R, K> rightKeySelector;

    /**
     * Function to extract timestamp from left stream elements
     */
    private final java.util.function.Function<L, Long> leftTimestampExtractor;

    /**
     * Function to extract timestamp from right stream elements
     */
    private final java.util.function.Function<R, Long> rightTimestampExtractor;

    /**
     * Maximum size of the join state (for memory management)
     */
    @Builder.Default
    private final int maxStateSize = 10000;

    /**
     * Time to retain elements in state (milliseconds)
     */
    @Builder.Default
    private final long stateRetentionTime = 3600000; // 1 hour default

    /**
     * Validate the configuration
     */
    public void validate() {
        if (joinType == null) {
            throw new IllegalArgumentException("Join type must be specified");
        }
        if (joinWindow == null) {
            throw new IllegalArgumentException("Join window must be specified");
        }
        if (leftKeySelector == null) {
            throw new IllegalArgumentException("Left key selector must be specified");
        }
        if (rightKeySelector == null) {
            throw new IllegalArgumentException("Right key selector must be specified");
        }
        if (maxStateSize <= 0) {
            throw new IllegalArgumentException("Max state size must be positive");
        }
        if (stateRetentionTime <= 0) {
            throw new IllegalArgumentException("State retention time must be positive");
        }
    }

    /**
     * Create a simple inner join configuration
     */
    public static <L, R, K> JoinConfig<L, R, K> innerJoin(
            java.util.function.Function<L, K> leftKeySelector,
            java.util.function.Function<R, K> rightKeySelector,
            JoinWindow window) {

        return JoinConfig.<L, R, K>builder()
                .joinType(JoinType.INNER)
                .joinWindow(window)
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .build();
    }

    /**
     * Create a simple left join configuration
     */
    public static <L, R, K> JoinConfig<L, R, K> leftJoin(
            java.util.function.Function<L, K> leftKeySelector,
            java.util.function.Function<R, K> rightKeySelector,
            JoinWindow window) {

        return JoinConfig.<L, R, K>builder()
                .joinType(JoinType.LEFT)
                .joinWindow(window)
                .leftKeySelector(leftKeySelector)
                .rightKeySelector(rightKeySelector)
                .build();
    }
}
