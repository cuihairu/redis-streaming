package io.github.cuihairu.redis.streaming.join.operator;

import java.io.Serializable;
import java.util.Objects;

/**
 * Single-stream envelope that carries a stream-stream join input through a keyed pipeline:
 * the left and right sources map their records into envelopes, both envelope streams are
 * written into one join-input topic (or one in-memory stream), and a
 * {@link StreamJoinOperator} consumes them side-agnostic.
 *
 * <p>The {@code joinKey} and {@code timestamp} carried here are authoritative for the
 * operator path — the key selectors and timestamp extractors on the {@code JoinConfig} are
 * <em>not</em> consulted again for keyed envelopes. Build envelopes with the same selector
 * the config uses so that partition routing (keyBy / MQ partition) and join matching agree;
 * a mismatched key does not fail, it silently stops matching (the record is routed to a
 * partition whose operator never sees its peers).
 *
 * @param <K> The type of the join key
 * @param <L> The type of left stream elements
 * @param <R> The type of right stream elements
 */
public final class Envelope<K, L, R> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Which side of the join the payload belongs to. */
    public enum Side { LEFT, RIGHT }

    private final Side side;
    private final K joinKey;
    private final long timestamp;
    private final L left;
    private final R right;

    private Envelope(Side side, K joinKey, long timestamp, L left, R right) {
        this.side = Objects.requireNonNull(side, "side");
        this.joinKey = joinKey;
        this.timestamp = timestamp;
        this.left = left;
        this.right = right;
    }

    /**
     * Wrap a left-stream payload.
     *
     * @param joinKey   the join key (as produced by {@code JoinConfig.leftKeySelector})
     * @param timestamp the event timestamp (as produced by {@code JoinConfig.leftTimestampExtractor})
     * @param payload   the left element
     */
    public static <K, L, R> Envelope<K, L, R> forLeft(K joinKey, long timestamp, L payload) {
        return new Envelope<>(Side.LEFT, joinKey, timestamp, payload, null);
    }

    /**
     * Wrap a right-stream payload.
     *
     * @param joinKey   the join key (as produced by {@code JoinConfig.rightKeySelector})
     * @param timestamp the event timestamp (as produced by {@code JoinConfig.rightTimestampExtractor})
     * @param payload   the right element
     */
    public static <K, L, R> Envelope<K, L, R> forRight(K joinKey, long timestamp, R payload) {
        return new Envelope<>(Side.RIGHT, joinKey, timestamp, null, payload);
    }

    public Side getSide() {
        return side;
    }

    public K getJoinKey() {
        return joinKey;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** The left payload; non-null only for {@link Side#LEFT} envelopes. */
    public L getLeft() {
        return left;
    }

    /** The right payload; non-null only for {@link Side#RIGHT} envelopes. */
    public R getRight() {
        return right;
    }

    @Override
    public String toString() {
        return "Envelope{" + side + ", key=" + joinKey + ", ts=" + timestamp + '}';
    }
}
