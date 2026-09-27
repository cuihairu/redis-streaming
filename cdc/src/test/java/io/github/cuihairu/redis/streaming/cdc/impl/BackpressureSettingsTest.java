package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDC-M1: the backpressure knobs must degrade to their safe defaults — a typo or a
 * nonsensical value (0, negative, non-numeric) must never silently disable the
 * bounded-queue / bounded-scan protection.
 */
class BackpressureSettingsTest {

    @Test
    void missingPropertiesYieldDefaults() {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("bp-missing").build();
        assertEquals(BackpressureSettings.DEFAULT_QUEUE_CAPACITY,
                BackpressureSettings.positiveInt(config, BackpressureSettings.QUEUE_CAPACITY_PROPERTY,
                        BackpressureSettings.DEFAULT_QUEUE_CAPACITY));
        assertEquals(BackpressureSettings.DEFAULT_POLL_BATCH_LIMIT,
                BackpressureSettings.positiveInt(config, BackpressureSettings.POLL_BATCH_LIMIT_PROPERTY,
                        BackpressureSettings.DEFAULT_POLL_BATCH_LIMIT));
    }

    @Test
    void invalidValuesFallBackToDefault() {
        for (String bad : new String[]{"abc", "0", "-5", "12.5", " "}) {
            CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("bp-invalid")
                    .property(BackpressureSettings.POLL_BATCH_LIMIT_PROPERTY, bad)
                    .build();
            assertEquals(1_000, BackpressureSettings.positiveInt(config,
                            BackpressureSettings.POLL_BATCH_LIMIT_PROPERTY, 1_000),
                    "non-positive input '" + bad + "' must fall back to the default");
        }
    }

    @Test
    void validValuesPassThrough() {
        assertEquals(42, BackpressureSettings.positiveInt(
                configWith(BackpressureSettings.QUEUE_CAPACITY_PROPERTY, "42"),
                BackpressureSettings.QUEUE_CAPACITY_PROPERTY, 10_000));
        // surrounding whitespace is tolerated
        assertEquals(7, BackpressureSettings.positiveInt(
                configWith(BackpressureSettings.QUEUE_CAPACITY_PROPERTY, " 7 "),
                BackpressureSettings.QUEUE_CAPACITY_PROPERTY, 10_000));
        // properties are an Object bag: a pre-parsed Integer must work too
        assertEquals(64, BackpressureSettings.positiveInt(
                configWith(BackpressureSettings.QUEUE_CAPACITY_PROPERTY, 64),
                BackpressureSettings.QUEUE_CAPACITY_PROPERTY, 10_000));
    }

    private static CDCConfiguration configWith(String key, Object value) {
        return CDCConfigurationBuilder.forDatabasePolling("bp-valid").property(key, value).build();
    }
}
