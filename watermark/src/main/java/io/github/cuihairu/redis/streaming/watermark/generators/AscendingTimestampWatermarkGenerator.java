package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;

/**
 * Watermark generator for streams with strictly ascending timestamps.
 *
 * This generator assumes that timestamps are always increasing and generates
 * watermarks equal to the latest observed timestamp minus 1.
 *
 * @param <T> The type of events
 */
public class AscendingTimestampWatermarkGenerator<T> implements WatermarkGenerator<T> {

    private static final long serialVersionUID = 1L;

    /** Current watermark (latest observed timestamp - 1) */
    private long currentWatermark = Long.MIN_VALUE;

    @Override
    public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        // For ascending timestamps, watermark is always current timestamp - 1
        if (eventTimestamp > currentWatermark) {
            currentWatermark = eventTimestamp - 1;
        }
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        output.emitWatermark(new Watermark(currentWatermark));
    }

    /**
     * Get the current watermark value
     */
    public long getCurrentWatermark() {
        return currentWatermark;
    }
}
