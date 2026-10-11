package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.cdc.CDCManager;
import io.github.cuihairu.redis.streaming.cdc.CDCMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;

/**
 * Exposes {@link CDCMetrics} snapshots of every registered CDC connector as
 * Micrometer gauges (docs/Metrics-Unification-Design.md 方案 A v1) — until now
 * CDC counters were readable in-process only, invisible to Micrometer/Prometheus.
 *
 * <p>Gauge values are read live from the manager at scrape time, so the latest
 * connector snapshot is always exported without any polling on our side. Names
 * follow the {@code redis.streaming.<module>.<metric>} convention with a
 * {@code connector} tag; call {@link #bind()} again after registering new
 * connectors to pick up their gauges (idempotent for already-bound ones).</p>
 */
public class CDCMetricsMicrometerBinder {

    private final CDCManager manager;
    private final MeterRegistry registry;
    private final ConcurrentMap<String, Boolean> bound = new ConcurrentHashMap<>();

    public CDCMetricsMicrometerBinder(CDCManager manager, MeterRegistry registry) {
        this.manager = manager;
        this.registry = registry;
        bind();
    }

    /** (Re)bind gauges for every connector currently known to the manager. */
    public void bind() {
        for (String name : manager.getMetricsAll().keySet()) {
            if (bound.putIfAbsent(name, Boolean.TRUE) == null) {
                bindConnector(name);
            }
        }
    }

    private void bindConnector(String name) {
        gauge(name, "redis.streaming.cdc.events.total", m -> m.getTotalEventsCaptured());
        gauge(name, "redis.streaming.cdc.events.inserted", m -> m.getInsertEvents());
        gauge(name, "redis.streaming.cdc.events.updated", m -> m.getUpdateEvents());
        gauge(name, "redis.streaming.cdc.events.deleted", m -> m.getDeleteEvents());
        gauge(name, "redis.streaming.cdc.events.schema_changed", m -> m.getSchemaChangeEvents());
        gauge(name, "redis.streaming.cdc.snapshot.records", m -> m.getSnapshotRecords());
        gauge(name, "redis.streaming.cdc.errors.total", m -> m.getErrorsCount());
        gauge(name, "redis.streaming.cdc.latency.avg.milliseconds", m -> m.getAverageEventLatencyMs());
        gauge(name, "redis.streaming.cdc.event.rate.per.second", m -> m.getEventRate());
        gauge(name, "redis.streaming.cdc.event.time.epoch.milliseconds",
                m -> m.getLastEventTime() == null ? 0d : (double) m.getLastEventTime().toEpochMilli());
        gauge(name, "redis.streaming.cdc.commit.time.epoch.milliseconds",
                m -> m.getLastCommitTime() == null ? 0d : (double) m.getLastCommitTime().toEpochMilli());
    }

    private void gauge(String connector, String metric, ToDoubleFunction<CDCMetrics> read) {
        Gauge.builder(metric, () -> {
                    CDCMetrics snapshot = manager.getMetrics(connector);
                    return snapshot == null ? 0d : read.applyAsDouble(snapshot);
                })
                .tag("connector", connector)
                .register(registry);
    }
}
