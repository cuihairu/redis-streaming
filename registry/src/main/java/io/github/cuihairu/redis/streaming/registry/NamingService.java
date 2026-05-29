package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * Naming service interface
 * Designed with reference to Nacos NamingService, provides unified service registration and discovery capabilities
 *
 * Core concepts:
 * - Service Provider: registers service instances, sends heartbeats to maintain health status
 * - Service Consumer: queries service instances, subscribes to service changes
 *
 * <p>Two perspectives of interface design:</p>
 * <pre>
 * Business role perspective:         Technical operation perspective:
 * ServiceProvider              <->   ServiceRegistry
 * ServiceConsumer              <->   ServiceDiscovery
 *
 * NamingService is a unified interface that inherits both perspectives
 * </pre>
 *
 * This interface extends both ServiceProvider and ServiceConsumer (business role perspective),
 * as well as ServiceRegistry and ServiceDiscovery (technical operation perspective)
 * to provide a unified interface while maintaining clear separation of concerns.
 */
public interface NamingService extends ServiceProvider, ServiceConsumer, ServiceRegistry, ServiceDiscovery {
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
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * Get healthy service instances filtered by metadata
     *
     * @param serviceName the service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
}
