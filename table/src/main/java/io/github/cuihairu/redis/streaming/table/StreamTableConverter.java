package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.table.impl.InMemoryKTable;

import java.util.function.Function;

/**
 * Utility class for converting between streams and tables.
 */
public class StreamTableConverter {

    private StreamTableConverter() {
        // Utility class
    }

    /**
     * Convert a DataStream to a KTable.
     * The stream must contain KeyValue pairs.
     *
     * @param stream The stream to convert
     * @param <K> The type of the key
     * @param <V> The type of the value
     * @return A KTable built from the stream
     */
    public static <K, V> KTable<K, V> toTable(DataStream<KTable.KeyValue<K, V>> stream) {
        // For now, return an empty in-memory table
        // In a real implementation, this would consume the stream and populate the table
        return new InMemoryKTable<>();
    }

    /**
     * Convert a regular DataStream to a KTable by extracting key-value pairs.
     *
     * @param stream The stream to convert
     * @param keyExtractor Function to extract the key from each element
     * @param valueExtractor Function to extract the value from each element
     * @param <T> The type of stream elements
     * @param <K> The type of the key
     * @param <V> The type of the value
     * @return A KTable built from the stream
     */
    public static <T, K, V> KTable<K, V> toTable(
            DataStream<T> stream,
            Function<T, K> keyExtractor,
            Function<T, V> valueExtractor) {

        // Convert stream elements to KeyValue pairs
        DataStream<KTable.KeyValue<K, V>> kvStream = stream.map(element ->
                KTable.KeyValue.of(
                        keyExtractor.apply(element),
                        valueExtractor.apply(element)
                )
        );

        return toTable(kvStream);
    }

    /**
     * Create a KTable from a keyed stream.
     * Each element in the stream represents an update to the table.
     *
     * @param keyExtractor Function to extract the key
     * @param valueExtractor Function to extract the value
     * @param <T> The type of stream elements
     * @param <K> The type of the key
     * @param <V> The type of the value
     * @return A builder for creating KTables from streams
     */
    public static <T, K, V> TableBuilder<T, K, V> tableBuilder(
            Function<T, K> keyExtractor,
            Function<T, V> valueExtractor) {
        return new TableBuilder<>(keyExtractor, valueExtractor);
    }

    /**
     * Builder class for creating KTables from streams
     */
    public static class TableBuilder<T, K, V> {
        private final Function<T, K> keyExtractor;
        private final Function<T, V> valueExtractor;

        private TableBuilder(Function<T, K> keyExtractor, Function<T, V> valueExtractor) {
            this.keyExtractor = keyExtractor;
            this.valueExtractor = valueExtractor;
        }

        /**
         * Build a KTable from the stream
         *
         * @param stream The stream to convert
         * @return A KTable
         */
        public KTable<K, V> build(DataStream<T> stream) {
            return toTable(stream, keyExtractor, valueExtractor);
        }
    }
}
