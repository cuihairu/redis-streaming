package io.github.cuihairu.redis.streaming.api.state;

/**
 * ValueState holds a single value that can be read and updated.
 *
 * @param <T> The type of the value
 */
public interface ValueState<T> extends State {

    /**
     * Get the current value
     *
     * @return The current value, or null if not set
     */
    T value();

    /**
     * Update the value
     *
     * @param value The new value
     */
    void update(T value);
}
