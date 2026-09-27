package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import lombok.extern.slf4j.Slf4j;

/**
 * CDC-M1: shared parsing of the backpressure-related connector settings.
 *
 * <p>Both knobs are read from the generic property bag (same mechanism as
 * {@code jdbc.url} / {@code query.timeout.seconds}):</p>
 * <ul>
 *   <li>{@code event.queue.capacity} — capacity of each connector's in-memory
 *       change-event queue (default {@value #DEFAULT_QUEUE_CAPACITY}). Producers
 *       block while it is full; events are never silently dropped while the
 *       connector runs.</li>
 *   <li>{@code poll.batch.limit} — rows fetched per statement by the polling
 *       connector (default {@value #DEFAULT_POLL_BATCH_LIMIT}); every scan uses
 *       {@code LIMIT limit+1} so a batch is never truncated inside a tie group.</li>
 * </ul>
 *
 * <p>Missing, non-numeric, zero or negative values fall back to the default with a
 * warning — a typo must not disable the bounded-queue protection.</p>
 */
@Slf4j
final class BackpressureSettings {

    static final String QUEUE_CAPACITY_PROPERTY = "event.queue.capacity";
    static final String POLL_BATCH_LIMIT_PROPERTY = "poll.batch.limit";
    static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    static final int DEFAULT_POLL_BATCH_LIMIT = 1_000;

    private BackpressureSettings() {
    }

    static int positiveInt(CDCConfiguration configuration, String key, int defaultValue) {
        if (configuration == null) {
            // legacy constructor contract: a null configuration is tolerated until first use
            return defaultValue;
        }
        Object raw = configuration.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw).trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignore) {
            // fall through to the default below
        }
        log.warn("Invalid {}='{}' for connector {}; falling back to {}",
                key, raw, configuration.getName(), defaultValue);
        return defaultValue;
    }
}
