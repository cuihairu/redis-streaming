package io.github.cuihairu.redis.streaming.join;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory implementation of stream join for testing and simple use cases.
 * Maintains buffered elements from both streams and performs joins based on configuration.
 *
 * @param <L> The type of left stream elements
 * @param <R> The type of right stream elements
 * @param <K> The type of the join key
 * @param <O> The type of output elements
 */
public class StreamJoiner<L, R, K, O> {

    private final JoinConfig<L, R, K> config;
    private final JoinFunction<L, R, O> joinFunction;

    // State: buffered elements from each stream, keyed by join key
    private final Map<K, List<TimestampedElement<L>>> leftBuffer;
    private final Map<K, List<TimestampedElement<R>>> rightBuffer;

    public StreamJoiner(JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) {
        config.validate();
        this.config = config;
        this.joinFunction = joinFunction;
        this.leftBuffer = new ConcurrentHashMap<>();
        this.rightBuffer = new ConcurrentHashMap<>();
    }

    /**
     * Process an element from the left stream
     *
     * @param element The element to process
     * @return List of joined output elements
     */
    public List<O> processLeft(L element) throws Exception {
        K key = config.getLeftKeySelector().apply(element);
        long timestamp = extractTimestamp(element, config.getLeftTimestampExtractor());

        // Buffer the left element
        leftBuffer.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new TimestampedElement<>(element, timestamp));

        // Find matching right elements
        List<O> results = new ArrayList<>();
        List<TimestampedElement<R>> rightElements = rightBuffer.get(key);

        if (rightElements != null) {
            for (TimestampedElement<R> rightElem : rightElements) {
                if (config.getJoinWindow().contains(rightElem.timestamp, timestamp)) {
                    O joined = joinFunction.join(element, rightElem.element);
                    results.add(joined);
                }
            }
        }

        // For LEFT join, emit even if no match
        if (config.getJoinType() == JoinType.LEFT && results.isEmpty()) {
            O joined = joinFunction.join(element, null);
            results.add(joined);
        }

        cleanup();
        return results;
    }

    /**
     * Process an element from the right stream
     *
     * @param element The element to process
     * @return List of joined output elements
     */
    public List<O> processRight(R element) throws Exception {
        K key = config.getRightKeySelector().apply(element);
        long timestamp = extractTimestamp(element, config.getRightTimestampExtractor());

        // Buffer the right element
        rightBuffer.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new TimestampedElement<>(element, timestamp));

        // Find matching left elements (only for INNER and RIGHT joins emit here)
        List<O> results = new ArrayList<>();

        // For INNER and LEFT joins, we emit from processLeft
        // For RIGHT join, we emit from processRight
        if (config.getJoinType() == JoinType.RIGHT || config.getJoinType() == JoinType.FULL_OUTER) {
            List<TimestampedElement<L>> leftElements = leftBuffer.get(key);

            if (leftElements != null) {
                for (TimestampedElement<L> leftElem : leftElements) {
                    if (config.getJoinWindow().contains(leftElem.timestamp, timestamp)) {
                        O joined = joinFunction.join(leftElem.element, element);
                        results.add(joined);
                    }
                }
            }

            // For RIGHT join, emit even if no match
            if (config.getJoinType() == JoinType.RIGHT && results.isEmpty()) {
                O joined = joinFunction.join(null, element);
                results.add(joined);
            }
        }

        cleanup();
        return results;
    }

    /**
     * Extract timestamp from element, using current time if no extractor provided
     */
    private <T> long extractTimestamp(T element, Function<T, Long> extractor) {
        if (extractor != null) {
            return extractor.apply(element);
        }
        return System.currentTimeMillis();
    }

    /**
     * Clean up old elements from buffers based on retention time
     */
    private void cleanup() {
        // Don't use current system time for cleanup - use max timestamp seen
        // This allows tests with arbitrary timestamps to work correctly
        long maxTimestamp = Math.max(getMaxTimestamp(leftBuffer), getMaxTimestamp(rightBuffer));

        if (maxTimestamp > 0) {
            long retentionTime = config.getStateRetentionTime();
            cleanupBuffer(leftBuffer, maxTimestamp, retentionTime);
            cleanupBuffer(rightBuffer, maxTimestamp, retentionTime);
        }
    }

    private <T> long getMaxTimestamp(Map<K, List<TimestampedElement<T>>> buffer) {
        return buffer.values().stream()
                .flatMap(List::stream)
                .mapToLong(elem -> elem.timestamp)
                .max()
                .orElse(0L);
    }

    private <T> void cleanupBuffer(Map<K, List<TimestampedElement<T>>> buffer,
                                    long referenceTime, long retentionTime) {
        buffer.values().forEach(list ->
                list.removeIf(elem -> referenceTime - elem.timestamp > retentionTime)
        );
        buffer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Get the current size of left buffer
     */
    public int getLeftBufferSize() {
        return leftBuffer.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Get the current size of right buffer
     */
    public int getRightBufferSize() {
        return rightBuffer.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Clear all buffered state
     */
    public void clear() {
        leftBuffer.clear();
        rightBuffer.clear();
    }

    /**
     * Internal class to hold elements with timestamps
     */
    private static class TimestampedElement<T> {
        final T element;
        final long timestamp;

        TimestampedElement(T element, long timestamp) {
            this.element = element;
            this.timestamp = timestamp;
        }
    }
}
