package io.github.cuihairu.redis.streaming.api.watermark;

import java.io.Serializable;

/**
 * WatermarkGenerator generates watermarks based on events.
 *
 * @param <T> The type of events
 */
public interface WatermarkGenerator<T> extends Serializable {

    /**
     * Process an event and potentially generate a watermark
     *
     * @param event The event
     * @param eventTimestamp The timestamp of the event
     * @param output The output for emitting watermarks
     */
    void onEvent(T event, long eventTimestamp, WatermarkOutput output);

    /**
     * Called periodically to potentially generate a watermark
     *
     * @param output The output for emitting watermarks
     */
    void onPeriodicEmit(WatermarkOutput output);

    /**
     * WatermarkOutput is used to emit watermarks
     */
    interface WatermarkOutput {
        /**
         * Emit a watermark
         */
        void emitWatermark(Watermark watermark);

        /**
         * Mark the stream as idle (no events)
         */
        void markIdle();

        /**
         * Mark the stream as active
         */
        void markActive();
    }
}
