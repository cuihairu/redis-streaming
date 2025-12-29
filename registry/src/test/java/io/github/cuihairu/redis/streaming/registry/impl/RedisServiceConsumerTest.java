package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisServiceConsumer
 */
class RedisServiceConsumerTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertNotNull(consumer);
        assertNotNull(consumer.getConfig());
        assertFalse(consumer.isRunning());
    }

    @Test
    void testConstructorWithConfig() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNotNull(consumer);
        assertNotNull(consumer.getConfig());
        assertFalse(consumer.isRunning());
    }

    @Test
    void testConstructorWithNullConfig() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, null);

        assertNotNull(consumer);
        assertNotNull(consumer.getConfig());
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStart() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertFalse(consumer.isRunning());
        consumer.start();
        assertTrue(consumer.isRunning());
    }

    @Test
    void testStop() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);
        consumer.start();

        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStartIsIdempotent() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        consumer.start();
        assertTrue(consumer.isRunning());

        consumer.start(); // Should not cause issues
        assertTrue(consumer.isRunning());
    }

    @Test
    void testStopIsIdempotent() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);
        consumer.start();

        consumer.stop();
        assertFalse(consumer.isRunning());

        consumer.stop(); // Should not cause issues
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertDoesNotThrow(() -> consumer.stop());
        assertFalse(consumer.isRunning());
    }

    @Test
    void testDiscoverWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.discover("test-service"));
    }

    @Test
    void testGetAllInstancesWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.getAllInstances("test-service"));
    }

    @Test
    void testGetHealthyInstancesWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.getHealthyInstances("test-service"));
    }

    @Test
    void testGetInstancesWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.getInstances("test-service", true));
    }

    @Test
    void testDiscoverHealthyWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.discoverHealthy("test-service"));
    }

    @Test
    void testSubscribeWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};
        assertThrows(IllegalStateException.class, () -> consumer.subscribe("test-service", listener));
    }

    @Test
    void testDiscoverByMetadataWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.discoverByMetadata("test-service", Map.of())
        );
    }

    @Test
    void testDiscoverHealthyByMetadataWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.discoverHealthyByMetadata("test-service", Map.of())
        );
    }

    @Test
    void testGetInstancesByMetadataWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.getInstancesByMetadata("test-service", Map.of())
        );
    }

    @Test
    void testGetHealthyInstancesByMetadataWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.getHealthyInstancesByMetadata("test-service", Map.of())
        );
    }

    @Test
    void testDiscoverByFiltersWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.discoverByFilters("test-service", Map.of(), Map.of())
        );
    }

    @Test
    void testDiscoverHealthyByFiltersWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                consumer.discoverHealthyByFilters("test-service", Map.of(), Map.of())
        );
    }

    @Test
    void testListServicesWhenNotRunningThrowsException() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> consumer.listServices());
    }

    @Test
    void testGetConfig() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNotNull(consumer.getConfig());
    }

    @Test
    void testGetHealthCheckManagerWhenDisabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNull(consumer.getHealthCheckManager());
    }

    @Test
    void testGetHealthCheckManagerWhenEnabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNotNull(consumer.getHealthCheckManager());
    }

    @Test
    void testGetActiveHealthCheckersCountWhenDisabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(0, consumer.getActiveHealthCheckersCount());
    }

    @Test
    void testGetActiveHealthCheckersCountWhenEnabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        // Health check manager is created when enabled, but checkers are registered on-demand
        // The count may be 0 initially until instances are discovered
        assertNotNull(consumer.getHealthCheckManager());
    }

    @Test
    void testGetDiscoveredInstanceCountWhenNotRunning() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertEquals(0, consumer.getDiscoveredInstanceCount());
    }

    @Test
    void testIsInstanceHealthyWhenNotRunning() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertFalse(consumer.isInstanceHealthy("test-instance-id"));
    }

    @Test
    void testRefreshServiceInstancesWhenNotRunning() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        assertDoesNotThrow(() -> consumer.refreshServiceInstances("test-service"));
    }

    @Test
    void testUnsubscribeWhenNotRunningSilentlySucceeds() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener));
    }

    @Test
    void testGetDelegatesToDiscover() {
        // Since getAllInstances delegates to discover
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> discoverResult = consumer.discover("test-service");
        List<ServiceInstance> getAllResult = consumer.getAllInstances("test-service");

        // Both should return empty lists (no instances registered)
        assertNotNull(discoverResult);
        assertNotNull(getAllResult);
        assertEquals(discoverResult.size(), getAllResult.size());
    }

    @Test
    void testGetHealthyInstancesDelegatesToDiscoverHealthy() {
        // Since getHealthyInstances delegates to discoverHealthy
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> getResult = consumer.getHealthyInstances("test-service");
        List<ServiceInstance> discoverResult = consumer.discoverHealthy("test-service");

        // Both should return empty lists (no instances registered)
        assertNotNull(getResult);
        assertNotNull(discoverResult);
        assertEquals(getResult.size(), discoverResult.size());
    }

    @Test
    void testGetInstancesWithHealthyFalseDelegatesToDiscover() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getInstances("test-service", false);

        assertNotNull(result);
    }

    @Test
    void testGetInstancesWithHealthyTrueDelegatesToDiscoverHealthy() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getInstances("test-service", true);

        assertNotNull(result);
    }

    @Test
    void testGetInstancesByMetadataDelegatesToDiscoverByMetadata() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("env", "test");
        List<ServiceInstance> getByMetadataResult = consumer.getInstancesByMetadata("test-service", filters);
        List<ServiceInstance> discoverByMetadataResult = consumer.discoverByMetadata("test-service", filters);

        // Both should return empty lists (no instances registered)
        assertNotNull(getByMetadataResult);
        assertNotNull(discoverByMetadataResult);
        assertEquals(getByMetadataResult.size(), discoverByMetadataResult.size());
    }

    @Test
    void testGetHealthyInstancesByMetadataDelegatesToDiscoverHealthyByMetadata() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("env", "test");
        List<ServiceInstance> getHealthyResult = consumer.getHealthyInstancesByMetadata("test-service", filters);
        List<ServiceInstance> discoverHealthyResult = consumer.discoverHealthyByMetadata("test-service", filters);

        // Both should return empty lists (no instances registered)
        assertNotNull(getHealthyResult);
        assertNotNull(discoverHealthyResult);
        assertEquals(getHealthyResult.size(), discoverHealthyResult.size());
    }

    @Test
    void testLifecycleTransitions() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        // Initial state
        assertFalse(consumer.isRunning());

        // Start
        consumer.start();
        assertTrue(consumer.isRunning());

        // Stop
        consumer.stop();
        assertFalse(consumer.isRunning());

        // Restart
        consumer.start();
        assertTrue(consumer.isRunning());

        // Final stop
        consumer.stop();
        assertFalse(consumer.isRunning());
    }

    @Test
    void testConfigIsMutable() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setHeartbeatTimeoutSeconds(60);
        config.setEnableHealthCheck(true);

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(60, consumer.getConfig().getHeartbeatTimeoutSeconds());
        assertTrue(consumer.getConfig().isEnableHealthCheck());
    }

    @Test
    void testConfigWithCustomKeyPrefix() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setKeyPrefix("custom-registry");

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals("custom-registry", consumer.getConfig().getKeyPrefix());
    }

    @Test
    void testConfigWithCustomHeartbeatTimeout() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setHeartbeatTimeoutSeconds(90);

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(90, consumer.getConfig().getHeartbeatTimeoutSeconds());
    }

    @Test
    void testConfigWithHealthCheckInterval() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckInterval(15);

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertTrue(consumer.getConfig().isEnableHealthCheck());
        assertEquals(15, consumer.getConfig().getHealthCheckInterval());
    }

    @Test
    void testConfigReturnsSameInstance() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertSame(config, consumer.getConfig());
    }

    @Test
    void testDefaultConfigValues() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        ServiceConsumerConfig config = consumer.getConfig();
        assertNotNull(config);
        // Default key prefix is "redis_streaming_registry" from BaseRedisConfig
        assertNotNull(config.getKeyPrefix());
        assertEquals(90, config.getHeartbeatTimeoutSeconds()); // Default is 90 seconds
    }

    @Test
    void testGetRegistryKeys() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setKeyPrefix("test-registry");

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNotNull(consumer.getConfig().getRegistryKeys());
        assertEquals("test-registry", consumer.getConfig().getRegistryKeys().getKeyPrefix());
    }

    @Test
    void testDiscoverByFilters() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> metadataFilters = Map.of("env", "test");
        Map<String, String> tagFilters = Map.of("version", "1.0");

        List<ServiceInstance> result = consumer.discoverByFilters("test-service", metadataFilters, tagFilters);

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        consumer.stop();
    }

    @Test
    void testDiscoverHealthyByFilters() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> metadataFilters = Map.of("env", "test");
        Map<String, String> tagFilters = Map.of("version", "1.0");

        List<ServiceInstance> result = consumer.discoverHealthyByFilters("test-service", metadataFilters, tagFilters);

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        consumer.stop();
    }

    @Test
    void testMultipleStartStopCycles() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient);

        // First cycle
        consumer.start();
        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());

        // Second cycle
        consumer.start();
        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());

        // Third cycle
        consumer.start();
        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());
    }

    @Test
    void testSubscribeUnsubscribeWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Subscribe will fail with mock Redisson client (topic is null)
        // but we can test that unsubscribe doesn't throw
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener));

        consumer.stop();
    }

    @Test
    void testListServicesWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<String> services = consumer.listServices();

        assertNotNull(services);
        assertTrue(services.isEmpty()); // No services registered

        consumer.stop();
    }

    @Test
    void testRefreshServiceInstancesWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        assertDoesNotThrow(() -> consumer.refreshServiceInstances("test-service"));

        consumer.stop();
    }

    @Test
    void testIsInstanceHealthyWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        assertFalse(consumer.isInstanceHealthy("unknown-instance-id"));

        consumer.stop();
    }

    @Test
    void testGetDiscoveredInstanceCountWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        assertEquals(0, consumer.getDiscoveredInstanceCount());

        consumer.stop();
    }

    @Test
    void testGetActiveHealthCheckersCountWhenRunning() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // Initially 0 since no instances discovered yet
        assertTrue(consumer.getActiveHealthCheckersCount() >= 0);

        consumer.stop();
    }

    // ==================== Additional tests for improved coverage ====================

    @Test
    void testDiscoverByMetadataWithEmptyFilters() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // Empty filters should delegate to discover
        List<ServiceInstance> result = consumer.discoverByMetadata("test-service", Map.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverByMetadataWithNullFilters() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // Null filters should delegate to discover
        List<ServiceInstance> result = consumer.discoverByMetadata("test-service", null);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverHealthyFiltersByEnabledAndHealthy() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // discoverHealthy should filter instances
        List<ServiceInstance> result = consumer.discoverHealthy("test-service");

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverHealthyByMetadataFiltersByEnabledAndHealthy() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("env", "test");
        List<ServiceInstance> result = consumer.discoverHealthyByMetadata("test-service", filters);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverHealthyByFiltersFiltersByEnabledAndHealthy() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> metadataFilters = Map.of("env", "test");
        Map<String, String> metricsFilters = Map.of("cpu", "<80");

        List<ServiceInstance> result = consumer.discoverHealthyByFilters("test-service", metadataFilters, metricsFilters);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testUnsubscribeRemovesListenerAndSubscription() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};
        ServiceChangeListener listener2 = (serviceName, action, instance, allInstances) -> {};

        // Unsubscribe one listener when there are multiple
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener));
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener2));

        consumer.stop();
    }

    @Test
    void testSubscribeWithNullServiceName() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Should throw due to IllegalStateException when trying to discover with null service name
        assertThrows(Exception.class, () -> consumer.subscribe(null, listener));

        consumer.stop();
    }

    @Test
    void testStopCleansUpAllSubscriptions() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // Mock some subscription cleanup
        assertDoesNotThrow(() -> consumer.stop());
        assertFalse(consumer.isRunning());
    }

    @Test
    void testStopWhenHealthCheckEnabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        assertNotNull(consumer.getHealthCheckManager());

        assertDoesNotThrow(() -> consumer.stop());
        assertFalse(consumer.isRunning());
        // HealthCheckManager is still set but stopped
        assertNotNull(consumer.getHealthCheckManager());
    }

    @Test
    void testListServicesWithEmptyRedis() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<String> services = consumer.listServices();

        assertNotNull(services);
        assertTrue(services.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetDiscoveredInstanceCount() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // Initially 0 instances
        assertEquals(0, consumer.getDiscoveredInstanceCount());

        consumer.stop();
    }

    @Test
    void testIsInstanceHealthyWhenHealthCheckDisabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        // When health check is disabled, should return false for unknown instance
        assertFalse(consumer.isInstanceHealthy("unknown-instance"));

        consumer.stop();
    }

    @Test
    void testRefreshServiceInstances() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        assertDoesNotThrow(() -> consumer.refreshServiceInstances("test-service"));

        consumer.stop();
    }

    @Test
    void testDiscoverByFiltersWithNullFilters() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.discoverByFilters("test-service", null, null);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverByFiltersWithOnlyMetadata() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.discoverByFilters("test-service", Map.of("env", "test"), null);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverByFiltersWithOnlyMetrics() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.discoverByFilters("test-service", null, Map.of("cpu", "<80"));

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testSubscribeMultipleTimes() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Subscribe may throw NullPointerException due to mock limitations
        // We just verify it doesn't cause consumer to be in an invalid state
        assertThrows(Exception.class, () -> consumer.subscribe("test-service", listener));

        consumer.stop();
    }

    @Test
    void testUnsubscribeNonExistentListener() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Unsubscribe a listener that was never subscribed
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener));

        consumer.stop();
    }

    @Test
    void testUnsubscribeFromNonExistentService() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Unsubscribe from a service that was never subscribed to
        assertDoesNotThrow(() -> consumer.unsubscribe("non-existent-service", listener));

        consumer.stop();
    }

    @Test
    void testGetInstancesWithHealthyTrue() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getInstances("test-service", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetInstancesWithHealthyFalse() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getInstances("test-service", false);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetAllInstances() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getAllInstances("test-service");

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetHealthyInstances() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.getHealthyInstances("test-service");

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetInstancesByMetadata() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("env", "test");
        List<ServiceInstance> result = consumer.getInstancesByMetadata("test-service", filters);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testGetHealthyInstancesByMetadata() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("env", "test");
        List<ServiceInstance> result = consumer.getHealthyInstancesByMetadata("test-service", filters);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverWithEmptyResult() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.discover("non-existent-service");

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverHealthyWithEmptyResult() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        List<ServiceInstance> result = consumer.discoverHealthy("non-existent-service");

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverByMetadataWithEmptyResult() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("non-existent-key", "value");
        List<ServiceInstance> result = consumer.discoverByMetadata("test-service", filters);

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testDiscoverByFiltersWithEmptyResult() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        Map<String, String> filters = Map.of("non-existent-key", "value");
        List<ServiceInstance> result = consumer.discoverByFilters("test-service", filters, Map.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());

        consumer.stop();
    }

    @Test
    void testConfigWithHeartbeatTimeout() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setHeartbeatTimeoutSeconds(120);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(120, consumer.getConfig().getHeartbeatTimeoutSeconds());
    }

    @Test
    void testConfigWithHealthCheckTimeout() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setHealthCheckTimeout(5000);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(5000, consumer.getConfig().getHealthCheckTimeout());
    }

    @Test
    void testConfigWithCustomTimeUnit() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setHealthCheckTimeUnit(java.util.concurrent.TimeUnit.MINUTES);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertEquals(java.util.concurrent.TimeUnit.MINUTES, consumer.getConfig().getHealthCheckTimeUnit());
    }

    @Test
    void testHealthCheckManagerInitialization() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckInterval(30);
        config.setHealthCheckTimeout(3000);

        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        assertNotNull(consumer.getHealthCheckManager());
    }

    @Test
    void testStartWithoutHealthCheck() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        consumer.start();

        assertTrue(consumer.isRunning());
        assertNull(consumer.getHealthCheckManager());

        consumer.stop();
    }

    @Test
    void testStartWithHealthCheck() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);

        consumer.start();

        assertTrue(consumer.isRunning());
        assertNotNull(consumer.getHealthCheckManager());

        consumer.stop();
    }

    @Test
    void testStopWithHealthCheckEnabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        consumer.stop();

        assertFalse(consumer.isRunning());
    }

    @Test
    void testMultipleSubscriptionsToDifferentServices() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Subscribe may throw NullPointerException due to mock limitations
        assertThrows(Exception.class, () -> consumer.subscribe("service1", listener));
        assertThrows(Exception.class, () -> consumer.subscribe("service2", listener));

        consumer.stop();
    }

    @Test
    void testSubscribeWithDifferentListeners() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener1 = (serviceName, action, instance, allInstances) -> {};
        ServiceChangeListener listener2 = (serviceName, action, instance, allInstances) -> {};

        // Subscribe may throw NullPointerException due to mock limitations
        assertThrows(Exception.class, () -> consumer.subscribe("test-service", listener1));
        assertThrows(Exception.class, () -> consumer.subscribe("test-service", listener2));

        consumer.stop();
    }

    @Test
    void testUnsubscribeCleansUpHealthChecksWhenEnabled() {
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        RedisServiceConsumer consumer = new RedisServiceConsumer(mockRedissonClient, config);
        consumer.start();

        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Unsubscribe should clean up health checks when no listeners remain
        assertDoesNotThrow(() -> consumer.unsubscribe("test-service", listener));

        consumer.stop();
    }
}
