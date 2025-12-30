package io.github.cuihairu.redis.streaming.api.stream;

import java.util.function.Function;
import java.util.function.Predicate;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner;

/**
 * DataStream represents a stream of elements of the same type.
 * It is the core abstraction for stream processing operations.
 *
 * @param <T> The type of elements in the stream
 */
public interface DataStream<T> {

    /**
     * Apply a map transformation to the stream
     *
     * @param mapper The function to apply to each element
     * @param <R> The type of the resulting elements
     * @return A new DataStream with transformed elements
     */
    <R> DataStream<R> map(Function<T, R> mapper);

    /**
     * Filter elements based on a predicate
     *
     * @param predicate The predicate to test each element
     * @return A new DataStream containing only matching elements
     */
    DataStream<T> filter(Predicate<T> predicate);

    /**
     * Apply a flat map transformation to the stream
     *
     * @param mapper The function to apply to each element, returning an iterable
     * @param <R> The type of the resulting elements
     * @return A new DataStream with flattened elements
     */
    <R> DataStream<R> flatMap(Function<T, Iterable<R>> mapper);

    /**
     * Key the stream by a key selector
     *
     * @param keySelector Function to extract the key from elements
     * @param <K> The type of the key
     * @return A KeyedStream partitioned by the key
     */
    <K> KeyedStream<K, T> keyBy(Function<T, K> keySelector);

    /**
     * Add a sink to consume the stream
     *
     * @param sink The sink to consume elements
     * @return This DataStream for chaining
     */
    DataStream<T> addSink(StreamSink<T> sink);

    /**
     * Print elements to stdout (for debugging)
     *
     * @return This DataStream for chaining
     */
    DataStream<T> print();

    /**
     * Print elements with a prefix (for debugging)
     *
     * @param prefix The prefix to print before each element
     * @return This DataStream for chaining
     */
    DataStream<T> print(String prefix);

    /**
     * Assign watermarks to this stream.
     *
     * <p>Note: Not all runtime implementations support this operation.</p>
     *
     * @param watermarkGenerator Watermark generator
     * @return A stream with watermark tracking enabled
     */
    default DataStream<T> assignTimestampsAndWatermarks(WatermarkGenerator<T> watermarkGenerator) {
        throw new UnsupportedOperationException("This runtime does not support watermark assignment");
    }

    /**
     * Assign event-time timestamps and watermarks to this stream.
     *
     * <p>This overload allows extracting event-time timestamps from elements via a {@link TimestampAssigner}.
     * Not all runtime implementations support this operation.</p>
     *
     * @param timestampAssigner  event timestamp extractor
     * @param watermarkGenerator watermark generator
     * @return a stream with event-time timestamps and watermark tracking enabled
     */
    default DataStream<T> assignTimestampsAndWatermarks(
            TimestampAssigner<T> timestampAssigner,
            WatermarkGenerator<T> watermarkGenerator) {
        throw new UnsupportedOperationException("This runtime does not support event-time timestamp assignment");
    }
}
