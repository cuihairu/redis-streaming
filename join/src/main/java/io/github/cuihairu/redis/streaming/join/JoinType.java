package io.github.cuihairu.redis.streaming.join;

import java.io.Serializable;

/**
 * Defines the type of join operation between two streams.
 */
public enum JoinType implements Serializable {
    /**
     * Inner join: Only emit when both streams have matching keys
     */
    INNER,

    /**
     * Left join: Emit for all left stream elements, right may be null
     */
    LEFT,

    /**
     * Right join: Emit for all right stream elements, left may be null
     */
    RIGHT,

    /**
     * Full outer join: Emit for all elements from both streams
     */
    FULL_OUTER
}
