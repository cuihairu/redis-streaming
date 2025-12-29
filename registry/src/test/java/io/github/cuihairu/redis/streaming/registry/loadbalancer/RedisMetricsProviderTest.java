package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisMetricsProvider
 */
class RedisMetricsProviderTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    private RedisMetricsProvider provider;
    private io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
    }

    @Test
    void testConstructorWithConfig() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        assertNotNull(provider);
    }

    @Test
    void testConstructorWithConfigAndTtl() {
        provider = new RedisMetricsProvider(mockRedissonClient, config, 60000);

        assertNotNull(provider);
    }

    @Test
    void testGetMetricsWithValidParameters() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        assertNotNull(metrics);
        // With mock RedissonClient, metrics will be empty or contain default values
    }

    @Test
    void testGetMetricsWithNullServiceName() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        // Should handle null service name gracefully
        Map<String, Object> metrics = provider.getMetrics(null, "instance-1");

        assertNotNull(metrics);
    }

    @Test
    void testGetMetricsWithNullInstanceId() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        Map<String, Object> metrics = provider.getMetrics("test-service", null);

        assertNotNull(metrics);
    }

    @Test
    void testGetMetricsWithMultipleInstances() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        // First call
        Map<String, Object> metrics1 = provider.getMetrics("test-service", "instance-1");
        // Second call should potentially use cache
        Map<String, Object> metrics2 = provider.getMetrics("test-service", "instance-2");

        assertNotNull(metrics1);
        assertNotNull(metrics2);
    }

    @Test
    void testGetMetricsCaching() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        // First call
        Map<String, Object> metrics1 = provider.getMetrics("test-service", "instance-1");
        // Second call should potentially use cache
        Map<String, Object> metrics2 = provider.getMetrics("test-service", "instance-1");

        assertNotNull(metrics1);
        assertNotNull(metrics2);
    }

    @Test
    void testGetMetricsWithDifferentServices() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        Map<String, Object> metrics1 = provider.getMetrics("service-a", "instance-1");
        Map<String, Object> metrics2 = provider.getMetrics("service-b", "instance-1");

        assertNotNull(metrics1);
        assertNotNull(metrics2);
    }

    @Test
    void testGetMetricsWithEmptyServiceName() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        Map<String, Object> metrics = provider.getMetrics("", "instance-1");

        assertNotNull(metrics);
    }

    @Test
    void testGetMetricsWithEmptyInstanceId() {
        provider = new RedisMetricsProvider(mockRedissonClient, config);

        Map<String, Object> metrics = provider.getMetrics("test-service", "");

        assertNotNull(metrics);
    }
}
