package io.github.cuihairu.redis.streaming.reliability;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A queue for storing failed elements that could not be processed.
 *
 * @param <T> The type of elements
 */
public class DeadLetterQueue<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ConcurrentLinkedQueue<FailedElement<T>> queue;
    private final int maxSize;
    private final AtomicInteger sizeCounter = new AtomicInteger(0);

    /**
     * Create a dead letter queue with unlimited size
     */
    public DeadLetterQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Create a dead letter queue with a maximum size
     *
     * @param maxSize Maximum number of elements to store; must be positive — a
     *                non-positive cap would silently discard every failure,
     *                defeating the queue's purpose (B-35)
     */
    public DeadLetterQueue(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.queue = new ConcurrentLinkedQueue<>();
        this.maxSize = maxSize;
    }

    /**
     * Add a failed element to the queue
     *
     * @param element The element that failed
     * @param exception The exception that caused the failure
     * @param attemptCount The number of attempts made
     * @return true if added successfully, false if queue is full
     */
    public boolean add(T element, Exception exception, int attemptCount) {
        // Fast-path check then CAS to avoid overshoot under contention
        while (true) {
            int s = sizeCounter.get();
            if (s >= maxSize) {
                return false;
            }
            if (sizeCounter.compareAndSet(s, s + 1)) {
                break;
            }
        }
        boolean offered = false;
        try {
            FailedElement<T> failedElement = new FailedElement<>(element, exception, attemptCount);
            offered = queue.offer(failedElement);
            return offered;
        } finally {
            if (!offered) {
                // Extremely rare for ConcurrentLinkedQueue.offer to fail; keep counters consistent
                sizeCounter.decrementAndGet();
            }
        }
    }

    /**
     * Get the next failed element from the queue
     *
     * @return The next failed element, or null if queue is empty
     */
    public FailedElement<T> poll() {
        FailedElement<T> e = queue.poll();
        if (e != null) sizeCounter.decrementAndGet();
        return e;
    }

    /**
     * Get the next failed element without removing it
     *
     * @return The next failed element, or null if queue is empty
     */
    public FailedElement<T> peek() {
        return queue.peek();
    }

    /**
     * Get all failed elements as a list
     *
     * @return List of all failed elements
     */
    public List<FailedElement<T>> getAll() {
        return new ArrayList<>(queue);
    }

    /**
     * Get the current size of the queue
     *
     * @return The number of elements in the queue
     */
    public int size() {
        return sizeCounter.get();
    }

    /**
     * Check if the queue is empty
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Check if the queue is full
     *
     * @return true if full
     */
    public boolean isFull() {
        return sizeCounter.get() >= maxSize;
    }

    /**
     * Clear all elements from the queue
     *
     * <p>Drains element-by-element with a matching counter decrement per removal
     * instead of queue.clear() + set(0): a bulk reset wipes the increments of adds
     * that land concurrently between the two steps, so the counter permanently
     * under-counts and the queue can exceed maxSize (B-35).
     */
    public void clear() {
        while (queue.poll() != null) {
            sizeCounter.decrementAndGet();
        }
    }

    /**
     * Get the maximum size of the queue
     *
     * @return The maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }
}
