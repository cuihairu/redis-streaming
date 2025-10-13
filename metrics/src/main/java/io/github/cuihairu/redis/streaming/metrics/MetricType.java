package io.github.cuihairu.redis.streaming.metrics;

import java.io.Serializable;

/**
 * Represents different types of metrics that can be collected.
 */
public enum MetricType implements Serializable {
    /**
     * Counter metric - monotonically increasing value
     */
    COUNTER,

    /**
     * Gauge metric - arbitrary value that can go up or down
     */
    GAUGE,

    /**
     * Histogram metric - distribution of values
     */
    HISTOGRAM,

    /**
     * Meter metric - rate of events over time
     */
    METER,

    /**
     * Timer metric - duration and rate of events
     */
    TIMER
}
