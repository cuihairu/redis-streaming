package io.github.cuihairu.redis.streaming.metrics;

import io.github.cuihairu.redis.streaming.metrics.impl.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Factory for creating metrics collectors and registries
 */
public class MetricsFactory {

    public enum CollectorType {
        SIMPLE,
        MICROMETER,
        PROMETHEUS
    }

    /**
     * Create a metrics collector based on type and configuration
     *
     * @param type the collector type
     * @param name the collector name
     * @param configuration the configuration
     * @return the metrics collector
     */
    public static MetricsCollector createCollector(CollectorType type, String name, MetricsConfiguration configuration) {
        switch (type) {
            case SIMPLE:
                return new SimpleMetricsCollector(name);
            case MICROMETER:
                return new MicrometerMetricsCollector(name, new SimpleMeterRegistry());
            case PROMETHEUS:
                return new PrometheusMetricsCollector(name, configuration);
            default:
                throw new IllegalArgumentException("Unsupported collector type: " + type);
        }
    }

    /**
     * Create a metrics collector based on type name
     *
     * @param typeName the collector type name
     * @param name the collector name
     * @param configuration the configuration
     * @return the metrics collector
     */
    public static MetricsCollector createCollector(String typeName, String name, MetricsConfiguration configuration) {
        try {
            CollectorType type = CollectorType.valueOf(typeName.toUpperCase());
            return createCollector(type, name, configuration);
        } catch (IllegalArgumentException e) {
            // Try to infer from configuration
            String registryType = configuration.getStringProperty("registry.type");
            if (registryType != null) {
                try {
                    CollectorType type = CollectorType.valueOf(registryType.toUpperCase());
                    return createCollector(type, name, configuration);
                } catch (IllegalArgumentException ex) {
                    // Fall back to simple
                    return createCollector(CollectorType.SIMPLE, name, configuration);
                }
            }
            throw new IllegalArgumentException("Unknown collector type: " + typeName, e);
        }
    }

    /**
     * Create a simple metrics collector
     *
     * @param name the collector name
     * @return the simple metrics collector
     */
    public static MetricsCollector createSimpleCollector(String name) {
        return new SimpleMetricsCollector(name);
    }

    /**
     * Create a Micrometer metrics collector with default registry
     *
     * @param name the collector name
     * @return the Micrometer metrics collector
     */
    public static MetricsCollector createMicrometerCollector(String name) {
        return new MicrometerMetricsCollector(name, new SimpleMeterRegistry());
    }

    /**
     * Create a Micrometer metrics collector with custom registry
     *
     * @param name the collector name
     * @param registry the meter registry
     * @return the Micrometer metrics collector
     */
    public static MetricsCollector createMicrometerCollector(String name, MeterRegistry registry) {
        return new MicrometerMetricsCollector(name, registry);
    }

    /**
     * Create a Prometheus metrics collector
     *
     * @param name the collector name
     * @param configuration the configuration
     * @return the Prometheus metrics collector
     */
    public static MetricsCollector createPrometheusCollector(String name, MetricsConfiguration configuration) {
        return new PrometheusMetricsCollector(name, configuration);
    }

    /**
     * Create a Prometheus metrics collector with application name
     *
     * @param name the collector name
     * @param applicationName the application name
     * @return the Prometheus metrics collector
     */
    public static MetricsCollector createPrometheusCollector(String name, String applicationName) {
        MetricsConfiguration config = new MetricsConfiguration(applicationName);
        return new PrometheusMetricsCollector(name, config);
    }

    /**
     * Create a metrics registry with default configuration
     *
     * @param applicationName the application name
     * @return the metrics registry
     */
    public static MetricsRegistry createRegistry(String applicationName) {
        MetricsConfiguration config = new MetricsConfiguration(applicationName);
        return new DefaultMetricsRegistry(config);
    }

    /**
     * Create a metrics registry with custom configuration
     *
     * @param configuration the configuration
     * @return the metrics registry
     */
    public static MetricsRegistry createRegistry(MetricsConfiguration configuration) {
        return new DefaultMetricsRegistry(configuration);
    }

    /**
     * Create a complete metrics setup with collector and registry
     *
     * @param collectorType the collector type
     * @param collectorName the collector name
     * @param applicationName the application name
     * @return the metrics setup
     */
    public static MetricsSetup createSetup(CollectorType collectorType, String collectorName, String applicationName) {
        MetricsConfiguration config = new MetricsConfiguration(applicationName);
        MetricsCollector collector = createCollector(collectorType, collectorName, config);
        MetricsRegistry registry = createRegistry(config);

        registry.registerCollector(collector);

        return new MetricsSetup(registry, collector, config);
    }

    /**
     * Create a Prometheus metrics setup
     *
     * @param collectorName the collector name
     * @param applicationName the application name
     * @return the Prometheus metrics setup
     */
    public static MetricsSetup createPrometheusSetup(String collectorName, String applicationName) {
        return createSetup(CollectorType.PROMETHEUS, collectorName, applicationName);
    }

    /**
     * Create a simple metrics setup
     *
     * @param collectorName the collector name
     * @param applicationName the application name
     * @return the simple metrics setup
     */
    public static MetricsSetup createSimpleSetup(String collectorName, String applicationName) {
        return createSetup(CollectorType.SIMPLE, collectorName, applicationName);
    }

    /**
     * Represents a complete metrics setup
     */
    public static class MetricsSetup {
        private final MetricsRegistry registry;
        private final MetricsCollector collector;
        private final MetricsConfiguration configuration;

        public MetricsSetup(MetricsRegistry registry, MetricsCollector collector, MetricsConfiguration configuration) {
            this.registry = registry;
            this.collector = collector;
            this.configuration = configuration;
        }

        public MetricsRegistry getRegistry() {
            return registry;
        }

        public MetricsCollector getCollector() {
            return collector;
        }

        public MetricsConfiguration getConfiguration() {
            return configuration;
        }

        /**
         * Add an event listener to the setup
         *
         * @param listener the event listener
         */
        public void setEventListener(MetricsEventListener listener) {
            if (registry instanceof DefaultMetricsRegistry) {
                ((DefaultMetricsRegistry) registry).setEventListener(listener);
            }
        }

        /**
         * Enable or disable metrics collection
         *
         * @param enabled the enabled state
         */
        public void setEnabled(boolean enabled) {
            if (registry instanceof DefaultMetricsRegistry) {
                ((DefaultMetricsRegistry) registry).setEnabled(enabled);
            }
        }

        /**
         * Get Prometheus metrics if collector supports it
         *
         * @return Prometheus formatted metrics, or empty string if not supported
         */
        public String getPrometheusMetrics() {
            if (collector instanceof PrometheusMetricsCollector) {
                return ((PrometheusMetricsCollector) collector).getPrometheusMetrics();
            }
            return "";
        }
    }
}