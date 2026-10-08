package io.github.cuihairu.redis.streaming.benchmark;

import java.util.Arrays;

/** Percentile helpers over millisecond latency samples. */
final class Percentiles {

    private Percentiles() {
    }

    /** Nearest-rank percentile over the given samples; returns 0 for empty input. */
    static double percentile(long[] samples, double p) {
        if (samples == null || samples.length == 0) {
            return 0d;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100d * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }

    static double p50(long[] samples) {
        return percentile(samples, 50);
    }

    static double p95(long[] samples) {
        return percentile(samples, 95);
    }

    static double p99(long[] samples) {
        return percentile(samples, 99);
    }
}
