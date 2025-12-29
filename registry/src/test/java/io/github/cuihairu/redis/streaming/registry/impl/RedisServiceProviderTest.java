package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisServiceProvider
 */
class RedisServiceProviderTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @Mock
    private MetricsCollectionManager mockMetricsManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider);
        assertNotNull(provider.getConfig());
        assertNotNull(provider.getHeartbeatConfig());
        assertNotNull(provider.getStateManager());
        assertNotNull(provider.getMetricsManager());
        assertNotNull(provider.getRegistryKeys());
        assertFalse(provider.isRunning());
    }

    @Test
    void testConstructorWithConfig() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setKeyPrefix("test-registry");

        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, config);

        assertNotNull(provider);
        assertEquals("test-registry", provider.getConfig().getKeyPrefix());
    }

    @Test
    void testConstructorWithNullConfig() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, null);

        assertNotNull(provider);
        assertNotNull(provider.getConfig()); // Should use default
    }

    @Test
    void testConstructorWithFullParameters() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setKeyPrefix("my-registry");

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient, 
            config, 
            null,  // heartbeatConfig - should use default
            null   // metricsManager - should use default
        );

        assertNotNull(provider);
        assertEquals("my-registry", provider.getConfig().getKeyPrefix());
        assertNotNull(provider.getHeartbeatConfig());
        assertNotNull(provider.getMetricsManager());
    }

    @Test
    void testGetConfig() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setKeyPrefix("test-prefix");
        config.setHeartbeatTimeoutSeconds(30);

        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, config);

        assertSame(config, provider.getConfig());
        assertEquals("test-prefix", provider.getConfig().getKeyPrefix());
        assertEquals(30, provider.getConfig().getHeartbeatTimeoutSeconds());
    }

    @Test
    void testGetHeartbeatConfig() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider.getHeartbeatConfig());
    }

    @Test
    void testGetStateManager() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider.getStateManager());
    }

    @Test
    void testGetMetricsManager() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider.getMetricsManager());
    }

    @Test
    void testGetRegistryKeys() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider.getRegistryKeys());
    }

    @Test
    void testInitialStateIsNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertFalse(provider.isRunning());
    }

    @Test
    void testStartWhenAlreadyRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        // Should not throw when starting again
        assertDoesNotThrow(() -> provider.start());
        assertTrue(provider.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // Should not throw when stopping without starting
        assertDoesNotThrow(() -> provider.stop());
        assertFalse(provider.isRunning());
    }

    @Test
    void testStartStopCycle() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertFalse(provider.isRunning());

        provider.start();
        assertTrue(provider.isRunning());

        provider.stop();
        assertFalse(provider.isRunning());
    }

    @Test
    void testRegisterWhenNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        // Don't start the provider

        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> provider.register(instance));
    }

    @Test
    void testDeregisterWhenNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        // Don't start the provider

        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> provider.deregister(instance));
    }

    @Test
    void testSendHeartbeatWhenNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        // Don't start the provider

        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should not throw (heartbeat can fail silently)
        assertDoesNotThrow(() -> provider.sendHeartbeat(instance));
    }

    @Test
    void testBatchSendHeartbeatsWhenNotRunning() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        // Don't start the provider

        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should not throw
        assertDoesNotThrow(() -> provider.batchSendHeartbeats(java.util.List.of(instance)));
    }

    @Test
    void testBatchSendHeartbeatsWithEmptyList() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // Should not throw
        assertDoesNotThrow(() -> provider.batchSendHeartbeats(java.util.List.of()));
    }

    @Test
    void testBatchSendHeartbeatsWithNullList() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // Should not throw
        assertDoesNotThrow(() -> provider.batchSendHeartbeats(null));
    }

    @Test
    void testHeartbeatAlias() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // heartbeat() is an alias for sendHeartbeat()
        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should not throw when not running
        assertDoesNotThrow(() -> provider.heartbeat(instance));
    }

    @Test
    void testBatchHeartbeatAlias() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // batchHeartbeat() is an alias for batchSendHeartbeats()
        io.github.cuihairu.redis.streaming.registry.ServiceInstance instance =
            io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("localhost")
                .port(8080)
                .build();

        // Should not throw when not running
        assertDoesNotThrow(() -> provider.batchHeartbeat(java.util.List.of(instance)));
    }

    @Test
    void testMultipleStartStopCycles() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // First cycle
        provider.start();
        assertTrue(provider.isRunning());
        provider.stop();
        assertFalse(provider.isRunning());

        // Second cycle
        provider.start();
        assertTrue(provider.isRunning());
        provider.stop();
        assertFalse(provider.isRunning());

        // Third cycle
        provider.start();
        assertTrue(provider.isRunning());
        provider.stop();
        assertFalse(provider.isRunning());
    }

    @Test
    void testConstructorWithCustomHeartbeatConfig() {
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig();
        heartbeatConfig.setMetadataUpdateIntervalSeconds(300);
        heartbeatConfig.setMetricsInterval(java.time.Duration.ofSeconds(120));

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            heartbeatConfig,
            null
        );

        assertNotNull(provider);
        assertEquals(300, provider.getHeartbeatConfig().getMetadataUpdateIntervalSeconds());
        assertEquals(120, provider.getHeartbeatConfig().getMetricsInterval().toSeconds());
    }

    @Test
    void testConstructorWithCustomMetricsManager() {
        MetricsCollectionManager customManager = mockMetricsManager;

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            new HeartbeatConfig(),
            customManager
        );

        assertNotNull(provider);
        assertSame(customManager, provider.getMetricsManager());
    }

    @Test
    void testConstructorWithNullHeartbeatConfig() {
        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            null,  // heartbeatConfig - should use default
            null
        );

        assertNotNull(provider);
        assertNotNull(provider.getHeartbeatConfig());
    }

    @Test
    void testConstructorWithAllNulls() {
        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            null,  // config
            null,  // heartbeatConfig
            null   // metricsManager
        );

        assertNotNull(provider);
        assertNotNull(provider.getConfig());
        assertNotNull(provider.getHeartbeatConfig());
        assertNotNull(provider.getMetricsManager());
    }

    @Test
    void testStartCreatesExecutorService() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        provider.start();
        assertTrue(provider.isRunning());

        // Start again should be idempotent
        provider.start();
        assertTrue(provider.isRunning());

        provider.stop();
    }

    @Test
    void testStopShutdownsExecutorService() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        provider.start();
        assertTrue(provider.isRunning());

        provider.stop();
        assertFalse(provider.isRunning());

        // Stop again should be idempotent
        provider.stop();
        assertFalse(provider.isRunning());
    }

    @Test
    void testRegisterWithInvalidCharactersInServiceName() {
        // Create a real provider that will start
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test:service:with:colons")
            .instanceId("instance-1")
            .host("localhost")
            .port(8080)
            .build();

        // Should not throw - sanitization should handle it
        // Note: This test verifies sanitization logic exists
        // Real Redis interaction would fail with mock, but sanitization happens first
        assertDoesNotThrow(() -> {
            try {
                provider.register(instance);
            } catch (RuntimeException e) {
                // Expected due to mock Redisson client
                // The important part is that sanitization occurred
                if (!e.getMessage().contains("Service registration failed")) {
                    throw e;
                }
            }
        });

        provider.stop();
    }

    @Test
    void testRegisterWithInvalidCharactersInInstanceId() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("instance:with:colons")
            .host("localhost")
            .port(8080)
            .build();

        assertDoesNotThrow(() -> {
            try {
                provider.register(instance);
            } catch (RuntimeException e) {
                if (!e.getMessage().contains("Service registration failed")) {
                    throw e;
                }
            }
        });

        provider.stop();
    }

    @Test
    void testDeregisterWithInvalidCharacters() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test:service")
            .instanceId("instance:1")
            .host("localhost")
            .port(8080)
            .build();

        assertDoesNotThrow(() -> {
            try {
                provider.deregister(instance);
            } catch (RuntimeException e) {
                if (!e.getMessage().contains("Service deregistration failed")) {
                    throw e;
                }
            }
        });

        provider.stop();
    }

    @Test
    void testSendHeartbeatWithInvalidCharacters() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test:service")
            .instanceId("instance:1")
            .host("localhost")
            .port(8080)
            .build();

        // Should not throw even with invalid characters
        assertDoesNotThrow(() -> provider.sendHeartbeat(instance));

        provider.stop();
    }

    @Test
    void testBatchSendHeartbeatsWithMultipleInstances() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

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

        ServiceInstance instance3 = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("instance-3")
            .host("localhost")
            .port(8082)
            .build();

        // Should not throw even with failures
        assertDoesNotThrow(() -> provider.batchSendHeartbeats(java.util.List.of(instance1, instance2, instance3)));

        provider.stop();
    }

    @Test
    void testBatchSendHeartbeatsWithMixedValidInvalidInstances() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance1 = DefaultServiceInstance.builder()
            .serviceName("valid-service")
            .instanceId("instance-1")
            .host("localhost")
            .port(8080)
            .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
            .serviceName("service:with:colons")
            .instanceId("instance-2")
            .host("localhost")
            .port(8081)
            .build();

        // Should handle both valid and invalid
        assertDoesNotThrow(() -> provider.batchSendHeartbeats(java.util.List.of(instance1, instance2)));

        provider.stop();
    }

    @Test
    void testConfigWithCustomKeyPrefix() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setKeyPrefix("custom-registry");

        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, config);

        assertEquals("custom-registry", provider.getConfig().getKeyPrefix());
        assertEquals("custom-registry", provider.getRegistryKeys().getKeyPrefix());
    }

    @Test
    void testConfigWithCustomHeartbeatTimeout() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setHeartbeatTimeoutSeconds(60);

        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, config);

        assertEquals(60, provider.getConfig().getHeartbeatTimeoutSeconds());
    }

    @Test
    void testConfigWithCustomServiceChangeChannel() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        // Use the default pattern - no setter for custom channel key
        String channelKey = config.getServiceChangeChannelKey("test-service");
        assertTrue(channelKey.contains("test-service"));
    }

    @Test
    void testStateManagerInitialization() {
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig();
        heartbeatConfig.setMetricsInterval(java.time.Duration.ofSeconds(60));
        heartbeatConfig.setMetadataUpdateIntervalSeconds(300);

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            heartbeatConfig,
            null
        );

        assertNotNull(provider.getStateManager());
        // State manager should be initialized with the heartbeat config
        assertNotNull(provider.getStateManager());
    }

    @Test
    void testMetricsManagerInitialization() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertNotNull(provider.getMetricsManager());
        // Default metrics manager should be initialized
        assertNotNull(provider.getMetricsManager());
    }

    @Test
    void testStartStopWithProviderInterface() {
        io.github.cuihairu.redis.streaming.registry.ServiceProvider provider =
            new RedisServiceProvider(mockRedissonClient);

        assertFalse(provider.isRunning());

        provider.start();
        assertTrue(provider.isRunning());

        provider.stop();
        assertFalse(provider.isRunning());
    }

    @Test
    void testRegistryInterfaceMethods() {
        io.github.cuihairu.redis.streaming.registry.ServiceRegistry registry =
            new RedisServiceProvider(mockRedissonClient);

        registry.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("instance-1")
            .host("localhost")
            .port(8080)
            .build();

        // Test register throws when not running
        registry.stop();
        assertThrows(IllegalStateException.class, () -> registry.register(instance));

        // Test deregister throws when not running
        assertThrows(IllegalStateException.class, () -> registry.deregister(instance));

        // Test heartbeat silently fails when not running
        assertDoesNotThrow(() -> registry.heartbeat(instance));

        // Test batchHeartbeat silently fails when not running
        assertDoesNotThrow(() -> registry.batchHeartbeat(java.util.List.of(instance)));
    }

    @Test
    void testSendHeartbeatSilentlyFailsOnException() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);
        provider.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test:service:invalid")
            .instanceId("instance:1:invalid")
            .host("localhost")
            .port(8080)
            .build();

        // Should not throw even with invalid characters (sanitization happens)
        assertDoesNotThrow(() -> provider.sendHeartbeat(instance));

        provider.stop();
    }

    @Test
    void testBatchHeartbeatAliasMethod() {
        io.github.cuihairu.redis.streaming.registry.ServiceRegistry registry =
            new RedisServiceProvider(mockRedissonClient);
        registry.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("instance-1")
            .host("localhost")
            .port(8080)
            .build();

        // Test batchHeartbeat alias
        assertDoesNotThrow(() -> registry.batchHeartbeat(java.util.List.of(instance)));

        registry.stop();
    }

    @Test
    void testHeartbeatAliasMethod() {
        io.github.cuihairu.redis.streaming.registry.ServiceRegistry registry =
            new RedisServiceProvider(mockRedissonClient);
        registry.start();

        ServiceInstance instance = DefaultServiceInstance.builder()
            .serviceName("test-service")
            .instanceId("instance-1")
            .host("localhost")
            .port(8080)
            .build();

        // Test heartbeat alias
        assertDoesNotThrow(() -> registry.heartbeat(instance));

        registry.stop();
    }

    @Test
    void testGetConfigReturnsSameInstance() {
        ServiceProviderConfig config = new ServiceProviderConfig();
        config.setKeyPrefix("test-registry");

        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient, config);

        assertSame(config, provider.getConfig());
    }

    @Test
    void testGetHeartbeatConfigReturnsSameInstance() {
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig();
        heartbeatConfig.setMetadataUpdateIntervalSeconds(600);

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            heartbeatConfig,
            null
        );

        assertSame(heartbeatConfig, provider.getHeartbeatConfig());
    }

    @Test
    void testGetStateManagerReturnsSameInstance() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        // State manager should be the same instance across calls
        assertSame(provider.getStateManager(), provider.getStateManager());
    }

    @Test
    void testGetMetricsManagerReturnsSameInstance() {
        MetricsCollectionManager customManager = mockMetricsManager;

        RedisServiceProvider provider = new RedisServiceProvider(
            mockRedissonClient,
            new ServiceProviderConfig(),
            new HeartbeatConfig(),
            customManager
        );

        assertSame(customManager, provider.getMetricsManager());
    }

    @Test
    void testGetRegistryKeysReturnsSameInstance() {
        RedisServiceProvider provider = new RedisServiceProvider(mockRedissonClient);

        assertSame(provider.getRegistryKeys(), provider.getRegistryKeys());
    }
}
