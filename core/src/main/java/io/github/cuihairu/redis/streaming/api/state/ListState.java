package io.github.cuihairu.redis.streaming.api.state;

/**
 * ListState holds a list of elements.
 *
 * @param <T> The type of elements
 */
public interface ListState<T> extends State {

    /**
     * Add an element to the list
     *
     * @param value The element to add
     */
    void add(T value);

    /**
     * Get all elements
     *
     * @return An iterable of all elements
     */
    Iterable<T> get();

    /**
     * Update the list with new elements (replaces existing)
     *
     * @param values The new elements
     */
    void update(Iterable<T> values);

    /**
     * Add all elements from an iterable
     *
     * @param values The elements to add
     */
    void addAll(Iterable<T> values);
}
