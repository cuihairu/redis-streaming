package io.github.cuihairu.redis.streaming.api.state;

/**
 * SetState holds a set of unique elements.
 *
 * @param <T> The type of elements
 */
public interface SetState<T> extends State {

    /**
     * Add an element to the set
     *
     * @param value The element to add
     * @return true if the element was added (not already present)
     */
    boolean add(T value);

    /**
     * Remove an element from the set
     *
     * @param value The element to remove
     * @return true if the element was removed
     */
    boolean remove(T value);

    /**
     * Check if an element exists in the set
     *
     * @param value The element to check
     * @return true if the element exists
     */
    boolean contains(T value);

    /**
     * Get all elements
     *
     * @return An iterable of all elements
     */
    Iterable<T> get();

    /**
     * Check if the set is empty
     *
     * @return true if empty
     */
    boolean isEmpty();

    /**
     * Get the size of the set
     *
     * @return The number of elements
     */
    int size();
}
