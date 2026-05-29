package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * Service discovery interface
 * Provides service instance discovery and listening capabilities
 */
public interface ServiceDiscovery {

    /**
     * Discover service instances
     *
     * @param serviceName the service name
     * @return list of service instances
     */
    List<ServiceInstance> discover(String serviceName);

    /**
     * Discover healthy service instances
     *
     * @param serviceName the service name
     * @return list of healthy service instances
     */
    List<ServiceInstance> discoverHealthy(String serviceName);

    /**
     * Discover service instances filtered by metadata (supports comparison operators)
     *
     * @param serviceName the service name
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
     *   <li>Numeric comparison is attempted first (recommended for weight, age, cpu, etc.)</li>
     *   <li>Falls back to string comparison (lexicographic order, use with caution)</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <pre>
     * // Recommended: numeric comparison
     * Map&lt;String, String&gt; filters = new HashMap&lt;&gt;();
     * filters.put("weight:>=", "10");      // weight >= 10
     * filters.put("cpu_usage:&lt;", "80");    // CPU usage &lt; 80
     *
     * // Recommended: string equality
     * filters.put("region", "us-east-1");  // exact match
     * filters.put("status:!=", "down");    // status not equals
     * </pre>
     *
     * @return list of matching service instances
     */
    List<ServiceInstance> discoverByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Discover healthy service instances filtered by metadata
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Subscribe to service changes
     *
     * @param serviceName the service name
     * @param listener the service change listener
     */
    void subscribe(String serviceName, ServiceChangeListener listener);

    /**
     * Unsubscribe from service changes
     *
     * @param serviceName the service name
     * @param listener the service change listener
     */
    void unsubscribe(String serviceName, ServiceChangeListener listener);

    /**
     * Start service discovery
     */
    void start();

    /**
     * Stop service discovery
     */
    void stop();

    /**
     * Check if service discovery is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();
}