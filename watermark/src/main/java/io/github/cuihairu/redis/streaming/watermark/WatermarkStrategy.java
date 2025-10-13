package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;

import java.io.Serializable;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Strategy for generating watermarks in event streams.
 *
 * This class provides factory methods for creating common watermark strategies:
 * - For ascending timestamps
 * - For out-of-order events with bounded delay
 * - Custom watermark generators
 *
 * @param <T> The type of events in the stream
 */
public class WatermarkStrategy<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Supplier<WatermarkGenerator<T>> watermarkGeneratorSupplier;
    private final TimestampAssigner<T> timestampAssigner;

    private WatermarkStrategy(
            Supplier<WatermarkGenerator<T>> watermarkGeneratorSupplier,
            TimestampAssigner<T> timestampAssigner) {
        this.watermarkGeneratorSupplier = watermarkGeneratorSupplier;
        this.timestampAssigner = timestampAssigner;
    }

    /**
     * Create a watermark generator instance
     */
    public WatermarkGenerator<T> createWatermarkGenerator() {
        return watermarkGeneratorSupplier.get();
    }

    /**
     * Get the timestamp assigner
     */
    public TimestampAssigner<T> getTimestampAssigner() {
        return timestampAssigner;
    }

    /**
     * Extract timestamp from event using the configured assigner
     */
    public long extractTimestamp(T event, long recordTimestamp) {
        if (timestampAssigner != null) {
            return timestampAssigner.extractTimestamp(event, recordTimestamp);
        }
        return recordTimestamp;
    }

    // ========== Factory Methods ==========

    /**
     * Create a watermark strategy for strictly ascending timestamps.
     *
     * Use this when you know that timestamps are always increasing.
     */
    public static <T> WatermarkStrategy<T> forMonotonousTimestamps() {
        return new WatermarkStrategy<>(
                AscendingTimestampWatermarkGenerator::new,
                null
        );
    }

    /**
     * Create a watermark strategy for out-of-order events with bounded delay.
     *
     * @param maxOutOfOrderness The maximum time by which events can be out of order
     */
    public static <T> WatermarkStrategy<T> forBoundedOutOfOrderness(Duration maxOutOfOrderness) {
        return new WatermarkStrategy<>(
                () -> new BoundedOutOfOrdernessWatermarkGenerator<>(maxOutOfOrderness),
                null
        );
    }

    /**
     * Create a watermark strategy with a custom generator supplier
     */
    public static <T> WatermarkStrategy<T> forGenerator(
            Supplier<WatermarkGenerator<T>> generatorSupplier) {
        return new WatermarkStrategy<>(generatorSupplier, null);
    }

    /**
     * Create a watermark strategy without any watermarks (processing time mode)
     */
    public static <T> WatermarkStrategy<T> noWatermarks() {
        return new WatermarkStrategy<>(
                () -> new NoWatermarkGenerator<>(),
                null
        );
    }

    /**
     * Specify a timestamp assigner for extracting event timestamps
     */
    public WatermarkStrategy<T> withTimestampAssigner(TimestampAssigner<T> assigner) {
        return new WatermarkStrategy<>(watermarkGeneratorSupplier, assigner);
    }

    // ========== Helper Classes ==========

    /**
     * TimestampAssigner extracts timestamps from events
     */
    @FunctionalInterface
    public interface TimestampAssigner<T> extends Serializable {
        /**
         * Extract timestamp from the given event
         *
         * @param event The event
         * @param recordTimestamp The timestamp from the record metadata (if available)
         * @return The extracted timestamp
         */
        long extractTimestamp(T event, long recordTimestamp);
    }

    /**
     * Serializable bi-function for timestamp extraction
     */
    @FunctionalInterface
    public interface SerializableBiFunction<T, U, R> extends Serializable {
        R apply(T t, U u);
    }

    /**
     * Watermark generator that doesn't emit any watermarks
     */
    private static class NoWatermarkGenerator<T> implements WatermarkGenerator<T> {
        private static final long serialVersionUID = 1L;

        @Override
        public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
            // No watermarks
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
            // No watermarks
        }
    }
}
