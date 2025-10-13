package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;

import java.time.Duration;

/**
 * Watermark generator that allows events to be out of order by a bounded amount of time.
 *
 * This generator tracks the maximum observed timestamp and generates watermarks that lag
 * behind by the specified maxOutOfOrderness duration.
 *
 * @param <T> The type of events
 */
public class BoundedOutOfOrdernessWatermarkGenerator<T> implements WatermarkGenerator<T> {

    private static final long serialVersionUID = 1L;

    /** Maximum time by which events can be out of order */
    private final long maxOutOfOrdernessMillis;

    /** Maximum observed timestamp so far */
    private long maxTimestamp;

    /**
     * Create a BoundedOutOfOrdernessWatermarkGenerator with specified max out-of-orderness
     *
     * @param maxOutOfOrderness Maximum duration by which events can be out of order
     */
    public BoundedOutOfOrdernessWatermarkGenerator(Duration maxOutOfOrderness) {
        this.maxOutOfOrdernessMillis = maxOutOfOrderness.toMillis();
        this.maxTimestamp = Long.MIN_VALUE + maxOutOfOrdernessMillis + 1;
    }

    @Override
    public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        // Update max timestamp if current event has a later timestamp
        if (eventTimestamp > maxTimestamp) {
            maxTimestamp = eventTimestamp;
        }
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        // Emit watermark = max observed timestamp - max out of orderness
        output.emitWatermark(new Watermark(maxTimestamp - maxOutOfOrdernessMillis - 1));
    }

    /**
     * Get the current maximum observed timestamp
     */
    public long getMaxTimestamp() {
        return maxTimestamp;
    }

    /**
     * Get the configured max out of orderness in milliseconds
     */
    public long getMaxOutOfOrdernessMillis() {
        return maxOutOfOrdernessMillis;
    }
}
