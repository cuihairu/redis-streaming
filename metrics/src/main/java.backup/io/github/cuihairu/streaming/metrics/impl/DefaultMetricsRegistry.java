package io.github.cuihairu.redis.streaming.metrics.impl;

import io.github.cuihairu.redis.streaming.metrics.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of MetricsRegistry
 */
@Slf4j
public class DefaultMetricsRegistry implements MetricsRegistry {

    private final MetricsConfiguration configuration;
    private final Map<String, MetricsCollector> collectors = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private volatile MetricsEventListener eventListener;

    public DefaultMetricsRegistry(MetricsConfiguration configuration) {
        this.configuration = configuration;
        this.enabled.set(configuration.isEnabled());
    }

    @Override
    public void registerCollector(MetricsCollector collector) {
        if (collector == null) {
            throw new IllegalArgumentException("Collector cannot be null");
        }

        String name = collector.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Collector name cannot be null or empty");
        }

        MetricsCollector existing = collectors.put(name, collector);
        if (existing != null) {
            log.warn("Replaced existing collector with name: {}", name);
        }

        // Set collector enabled state based on registry state
        collector.setEnabled(enabled.get());

        log.info("Registered metrics collector: {}", name);
        notifyCollectorRegistered(name);
    }

    @Override
    public MetricsCollector unregisterCollector(String collectorName) {
        if (collectorName == null) {
            return null;
        }

        MetricsCollector removed = collectors.remove(collectorName);
        if (removed != null) {
            log.info("Unregistered metrics collector: {}", collectorName);
            notifyCollectorUnregistered(collectorName);
        }

        return removed;
    }

    @Override
    public MetricsCollector getCollector(String name) {
        return collectors.get(name);
    }

    @Override
    public MetricsCollector[] getAllCollectors() {
        return collectors.values().toArray(new MetricsCollector[0]);
    }

    @Override
    public int getCollectorCount() {
        return collectors.size();
    }

    @Override
    public void clearAllMetrics() {
        collectors.values().forEach(collector -> {
            try {
                collector.clear();
            } catch (Exception e) {
                log.error("Error clearing metrics for collector: {}", collector.getName(), e);
                notifyError(collector.getName(), e);
            }
        });
        log.info("Cleared all metrics from {} collectors", collectors.size());
    }

    @Override
    public void setAllEnabled(boolean enabled) {
        this.enabled.set(enabled);
        collectors.values().forEach(collector -> {
            try {
                collector.setEnabled(enabled);
            } catch (Exception e) {
                log.error("Error setting enabled state for collector: {}", collector.getName(), e);
                notifyError(collector.getName(), e);
            }
        });
        log.info("Set enabled={} for all {} collectors", enabled, collectors.size());
    }

    @Override
    public boolean hasCollectors() {
        return !collectors.isEmpty();
    }

    @Override
    public MetricsConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Set the event listener for this registry
     *
     * @param listener the event listener
     */
    public void setEventListener(MetricsEventListener listener) {
        this.eventListener = listener;

        // Set the listener on all existing collectors that support it
        collectors.values().forEach(collector -> {
            if (collector instanceof MicrometerMetricsCollector) {
                ((MicrometerMetricsCollector) collector).setEventListener(listener);
            }
        });
    }

    /**
     * Check if the registry is enabled
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Set the enabled state of the registry
     *
     * @param enabled the enabled state
     */
    public void setEnabled(boolean enabled) {
        setAllEnabled(enabled);
    }

    /**
     * Get collector names
     *
     * @return array of collector names
     */
    public String[] getCollectorNames() {
        return collectors.keySet().toArray(new String[0]);
    }

    /**
     * Get the total number of metrics across all collectors
     *
     * @return total metric count
     */
    public int getTotalMetricCount() {
        return collectors.values().stream()
                .mapToInt(collector -> collector.getCurrentMetrics().size())
                .sum();
    }

    /**
     * Get all current metrics from all collectors
     *
     * @return map of collector name to metrics
     */
    public Map<String, Map<String, Double>> getAllCurrentMetrics() {
        Map<String, Map<String, Double>> allMetrics = new ConcurrentHashMap<>();
        collectors.forEach((name, collector) -> {
            try {
                allMetrics.put(name, collector.getCurrentMetrics());
            } catch (Exception e) {
                log.error("Error getting metrics from collector: {}", name, e);
                notifyError(name, e);
            }
        });
        return allMetrics;
    }

    private void notifyCollectorRegistered(String collectorName) {
        if (eventListener != null) {
            try {
                eventListener.onCollectorRegistered(collectorName);
            } catch (Exception e) {
                log.warn("Error notifying collector registered: {}", collectorName, e);
            }
        }
    }

    private void notifyCollectorUnregistered(String collectorName) {
        if (eventListener != null) {
            try {
                eventListener.onCollectorUnregistered(collectorName);
            } catch (Exception e) {
                log.warn("Error notifying collector unregistered: {}", collectorName, e);
            }
        }
    }

    private void notifyError(String collectorName, Throwable error) {
        if (eventListener != null) {
            try {
                eventListener.onMetricsError(collectorName, error);
            } catch (Exception e) {
                log.warn("Error notifying metrics error for collector: {}", collectorName, e);
            }
        }
    }
}