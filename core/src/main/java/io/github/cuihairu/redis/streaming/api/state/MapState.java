package io.github.cuihairu.redis.streaming.api.state;

import java.util.Map;

/**
 * MapState holds a map of key-value pairs.
 *
 * @param <K> The type of keys
 * @param <V> The type of values
 */
public interface MapState<K, V> extends State {

    /**
     * Get a value by key
     *
     * @param key The key
     * @return The value, or null if not found
     */
    V get(K key);

    /**
     * Put a key-value pair
     *
     * @param key The key
     * @param value The value
     */
    void put(K key, V value);

    /**
     * Remove a key
     *
     * @param key The key to remove
     */
    void remove(K key);

    /**
     * Check if a key exists
     *
     * @param key The key
     * @return true if the key exists
     */
    boolean contains(K key);

    /**
     * Get all entries
     *
     * @return An iterable of all entries
     */
    Iterable<Map.Entry<K, V>> entries();

    /**
     * Get all keys
     *
     * @return An iterable of all keys
     */
    Iterable<K> keys();

    /**
     * Get all values
     *
     * @return An iterable of all values
     */
    Iterable<V> values();

    /**
     * Check if the map is empty
     *
     * @return true if empty
     */
    boolean isEmpty();
}
