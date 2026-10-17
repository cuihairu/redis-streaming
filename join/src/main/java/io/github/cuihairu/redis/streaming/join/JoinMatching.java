package io.github.cuihairu.redis.streaming.join;

import java.util.List;
import java.util.ArrayList;

/**
 * The per-key join core shared by {@link StreamJoiner} (buffers held in instance maps) and
 * the keyed operator ({@code join.operator.StreamJoinOperator}, buffers held in keyed
 * state): window predicate, outer-join emission and retention removal live here so the two
 * entry points cannot drift apart.
 */
public final class JoinMatching {

    private JoinMatching() {
    }

    /**
     * Pair an arriving left element against the right elements buffered for the same key.
     * The window predicate is anchored on the LEFT timestamp, so a pair matches regardless
     * of which side arrived first; {@code LEFT}/{@code FULL_OUTER} joins emit
     * {@code join(element, null)} when nothing matches (B-36 immediate outer semantics).
     */
    public static <L, R, K, O> List<O> matchArrivingLeft(JoinConfig<L, R, K> config,
                                                  JoinFunction<L, R, O> joinFunction,
                                                  L element,
                                                  long timestamp,
                                                  List<TimestampedElement<R>> rightElements) throws Exception {
        return matchArrivingLeft(config, joinFunction, element, timestamp, rightElements, true);
    }

    /**
     * Same as {@link #matchArrivingLeft(JoinConfig, JoinFunction, Object, long, List)} but
     * with the unmatched outer row controlled explicitly, for the operator's Phase 3
     * waiting/retract mode ({@code JoinConfig.emitUnmatchedImmediately=false}): pairs are
     * still emitted on arrival, the unmatched row is deferred to the close emission instead.
     */
    public static <L, R, K, O> List<O> matchArrivingLeft(JoinConfig<L, R, K> config,
                                                  JoinFunction<L, R, O> joinFunction,
                                                  L element,
                                                  long timestamp,
                                                  List<TimestampedElement<R>> rightElements,
                                                  boolean emitUnmatched) throws Exception {
        List<O> results = new ArrayList<>();
        if (rightElements != null) {
            for (TimestampedElement<R> rightElem : rightElements) {
                if (config.getJoinWindow().contains(timestamp, rightElem.timestamp())) {
                    results.add(joinFunction.join(element, rightElem.element()));
                }
            }
        }
        if (emitUnmatched && results.isEmpty()
                && (config.getJoinType() == JoinType.LEFT || config.getJoinType() == JoinType.FULL_OUTER)) {
            results.add(joinFunction.join(element, null));
        }
        return results;
    }

    /**
     * Pair an arriving right element against the buffered left elements of the same key
     * (see {@link #matchArrivingLeft}).
     */
    public static <L, R, K, O> List<O> matchArrivingRight(JoinConfig<L, R, K> config,
                                                   JoinFunction<L, R, O> joinFunction,
                                                   R element,
                                                   long timestamp,
                                                   List<TimestampedElement<L>> leftElements) throws Exception {
        return matchArrivingRight(config, joinFunction, element, timestamp, leftElements, true);
    }

    /**
     * Same as {@link #matchArrivingRight(JoinConfig, JoinFunction, Object, long, List)}
     * with the unmatched outer row controlled explicitly (see
     * {@link #matchArrivingLeft(JoinConfig, JoinFunction, Object, long, List, boolean)}).
     */
    public static <L, R, K, O> List<O> matchArrivingRight(JoinConfig<L, R, K> config,
                                                   JoinFunction<L, R, O> joinFunction,
                                                   R element,
                                                   long timestamp,
                                                   List<TimestampedElement<L>> leftElements,
                                                   boolean emitUnmatched) throws Exception {
        List<O> results = new ArrayList<>();
        if (leftElements != null) {
            for (TimestampedElement<L> leftElem : leftElements) {
                if (config.getJoinWindow().contains(leftElem.timestamp(), timestamp)) {
                    results.add(joinFunction.join(leftElem.element(), element));
                }
            }
        }
        if (emitUnmatched && results.isEmpty()
                && (config.getJoinType() == JoinType.RIGHT || config.getJoinType() == JoinType.FULL_OUTER)) {
            results.add(joinFunction.join(null, element));
        }
        return results;
    }

    /**
     * Phase 3 waiting/retract close emission for an arriving LEFT element whose window can no
     * longer accept a peer: emit its unmatched outer-join row only when no in-window peer is
     * still buffered. Pairs are never emitted here — they were emitted on arrival — so the
     * close cannot re-emit them.
     *
     * <p>The peer check is the same window predicate as matching, and the buffered peer is
     * <em>not</em> dropped at the close (retention owns that), so a late peer that arrives
     * after the close still pairs; that late-record double row is the pre-existing corner of
     * the window predicate, unchanged by waiting mode.
     */
    public static <L, R, O> List<O> unmatchedCloseLeft(JoinConfig<L, R, ?> config,
                                                       JoinFunction<L, R, O> joinFunction,
                                                       L element,
                                                       long timestamp,
                                                       List<TimestampedElement<R>> rightElements) throws Exception {
        if (rightElements != null) {
            for (TimestampedElement<R> rightElem : rightElements) {
                if (config.getJoinWindow().contains(timestamp, rightElem.timestamp())) {
                    return List.of(); // a peer is still buffered: the pair is already out
                }
            }
        }
        if (config.getJoinType() == JoinType.LEFT || config.getJoinType() == JoinType.FULL_OUTER) {
            return List.of(joinFunction.join(element, null));
        }
        return List.of();
    }

    /**
     * Waiting/retract close emission for an arriving RIGHT element (see
     * {@link #unmatchedCloseLeft}).
     */
    public static <L, R, O> List<O> unmatchedCloseRight(JoinConfig<L, R, ?> config,
                                                        JoinFunction<L, R, O> joinFunction,
                                                        R element,
                                                        long timestamp,
                                                        List<TimestampedElement<L>> leftElements) throws Exception {
        if (leftElements != null) {
            for (TimestampedElement<L> leftElem : leftElements) {
                if (config.getJoinWindow().contains(leftElem.timestamp(), timestamp)) {
                    return List.of();
                }
            }
        }
        if (config.getJoinType() == JoinType.RIGHT || config.getJoinType() == JoinType.FULL_OUTER) {
            return List.of(joinFunction.join(null, element));
        }
        return List.of();
    }

    /**
     * Event time at which a LEFT element's window can no longer accept a peer.
     */
    public static long closeMillisLeft(JoinWindow window, long elementTimestamp) {
        return elementTimestamp + window.getAfterMillis();
    }

    /**
     * Event time at which a RIGHT element's window can no longer accept a peer.
     */
    public static long closeMillisRight(JoinWindow window, long elementTimestamp) {
        return elementTimestamp - window.getBeforeMillis();
    }

    /**
     * Drop buffered elements older than {@code retentionTime} relative to
     * {@code referenceTime}; the window predicate guards matching, so this is memory
     * hygiene, not a correctness mechanism.
     */
    public static <T> void removeExpired(List<TimestampedElement<T>> list, long referenceTime, long retentionTime) {
        list.removeIf(elem -> referenceTime - elem.timestamp() > retentionTime);
    }

    /**
     * @return the newest timestamp buffered in the list, or 0 if empty
     */
    public static <T> long maxTimestamp(List<TimestampedElement<T>> list) {
        long max = 0L;
        for (TimestampedElement<T> elem : list) {
            if (elem.timestamp() > max) {
                max = elem.timestamp();
            }
        }
        return max;
    }

    /**
     * Remove the single oldest element across a key's two buffer lists (per-key
     * {@code maxStateSize} enforcement on the operator path).
     *
     * @return {@code false} if both lists are empty
     */
    public static <L, R> boolean removeOldestAcross(List<TimestampedElement<L>> left, List<TimestampedElement<R>> right) {
        int leftIndex = indexOfOldest(left);
        int rightIndex = indexOfOldest(right);
        if (leftIndex < 0 && rightIndex < 0) {
            return false;
        }
        boolean takeLeft = rightIndex < 0
                || (leftIndex >= 0 && left.get(leftIndex).timestamp() <= right.get(rightIndex).timestamp());
        if (takeLeft) {
            left.remove(leftIndex);
        } else {
            right.remove(rightIndex);
        }
        return true;
    }

    private static <T> int indexOfOldest(List<TimestampedElement<T>> list) {
        int index = -1;
        long oldest = 0L;
        for (int i = 0; i < list.size(); i++) {
            long ts = list.get(i).timestamp();
            if (index < 0 || ts < oldest) {
                index = i;
                oldest = ts;
            }
        }
        return index;
    }
}
