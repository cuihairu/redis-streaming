package io.github.cuihairu.redis.streaming.registry.client.metrics;

import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisClientMetricsReporter
 */
class RedisClientMetricsReporterTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private RMap mockMap;

    private ServiceConsumerConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new ServiceConsumerConfig();
        config.setKeyPrefix("registry");
        
        // Setup mock behavior
        when(mockRedissonClient.getMap(any(String.class), any(StringCodec.class))).thenReturn(mockMap);
    }

    @Test
    void testConstructorWithDefaultAlpha() {
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);

        assertNotNull(reporter);
    }

    @Test
    void testConstructorWithCustomAlpha() {
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config, 0.5);

        assertNotNull(reporter);
    }

    @Test
    void testConstructorWithAlphaBelowZero() {
        // Alpha should be clamped to 0.0
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config, -0.5);

        assertNotNull(reporter);
    }

    @Test
    void testConstructorWithAlphaAboveOne() {
        // Alpha should be clamped to 1.0
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config, 1.5);

        assertNotNull(reporter);
    }

    @Test
    void testConstructorWithZeroAlpha() {
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config, 0.0);

        assertNotNull(reporter);
    }

    @Test
    void testConstructorWithOneAlpha() {
        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config, 1.0);

        assertNotNull(reporter);
    }

    @Test
    void testIncrementInflight() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.incrementInflight("test-service", "instance-1");

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testDecrementInflight() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.decrementInflight("test-service", "instance-1");

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testRecordLatency() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 100);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testRecordLatencyWithZero() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 0);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testRecordLatencyWithLargeValue() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 100000);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testRecordOutcomeWithSuccess() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordOutcome("test-service", "instance-1", true);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testRecordOutcomeWithFailure() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordOutcome("test-service", "instance-1", false);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testDecrementBelowZero() {
        // Start with zero inflight
        String existingMetrics = "{\"clientInflight\":0}";
        when(mockMap.get("metrics")).thenReturn(existingMetrics);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        // Decrementing should not go below zero
        reporter.decrementInflight("test-service", "instance-1");
        
        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testWithExistingMetrics() {
        String existingMetrics = "{\"clientInflight\":2,\"clientLatencyMs\":50,\"clientErrorRate\":10.5}";
        when(mockMap.get("metrics")).thenReturn(existingMetrics);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.incrementInflight("test-service", "instance-1");

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testWithEmptyMetrics() {
        when(mockMap.get("metrics")).thenReturn("");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 75);

        verify(mockMap).put(eq("metrics"), any(String.class));
    }

    @Test
    void testWithInvalidMetricsJson() {
        when(mockMap.get("metrics")).thenReturn("invalid-json");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        // Should not throw exception
        assertDoesNotThrow(() -> reporter.recordLatency("test-service", "instance-1", 50));
    }

    @Test
    void testMultipleOperations() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        reporter.incrementInflight("test-service", "instance-1");
        reporter.recordLatency("test-service", "instance-1", 100);
        reporter.recordOutcome("test-service", "instance-1", true);
        reporter.decrementInflight("test-service", "instance-1");

        verify(mockMap, times(4)).put(eq("metrics"), any(String.class));
    }

    @Test
    void testWithDifferentServices() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        reporter.recordLatency("service-a", "instance-1", 50);
        reporter.recordLatency("service-b", "instance-1", 75);
        reporter.recordLatency("service-c", "instance-1", 100);

        verify(mockMap, times(3)).put(eq("metrics"), any(String.class));
    }

    @Test
    void testWithDifferentInstances() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        reporter.recordLatency("test-service", "instance-1", 50);
        reporter.recordLatency("test-service", "instance-2", 75);
        reporter.recordLatency("test-service", "instance-3", 100);

        verify(mockMap, times(3)).put(eq("metrics"), any(String.class));
    }
}
