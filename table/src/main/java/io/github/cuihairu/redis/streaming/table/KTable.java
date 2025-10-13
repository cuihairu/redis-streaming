package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;

import java.io.Serializable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * KTable represents a changelog stream that can be interpreted as a table.
 * Each record represents an update to a key-value pair in the table.
 *
 * Unlike streams, KTables represent the current state of a table where
 * each key has at most one corresponding value at any point in time.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public interface KTable<K, V> extends Serializable {

    /**
     * Transform the values of this table
     *
     * @param mapper The function to transform values
     * @param <VR> The type of the new values
     * @return A new KTable with transformed values
     */
    <VR> KTable<K, VR> mapValues(Function<V, VR> mapper);

    /**
     * Transform the values of this table with access to the key
     *
     * @param mapper The function to transform values with key
     * @param <VR> The type of the new values
     * @return A new KTable with transformed values
     */
    <VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper);

    /**
     * Filter records from this table
     *
     * @param predicate The predicate to filter records
     * @return A new KTable with filtered records
     */
    KTable<K, V> filter(BiFunction<K, V, Boolean> predicate);

    /**
     * Join this table with another table
     *
     * @param other The other table to join with
     * @param joiner The function to join values
     * @param <VO> The type of values in the other table
     * @param <VR> The type of the resulting values
     * @return A new KTable with joined values
     */
    <VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);

    /**
     * Left join this table with another table
     *
     * @param other The other table to join with
     * @param joiner The function to join values (right value may be null)
     * @param <VO> The type of values in the other table
     * @param <VR> The type of the resulting values
     * @return A new KTable with joined values
     */
    <VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);

    /**
     * Convert this table to a changelog stream
     *
     * @return A DataStream representing the changelog
     */
    DataStream<KeyValue<K, V>> toStream();

    /**
     * Group the table by a new key
     *
     * @param keySelector Function to select the new key
     * @param <KR> The type of the new key
     * @return A grouped table
     */
    <KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector);

    /**
     * Represents a key-value pair in the table
     */
    interface KeyValue<K, V> extends Serializable {
        K getKey();
        V getValue();

        static <K, V> KeyValue<K, V> of(K key, V value) {
            return new DefaultKeyValue<>(key, value);
        }
    }

    /**
     * Default implementation of KeyValue
     */
    class DefaultKeyValue<K, V> implements KeyValue<K, V> {
        private static final long serialVersionUID = 1L;

        private final K key;
        private final V value;

        public DefaultKeyValue(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "KeyValue{" + "key=" + key + ", value=" + value + '}';
        }
    }
}
