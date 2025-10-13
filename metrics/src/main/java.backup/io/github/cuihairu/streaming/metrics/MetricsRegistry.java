package io.github.cuihairu.redis.streaming.metrics;

/**
 * Registry for managing multiple metrics collectors
 */
public interface MetricsRegistry {

    /**
     * Register a metrics collector
     *
     * @param collector the collector to register
     */
    void registerCollector(MetricsCollector collector);

    /**
     * Unregister a metrics collector
     *
     * @param collectorName the name of the collector to unregister
     * @return the unregistered collector, or null if not found
     */
    MetricsCollector unregisterCollector(String collectorName);

    /**
     * Get a metrics collector by name
     *
     * @param name the collector name
     * @return the collector, or null if not found
     */
    MetricsCollector getCollector(String name);

    /**
     * Get all registered collectors
     *
     * @return array of all collectors
     */
    MetricsCollector[] getAllCollectors();

    /**
     * Get the number of registered collectors
     *
     * @return collector count
     */
    int getCollectorCount();

    /**
     * Clear all metrics from all collectors
     */
    void clearAllMetrics();

    /**
     * Enable or disable all collectors
     *
     * @param enabled enable flag
     */
    void setAllEnabled(boolean enabled);

    /**
     * Check if the registry has any collectors
     *
     * @return true if registry has collectors
     */
    boolean hasCollectors();

    /**
     * Get the registry configuration
     *
     * @return the configuration
     */
    MetricsConfiguration getConfiguration();
}