package io.github.cuihairu.redis.streaming.join;

import java.io.Serializable;
import java.time.Duration;

/**
 * Defines a time window for stream joins.
 * Elements within this time window are eligible for joining.
 */
public class JoinWindow implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long beforeMillis;
    private final long afterMillis;

    private JoinWindow(long beforeMillis, long afterMillis) {
        if (beforeMillis < 0 || afterMillis < 0) {
            throw new IllegalArgumentException("Window bounds must be non-negative");
        }
        this.beforeMillis = beforeMillis;
        this.afterMillis = afterMillis;
    }

    /**
     * Create a join window that allows elements within a time range before and after
     *
     * @param before Time before the element
     * @param after Time after the element
     * @return A join window
     */
    public static JoinWindow of(Duration before, Duration after) {
        return new JoinWindow(before.toMillis(), after.toMillis());
    }

    /**
     * Create a symmetric join window
     *
     * @param size The size of the window on each side
     * @return A join window
     */
    public static JoinWindow ofSize(Duration size) {
        long millis = size.toMillis();
        return new JoinWindow(millis, millis);
    }

    /**
     * Create a join window that only looks forward in time
     *
     * @param after Time to look forward
     * @return A join window
     */
    public static JoinWindow afterOnly(Duration after) {
        return new JoinWindow(0, after.toMillis());
    }

    /**
     * Create a join window that only looks backward in time
     *
     * @param before Time to look backward
     * @return A join window
     */
    public static JoinWindow beforeOnly(Duration before) {
        return new JoinWindow(before.toMillis(), 0);
    }

    /**
     * Check if a timestamp falls within the join window relative to a reference timestamp
     *
     * @param referenceTimestamp The reference timestamp
     * @param candidateTimestamp The candidate timestamp to check
     * @return true if the candidate is within the window
     */
    public boolean contains(long referenceTimestamp, long candidateTimestamp) {
        long diff = candidateTimestamp - referenceTimestamp;
        return diff >= -beforeMillis && diff <= afterMillis;
    }

    public long getBeforeMillis() {
        return beforeMillis;
    }

    public long getAfterMillis() {
        return afterMillis;
    }

    @Override
    public String toString() {
        return "JoinWindow{" +
                "before=" + beforeMillis + "ms" +
                ", after=" + afterMillis + "ms" +
                '}';
    }
}
