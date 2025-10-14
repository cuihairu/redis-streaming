package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Map;

/**
 * Micrometer binder for ClientInvoker metrics snapshot.
 */
public class ClientInvokerMetricsBinder implements MeterBinder {
    private final ClientInvoker invoker;

    public ClientInvokerMetricsBinder(ClientInvoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // total gauges
        Gauge.builder("client.invoker.total.attempts", this, b -> get("total","attempts")).register(registry);
        Gauge.builder("client.invoker.total.successes", this, b -> get("total","successes")).register(registry);
        Gauge.builder("client.invoker.total.failures", this, b -> get("total","failures")).register(registry);
        Gauge.builder("client.invoker.total.retries", this, b -> get("total","retries")).register(registry);
        Gauge.builder("client.invoker.total.cbOpenSkips", this, b -> get("total","cbOpenSkips")).register(registry);
    }

    private double get(String service, String key) {
        try {
            Map<String, Map<String, Long>> snap = invoker.getMetricsSnapshot();
            Map<String, Long> g = snap.get(service);
            if (g == null) return 0.0;
            return g.getOrDefault(key, 0L);
        } catch (Exception e) {
            return 0.0;
        }
    }
}

