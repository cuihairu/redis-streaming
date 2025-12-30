package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;

final class WatermarkState {

    private volatile long watermark = Long.MIN_VALUE;
    private volatile boolean idle = false;

    long getWatermark() {
        return watermark;
    }

    boolean isIdle() {
        return idle;
    }

    void emit(Watermark watermark) {
        if (watermark == null) {
            return;
        }
        long ts = watermark.getTimestamp();
        if (ts > this.watermark) {
            this.watermark = ts;
        }
    }

    void markIdle() {
        idle = true;
    }

    void markActive() {
        idle = false;
    }
}

