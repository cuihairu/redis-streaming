package io.github.cuihairu.redis.streaming.state.backend;

import io.github.cuihairu.redis.streaming.api.state.*;

/**
 * StateBackend creates and manages state instances.
 */
public interface StateBackend {

    /**
     * Create a ValueState
     *
     * @param descriptor The state descriptor
     * @param <T> The type of the value
     * @return A ValueState instance
     */
    <T> ValueState<T> createValueState(StateDescriptor<T> descriptor);

    /**
     * Create a MapState
     *
     * @param keyType The key type
     * @param valueType The value type
     * @param name The state name
     * @param <K> The type of keys
     * @param <V> The type of values
     * @return A MapState instance
     */
    <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType);

    /**
     * Create a ListState
     *
     * @param descriptor The state descriptor
     * @param <T> The type of elements
     * @return A ListState instance
     */
    <T> ListState<T> createListState(StateDescriptor<T> descriptor);

    /**
     * Create a SetState
     *
     * @param descriptor The state descriptor
     * @param <T> The type of elements
     * @return A SetState instance
     */
    <T> SetState<T> createSetState(StateDescriptor<T> descriptor);

    /**
     * Close the state backend and release resources
     */
    void close();
}
