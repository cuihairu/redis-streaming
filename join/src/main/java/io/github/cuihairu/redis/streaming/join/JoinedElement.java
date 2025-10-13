package io.github.cuihairu.redis.streaming.join;

import lombok.Data;

import java.io.Serializable;

/**
 * Result of a join operation, containing elements from both streams.
 *
 * @param <L> The type of left stream elements
 * @param <R> The type of right stream elements
 */
@Data
public class JoinedElement<L, R> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final L left;
    private final R right;
    private final long timestamp;

    /**
     * Create a joined element
     *
     * @param left The left element (may be null)
     * @param right The right element (may be null)
     * @param timestamp The timestamp of the join
     */
    public JoinedElement(L left, R right, long timestamp) {
        this.left = left;
        this.right = right;
        this.timestamp = timestamp;
    }

    /**
     * Check if this is a left-only element (right is null)
     */
    public boolean isLeftOnly() {
        return left != null && right == null;
    }

    /**
     * Check if this is a right-only element (left is null)
     */
    public boolean isRightOnly() {
        return left == null && right != null;
    }

    /**
     * Check if both elements are present
     */
    public boolean isBothPresent() {
        return left != null && right != null;
    }

    /**
     * Create a joined element with both sides
     */
    public static <L, R> JoinedElement<L, R> of(L left, R right, long timestamp) {
        return new JoinedElement<>(left, right, timestamp);
    }

    /**
     * Create a left-only joined element
     */
    public static <L, R> JoinedElement<L, R> leftOnly(L left, long timestamp) {
        return new JoinedElement<>(left, null, timestamp);
    }

    /**
     * Create a right-only joined element
     */
    public static <L, R> JoinedElement<L, R> rightOnly(R right, long timestamp) {
        return new JoinedElement<>(null, right, timestamp);
    }
}
