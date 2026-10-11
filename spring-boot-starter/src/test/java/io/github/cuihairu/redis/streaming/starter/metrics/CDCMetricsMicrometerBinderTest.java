package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.cdc.CDCManager;
import io.github.cuihairu.redis.streaming.cdc.CDCMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CDC snapshot gauges (docs/Metrics-Unification-Design.md 方案 A v1): live-read at
 * scrape time, one connector tag set per gauge family, safe on unknown connectors,
 * and re-bind picks up connectors registered later.
 */
class CDCMetricsMicrometerBinderTest {

    @Test
    void exportsSnapshotCountersWithConnectorTag() {
        CDCManager manager = mock(CDCManager.class);
        CDCMetrics snap = new CDCMetrics(100, 60, 30, 8, 2, 5, 1, 12.5,
                Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_001_000L),
                Instant.ofEpochMilli(1_699_999_000_000L), "binlog:42");
        when(manager.getMetrics("orders-mysql")).thenReturn(snap);
        when(manager.getMetricsAll()).thenReturn(java.util.Map.of("orders-mysql", snap));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CDCMetricsMicrometerBinder(manager, registry);

        assertEquals(100d, gauge(registry, "redis.streaming.cdc.events.total", "orders-mysql"));
        assertEquals(60d, gauge(registry, "redis.streaming.cdc.events.inserted", "orders-mysql"));
        assertEquals(30d, gauge(registry, "redis.streaming.cdc.events.updated", "orders-mysql"));
        assertEquals(8d, gauge(registry, "redis.streaming.cdc.events.deleted", "orders-mysql"));
        assertEquals(2d, gauge(registry, "redis.streaming.cdc.events.schema_changed", "orders-mysql"));
        assertEquals(5d, gauge(registry, "redis.streaming.cdc.snapshot.records", "orders-mysql"));
        assertEquals(1d, gauge(registry, "redis.streaming.cdc.errors.total", "orders-mysql"));
        assertEquals(12.5d, gauge(registry, "redis.streaming.cdc.latency.avg.milliseconds", "orders-mysql"));
        assertEquals(1_700_000_000_000d, gauge(registry, "redis.streaming.cdc.event.time.epoch.milliseconds", "orders-mysql"));
        assertEquals(1_700_000_001_000d, gauge(registry, "redis.streaming.cdc.commit.time.epoch.milliseconds", "orders-mysql"));
    }

    @Test
    void gaugesReadLiveAtScrapeTime() {
        CDCManager manager = mock(CDCManager.class);
        CDCMetrics first = new CDCMetrics(10, 10, 0, 0, 0, 0, 0, 0.0,
                Instant.now(), null, Instant.now(), null);
        CDCMetrics second = new CDCMetrics(110, 100, 10, 0, 0, 0, 0, 1.0,
                Instant.now(), null, Instant.now(), null);
        when(manager.getMetrics("pg")).thenReturn(first, second);
        when(manager.getMetricsAll()).thenReturn(java.util.Map.of("pg", first));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CDCMetricsMicrometerBinder(manager, registry);

        assertEquals(10d, gauge(registry, "redis.streaming.cdc.events.total", "pg"));
        assertEquals(110d, gauge(registry, "redis.streaming.cdc.events.total", "pg"),
                "the gauge must follow the connector's latest snapshot, not a bind-time copy");
    }

    @Test
    void unknownConnectorReadsZeroAndNullInstantsAreZero() {
        CDCManager manager = mock(CDCManager.class);
        CDCMetrics snap = new CDCMetrics(0, 0, 0, 0, 0, 0, 0, 0.0,
                null, null, Instant.now(), null);
        when(manager.getMetrics("ghost")).thenReturn(null);
        when(manager.getMetricsAll()).thenReturn(java.util.Map.of("ghost", snap));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CDCMetricsMicrometerBinder(manager, registry);

        assertEquals(0d, gauge(registry, "redis.streaming.cdc.events.total", "ghost"));
        assertEquals(0d, gauge(registry, "redis.streaming.cdc.event.time.epoch.milliseconds", "ghost"));
        assertEquals(0d, gauge(registry, "redis.streaming.cdc.commit.time.epoch.milliseconds", "ghost"));
    }

    @Test
    void rebindPicksUpConnectorsRegisteredLater() {
        CDCManager manager = mock(CDCManager.class);
        CDCMetrics a = new CDCMetrics(1, 1, 0, 0, 0, 0, 0, 0.0, null, null, Instant.now(), null);
        CDCMetrics b = new CDCMetrics(2, 2, 0, 0, 0, 0, 0, 0.0, null, null, Instant.now(), null);
        when(manager.getMetrics("a")).thenReturn(a);
        when(manager.getMetrics("b")).thenReturn(b);
        when(manager.getMetricsAll())
                .thenReturn(java.util.Map.of("a", a))
                .thenReturn(java.util.Map.of("a", a, "b", b));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CDCMetricsMicrometerBinder binder = new CDCMetricsMicrometerBinder(manager, registry);
        assertEquals(1d, gauge(registry, "redis.streaming.cdc.events.total", "a"));
        assertNull(registry.find("redis.streaming.cdc.events.total").tag("connector", "b").gauge(),
                "a connector registered after construction is not bound yet");

        binder.bind();

        assertEquals(2d, gauge(registry, "redis.streaming.cdc.events.total", "b"),
                "re-bind picks up the late-registered connector");
    }

    private static double gauge(MeterRegistry registry, String name, String connector) {
        Gauge g = registry.find(name).tag("connector", connector).gauge();
        assertNotNull(g, name + " for " + connector);
        return g.value();
    }
}
