package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * Naming service interface
 * Designed with reference to Nacos NamingService, provides unified service registration and discovery capabilities
 *
 * <p>Extends the two business-role interfaces:</p>
 * <ul>
 *   <li>{@link ServiceProvider} — register/deregister/heartbeat</li>
 *   <li>{@link ServiceConsumer} — discovery/subscription/filtering</li>
 * </ul>
 *
 * <p>The former technical-perspective siblings ({@code ServiceRegistry}, {@code ServiceDiscovery})
 * were removed; their vocabulary survives as default aliases below so existing call sites keep
 * compiling unchanged.</p>
 */
public interface NamingService extends ServiceProvider, ServiceConsumer {

    // ==================== Legacy ServiceRegistry vocabulary (aliases) ====================

    /**
     * Alias for {@link #sendHeartbeat(ServiceInstance)} (former ServiceRegistry vocabulary)
     *
     * @param instance the service instance to send heartbeat for
     */
    default void heartbeat(ServiceInstance instance) {
        sendHeartbeat(instance);
    }

    /**
     * Alias for {@link #batchSendHeartbeats(List)} (former ServiceRegistry vocabulary)
     *
     * @param instances the list of service instances to send heartbeats for
     */
    default void batchHeartbeat(List<ServiceInstance> instances) {
        batchSendHeartbeats(instances);
    }

    // ==================== Legacy ServiceDiscovery vocabulary (aliases) ====================

    /**
     * Alias for {@link #getAllInstances(String)} (former ServiceDiscovery vocabulary)
     *
     * @param serviceName the service name
     * @return list of service instances
     */
    default List<ServiceInstance> discover(String serviceName) {
        return getAllInstances(serviceName);
    }

    /**
     * Alias for {@link #getHealthyInstances(String)} (former ServiceDiscovery vocabulary)
     *
     * @param serviceName the service name
     * @return list of healthy service instances
     */
    default List<ServiceInstance> discoverHealthy(String serviceName) {
        return getHealthyInstances(serviceName);
    }

    /**
     * Alias for {@link #getInstancesByMetadata(String, Map)} (former ServiceDiscovery vocabulary)
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching service instances
     */
    default List<ServiceInstance> discoverByMetadata(String serviceName, Map<String, String> metadataFilters) {
        return getInstancesByMetadata(serviceName, metadataFilters);
    }

    /**
     * Alias for {@link #getHealthyInstancesByMetadata(String, Map)} (former ServiceDiscovery vocabulary)
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    default List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters) {
        return getHealthyInstancesByMetadata(serviceName, metadataFilters);
    }

    // ==================== NamingService members ====================

    /**
     * Get healthy instances of a service for load balancing
     * This is an alias for getInstances(serviceName, true)
     *
     * @param serviceName the name of the service to discover
     * @return list of healthy service instances
     * @throws IllegalStateException if the service is not running
     */
    default List<ServiceInstance> getHealthyInstances(String serviceName) {
        return getInstances(serviceName, true);
    }

    /**
     * Get service instances filtered by metadata (supports comparison operators)
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship), supports comparison operators:
     * <ul>
     *   <li>"field": "value" - equals (default)</li>
     *   <li>"field:==": "value" - equals (explicit)</li>
     *   <li>"field:!=": "value" - not equals</li>
     *   <li>"field:&gt;": "value" - greater than</li>
     *   <li>"field:&gt;=": "value" - greater than or equals</li>
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
     * filters.put("weight:&gt;=", "10");      // weight &gt;= 10
     * filters.put("cpu_usage:&lt;", "80");    // CPU usage &lt; 80
     *
     * // Recommended: string equality
     * filters.put("region", "us-east-1");  // exact match
     * filters.put("status:!=", "down");    // status not equals
     * </pre>
     *
     * @return list of matching service instances
     */
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Get healthy service instances filtered by metadata
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Subscribe to service changes
     *
     * @param serviceName the name of the service to subscribe to
     * @param listener the listener to notify of changes
     */
    @Override
    void subscribe(String serviceName, ServiceChangeListener listener);
}
