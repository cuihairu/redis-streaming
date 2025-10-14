package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * Service Consumer interface
 * Represents the role of a service consumer in the Nacos architecture
 *
 * Service consumers are responsible for:
 * 1. Discovering available service instances
 * 2. Subscribing to service changes
 * 3. Selecting instances for load balancing
 */
public interface ServiceConsumer {

    /**
     * Get all instances of a service (including unhealthy ones)
     *
     * @param serviceName the name of the service to discover
     * @return list of all service instances
     * @throws IllegalStateException if the consumer is not running
     */
    List<ServiceInstance> getAllInstances(String serviceName);

    /**
     * Get healthy instances of a service for load balancing
     *
     * @param serviceName the name of the service to discover
     * @return list of healthy service instances
     * @throws IllegalStateException if the consumer is not running
     */
    List<ServiceInstance> getHealthyInstances(String serviceName);

    /**
     * Get instances of a service with health filter
     *
     * @param serviceName the name of the service to discover
     * @param healthy whether to filter for healthy instances only
     * @return list of service instances
     * @throws IllegalStateException if the consumer is not running
     */
    List<ServiceInstance> getInstances(String serviceName, boolean healthy);

    /**
     * Get instances filtered by metadata (supports comparison operators)
     *
     * @param serviceName the name of the service to discover
     * @param metadataFilters metadata filter conditions (AND relationship), supports comparison operators:
     * <ul>
     *   <li>"field": "value" - equals (default)</li>
     *   <li>"field:==": "value" - equals (explicit)</li>
     *   <li>"field:!=": "value" - not equals</li>
     *   <li>"field:>": "value" - greater than</li>
     *   <li>"field:>=": "value" - greater than or equals</li>
     *   <li>"field:&lt;": "value" - less than</li>
     *   <li>"field:&lt;=": "value" - less than or equals</li>
     * </ul>
     *
     * <p>Comparison rules:</p>
     * <ol>
     *   <li>Tries numeric comparison first (recommended for weight, age, cpu, etc.)</li>
     *   <li>Falls back to string comparison (lexicographic order, use with caution)</li>
     * </ol>
     *
     * <p>Example:</p>
     * <pre>
     * // ✅ Recommended: numeric comparison
     * Map&lt;String, String&gt; filters = new HashMap&lt;&gt;();
     * filters.put("weight:>=", "10");      // weight >= 10
     * filters.put("cpu_usage:&lt;", "80");    // CPU usage &lt; 80
     *
     * // ✅ Recommended: string equality
     * filters.put("region", "us-east-1");  // exact match
     * filters.put("status:!=", "down");    // status not equals
     * </pre>
     *
     * @return list of matching service instances
     * @throws IllegalStateException if the consumer is not running
     */
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Get healthy instances filtered by metadata
     *
     * @param serviceName the name of the service to discover
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     * @throws IllegalStateException if the consumer is not running
     */
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Subscribe to service changes
     *
     * @param serviceName the name of the service to subscribe to
     * @param listener the listener to notify of changes
     * @throws IllegalStateException if the consumer is not running
     */
    void subscribe(String serviceName, ServiceChangeListener listener);

    /**
     * Unsubscribe from service changes
     *
     * @param serviceName the name of the service to unsubscribe from
     * @param listener the listener to remove
     */
    void unsubscribe(String serviceName, ServiceChangeListener listener);

    /**
     * Start the service consumer
     */
    void start();

    /**
     * Stop the service consumer
     */
    void stop();

    /**
     * Check if the service consumer is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();
}