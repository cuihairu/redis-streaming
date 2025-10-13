package io.github.cuihairu.redis.streaming.table;

import java.io.Serializable;
import java.util.function.BiFunction;

/**
 * KGroupedTable represents a table that has been grouped by a key.
 * It allows aggregation operations to be performed on the grouped data.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public interface KGroupedTable<K, V> extends Serializable {

    /**
     * Aggregate the grouped table
     *
     * @param initializer Function to create the initial aggregate value
     * @param adder Function to add a value to the aggregate
     * @param subtractor Function to subtract a value from the aggregate
     * @param <VR> The type of the aggregate result
     * @return A KTable with aggregated values
     */
    <VR> KTable<K, VR> aggregate(
            java.util.function.Supplier<VR> initializer,
            BiFunction<K, V, VR> adder,
            BiFunction<K, V, VR> subtractor
    );

    /**
     * Count the number of records for each key
     *
     * @return A KTable with counts
     */
    KTable<K, Long> count();

    /**
     * Reduce the grouped table
     *
     * @param adder Function to add values
     * @param subtractor Function to subtract values
     * @return A KTable with reduced values
     */
    KTable<K, V> reduce(BiFunction<V, V, V> adder, BiFunction<V, V, V> subtractor);
}
