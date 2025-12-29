package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.NamingServiceConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisNamingService
 */
class RedisNamingServiceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private RedisNamingService createNamingService() {
        return new RedisNamingService(mockRedissonClient);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        RedisNamingService service = new RedisNamingService(mockRedissonClient);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithConfig() {
        NamingServiceConfig config = new NamingServiceConfig();
        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithNullConfig() {
        RedisNamingService service = new RedisNamingService(mockRedissonClient, null);

        assertNotNull(service);
        // Should use default config
    }

    @Test
    void testStart() {
        RedisNamingService service = createNamingService();

        assertFalse(service.isRunning());
        service.start();
        assertTrue(service.isRunning());
    }

    @Test
    void testStop() {
        RedisNamingService service = createNamingService();
        service.start();

        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testStartIsIdempotent() {
        RedisNamingService service = createNamingService();

        service.start();
        assertTrue(service.isRunning());

        service.start(); // Should not cause issues
        assertTrue(service.isRunning());
    }

    @Test
    void testStopIsIdempotent() {
        RedisNamingService service = createNamingService();
        service.start();

        service.stop();
        assertFalse(service.isRunning());

        service.stop(); // Should not cause issues
        assertFalse(service.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        RedisNamingService service = createNamingService();

        assertDoesNotThrow(() -> service.stop());
        assertFalse(service.isRunning());
    }

    @Test
    void testRegisterWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertThrows(IllegalStateException.class, () -> service.register(instance));
    }

    @Test
    void testDeregisterWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertThrows(IllegalStateException.class, () -> service.deregister(instance));
    }

    @Test
    void testGetAllInstancesWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () -> service.getAllInstances("test-service"));
    }

    @Test
    void testDiscoverWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () -> service.discover("test-service"));
    }

    @Test
    void testGetHealthyInstancesWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () -> service.getHealthyInstances("test-service"));
    }

    @Test
    void testGetInstancesWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () -> service.getInstances("test-service", true));
    }

    @Test
    void testGetInstancesByMetadataWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () ->
                service.getInstancesByMetadata("test-service", java.util.Map.of())
        );
    }

    @Test
    void testGetHealthyInstancesByMetadataWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () ->
                service.getHealthyInstancesByMetadata("test-service", java.util.Map.of())
        );
    }

    @Test
    void testSubscribeWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () ->
                service.subscribe("test-service", (serviceName, action, instance, allInstances) -> {})
        );
    }

    @Test
    void testGetInstancesByFiltersWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () ->
                service.getInstancesByFilters("test-service", java.util.Map.of(), java.util.Map.of())
        );
    }

    @Test
    void testGetHealthyInstancesByFiltersWhenNotRunningThrowsException() {
        RedisNamingService service = createNamingService();

        assertThrows(IllegalStateException.class, () ->
                service.getHealthyInstancesByFilters("test-service", java.util.Map.of(), java.util.Map.of())
        );
    }

    @Test
    void testSendHeartbeatWhenNotRunningSilentlySucceeds() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Send heartbeat should silently succeed when not running
        assertDoesNotThrow(() -> service.sendHeartbeat(instance));
    }

    @Test
    void testBatchSendHeartbeatsWhenNotRunningSilentlySucceeds() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Batch send heartbeat should silently succeed when not running
        assertDoesNotThrow(() -> service.batchSendHeartbeats(java.util.List.of(instance)));
    }

    @Test
    void testBatchSendHeartbeatsWithEmptyList() {
        RedisNamingService service = createNamingService();
        service.start();

        assertDoesNotThrow(() -> service.batchSendHeartbeats(java.util.List.of()));
    }

    @Test
    void testUnsubscribeWhenNotRunningSilentlySucceeds() {
        RedisNamingService service = createNamingService();

        io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {};

        // Unsubscribe should silently succeed when not running
        assertDoesNotThrow(() -> service.unsubscribe("test-service", listener));
    }

    @Test
    void testGetConfig() {
        NamingServiceConfig config = new NamingServiceConfig();
        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertNotNull(service.getConfig());
    }

    @Test
    void testDiscoverDelegatesToGetAllInstances() {
        // Since discover delegates to getAllInstances, both should return the same result
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> allResult = service.getAllInstances("test-service");
        List<ServiceInstance> discoverResult = service.discover("test-service");

        // Both should return empty lists (no instances registered)
        assertNotNull(allResult);
        assertNotNull(discoverResult);
        assertEquals(allResult.size(), discoverResult.size());
    }

    @Test
    void testDiscoverHealthyDelegatesToGetHealthyInstances() {
        // Since discoverHealthy delegates to getHealthyInstances
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> getResult = service.getHealthyInstances("test-service");
        List<ServiceInstance> discoverResult = service.discoverHealthy("test-service");

        // Both should return empty lists (no instances registered)
        assertNotNull(getResult);
        assertNotNull(discoverResult);
        assertEquals(getResult.size(), discoverResult.size());
    }

    @Test
    void testHeartbeatDelegatesToSendHeartbeat() {
        RedisNamingService service = createNamingService();
        service.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertDoesNotThrow(() -> service.heartbeat(instance));
    }

    @Test
    void testBatchHeartbeatDelegatesToBatchSendHeartbeats() {
        RedisNamingService service = createNamingService();
        service.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertDoesNotThrow(() -> service.batchHeartbeat(java.util.List.of(instance)));
    }

    @Test
    void testLifecycleTransitions() {
        RedisNamingService service = createNamingService();

        // Initial state
        assertFalse(service.isRunning());

        // Start
        service.start();
        assertTrue(service.isRunning());

        // Stop
        service.stop();
        assertFalse(service.isRunning());

        // Restart
        service.start();
        assertTrue(service.isRunning());

        // Final stop
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testConfigWithCustomKeyPrefix() {
        NamingServiceConfig config = new NamingServiceConfig();
        config.setKeyPrefix("custom-registry");

        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertEquals("custom-registry", service.getConfig().getKeyPrefix());
    }

    @Test
    void testConfigWithHealthCheckInterval() {
        NamingServiceConfig config = new NamingServiceConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckInterval(15);

        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertTrue(service.getConfig().isEnableHealthCheck());
        assertEquals(15, service.getConfig().getHealthCheckInterval());
    }

    @Test
    void testConfigReturnsSameInstance() {
        NamingServiceConfig config = new NamingServiceConfig();
        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertSame(config, service.getConfig());
    }

    @Test
    void testDefaultConfigValues() {
        RedisNamingService service = createNamingService();

        assertNotNull(service.getConfig());
        assertNotNull(service.getConfig().getKeyPrefix());
    }

    @Test
    void testGetInstancesWithHealthyFalse() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getInstances("test-service", false);

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testGetInstancesWithHealthyTrue() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getInstances("test-service", true);

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testMultipleStartStopCycles() {
        RedisNamingService service = createNamingService();

        // First cycle
        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());

        // Second cycle
        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());

        // Third cycle
        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testSendHeartbeatWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertDoesNotThrow(() -> service.sendHeartbeat(instance));

        service.stop();
    }

    @Test
    void testBatchSendHeartbeatsWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("localhost")
                .port(8081)
                .build();

        assertDoesNotThrow(() -> service.batchSendHeartbeats(java.util.List.of(instance1, instance2)));

        service.stop();
    }

    @Test
    void testBatchSendHeartbeatsWithNullList() {
        RedisNamingService service = createNamingService();
        service.start();

        // Passing null may throw NullPointerException in implementation
        // This is expected behavior
        assertThrows(java.lang.NullPointerException.class, () -> service.batchSendHeartbeats(null));

        service.stop();
    }

    @Test
    void testHeartbeatWhenNotRunning() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertDoesNotThrow(() -> service.heartbeat(instance));
    }

    @Test
    void testBatchHeartbeatWhenNotRunning() {
        RedisNamingService service = createNamingService();

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        assertDoesNotThrow(() -> service.batchHeartbeat(java.util.List.of(instance)));
    }

    @Test
    void testDiscoverByMetadataWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getInstancesByMetadata("test-service", java.util.Map.of("env", "test"));

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testGetHealthyInstancesByMetadataWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getHealthyInstancesByMetadata("test-service", java.util.Map.of("env", "test"));

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testGetInstancesByFiltersWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getInstancesByFilters("test-service", java.util.Map.of("env", "test"), java.util.Map.of());

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testGetHealthyInstancesByFiltersWhenRunning() {
        RedisNamingService service = createNamingService();
        service.start();

        List<ServiceInstance> result = service.getHealthyInstancesByFilters("test-service", java.util.Map.of(), java.util.Map.of());

        assertNotNull(result);
        assertTrue(result.isEmpty()); // No instances registered

        service.stop();
    }

    @Test
    void testGetRegistryKeys() {
        NamingServiceConfig config = new NamingServiceConfig();
        config.setKeyPrefix("test-registry");

        RedisNamingService service = new RedisNamingService(mockRedissonClient, config);

        assertNotNull(service.getConfig().getRegistryKeys());
        assertEquals("test-registry", service.getConfig().getRegistryKeys().getKeyPrefix());
    }
}
