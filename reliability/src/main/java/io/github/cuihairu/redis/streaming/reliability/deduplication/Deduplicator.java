package io.github.cuihairu.redis.streaming.reliability.deduplication;

/**
 * Deduplicator interface for detecting duplicate elements.
 *
 * @param <T> the type of elements to deduplicate
 */
public interface Deduplicator<T> {

    /**
     * Check if an element is a duplicate.
     *
     * @param element the element to check
     * @return true if the element is a duplicate, false otherwise
     */
    boolean isDuplicate(T element);

    /**
     * Mark an element as seen.
     *
     * @param element the element to mark
     */
    void markAsSeen(T element);

    /**
     * Check and mark an element in a single operation.
     * This is an atomic operation for thread safety.
     *
     * @param element the element to check and mark
     * @return true if the element was a duplicate (already seen), false if it's new
     */
    default boolean checkAndMark(T element) {
        if (isDuplicate(element)) {
            return true;
        }
        markAsSeen(element);
        return false;
    }

    /**
     * Clear all deduplication state.
     */
    void clear();

    /**
     * Get the approximate number of unique elements seen.
     *
     * @return the count of unique elements
     */
    long getUniqueCount();
}
