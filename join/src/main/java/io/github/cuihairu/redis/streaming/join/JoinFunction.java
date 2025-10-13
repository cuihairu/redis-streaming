package io.github.cuihairu.redis.streaming.join;

import java.io.Serializable;
import java.util.function.BiFunction;

/**
 * Function for joining two stream elements.
 *
 * @param <L> The type of left stream elements
 * @param <R> The type of right stream elements
 * @param <O> The type of the output elements
 */
@FunctionalInterface
public interface JoinFunction<L, R, O> extends Serializable {

    /**
     * Join two elements from left and right streams
     *
     * @param left The element from the left stream (may be null for right/full joins)
     * @param right The element from the right stream (may be null for left/full joins)
     * @return The joined result
     * @throws Exception if the join operation fails
     */
    O join(L left, R right) throws Exception;

    /**
     * Create a JoinFunction from a BiFunction
     *
     * @param function The BiFunction to wrap
     * @param <L> The type of left elements
     * @param <R> The type of right elements
     * @param <O> The type of output elements
     * @return A JoinFunction
     */
    static <L, R, O> JoinFunction<L, R, O> of(BiFunction<L, R, O> function) {
        return (left, right) -> function.apply(left, right);
    }
}
