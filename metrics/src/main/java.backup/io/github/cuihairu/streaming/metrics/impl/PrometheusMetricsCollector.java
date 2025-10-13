package io.github.cuihairu.redis.streaming.metrics.impl;

import io.github.cuihairu.redis.streaming.metrics.MetricsCollector;
import io.github.cuihairu.redis.streaming.metrics.MetricsConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Prometheus-specific metrics collector implementation
 */
@Slf4j
public class PrometheusMetricsCollector extends MicrometerMetricsCollector {

    private final PrometheusMeterRegistry prometheusRegistry;

    public PrometheusMetricsCollector(String name, MetricsConfiguration configuration) {
        this(name, createPrometheusRegistry(configuration));
    }

    public PrometheusMetricsCollector(String name, PrometheusMeterRegistry prometheusRegistry) {
        super(name, prometheusRegistry);
        this.prometheusRegistry = prometheusRegistry;
    }

    /**
     * Get metrics in Prometheus format
     *
     * @return Prometheus formatted metrics
     */
    public String getPrometheusMetrics() {
        try {
            return prometheusRegistry.scrape();
        } catch (Exception e) {
            log.error("Error getting Prometheus metrics", e);
            return "";
        }
    }

    /**
     * Get the Prometheus registry
     *
     * @return the Prometheus meter registry
     */
    public PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }

    private static PrometheusMeterRegistry createPrometheusRegistry(MetricsConfiguration configuration) {
        PrometheusConfig prometheusConfig = new PrometheusConfig() {
            @Override
            public Duration step() {
                return configuration.getReportingInterval();
            }

            @Override
            public String get(String key) {
                Object value = configuration.getProperty("prometheus." + key);
                return value != null ? value.toString() : null;
            }
        };

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(prometheusConfig);

        // Add common tags
        String appName = configuration.getApplicationName();
        if (appName != null) {
            registry.config().commonTags("application", appName);
        }

        // Add any additional common tags from configuration
        Object commonTagsObj = configuration.getProperty("common.tags");
        if (commonTagsObj instanceof String) {
            String[] commonTags = ((String) commonTagsObj).split(",");
            for (String tag : commonTags) {
                String[] tagParts = tag.trim().split("=", 2);
                if (tagParts.length == 2) {
                    registry.config().commonTags(tagParts[0].trim(), tagParts[1].trim());
                }
            }
        }

        log.info("Created Prometheus metrics registry for application: {}", appName);
        return registry;
    }

    /**
     * Create a Prometheus metrics collector with default configuration
     *
     * @param name the collector name
     * @param applicationName the application name
     * @return the Prometheus metrics collector
     */
    public static PrometheusMetricsCollector create(String name, String applicationName) {
        MetricsConfiguration config = new MetricsConfiguration(applicationName);
        return new PrometheusMetricsCollector(name, config);
    }

    /**
     * Create a Prometheus metrics collector with custom step interval
     *
     * @param name the collector name
     * @param applicationName the application name
     * @param step the step interval
     * @return the Prometheus metrics collector
     */
    public static PrometheusMetricsCollector create(String name, String applicationName, Duration step) {
        MetricsConfiguration config = new MetricsConfiguration(applicationName, true, step, java.util.Map.of());
        return new PrometheusMetricsCollector(name, config);
    }
}