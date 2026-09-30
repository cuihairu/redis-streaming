package io.github.cuihairu.redis.streaming.registry.client.metrics;

import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private RScript mockScript;

    private ServiceConsumerConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new ServiceConsumerConfig();
        config.setKeyPrefix("registry");

        // Setup mock behavior
        when(mockRedissonClient.getMap(any(String.class), any(StringCodec.class))).thenReturn(mockMap);
        // metrics are written through the server-side merge script (not a plain HSET)
        when(mockRedissonClient.getScript(any(Codec.class))).thenReturn(mockScript);
    }

    private void verifyMetricsMerged() {
        verifyMetricsMerged(times(1));
    }

    private void verifyMetricsMerged(org.mockito.verification.VerificationMode mode) {
        verify(mockScript, mode).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.LONG), anyList(), eq("metrics"), anyString());
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

        verifyMetricsMerged();
    }

    @Test
    void testDecrementInflight() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.decrementInflight("test-service", "instance-1");

        verifyMetricsMerged();
    }

    @Test
    void testRecordLatency() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 100);

        verifyMetricsMerged();
    }

    @Test
    void testRecordLatencyWithZero() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 0);

        verifyMetricsMerged();
    }

    @Test
    void testRecordLatencyWithLargeValue() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 100000);

        verifyMetricsMerged();
    }

    @Test
    void testRecordOutcomeWithSuccess() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordOutcome("test-service", "instance-1", true);

        verifyMetricsMerged();
    }

    @Test
    void testRecordOutcomeWithFailure() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordOutcome("test-service", "instance-1", false);

        verifyMetricsMerged();
    }

    @Test
    void testDecrementBelowZero() {
        // Start with zero inflight
        String existingMetrics = "{\"clientInflight\":0}";
        when(mockMap.get("metrics")).thenReturn(existingMetrics);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        // Decrementing should not go below zero
        reporter.decrementInflight("test-service", "instance-1");
        
        verifyMetricsMerged();
    }

    @Test
    void testWithExistingMetrics() {
        String existingMetrics = "{\"clientInflight\":2,\"clientLatencyMs\":50,\"clientErrorRate\":10.5}";
        when(mockMap.get("metrics")).thenReturn(existingMetrics);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.incrementInflight("test-service", "instance-1");

        verifyMetricsMerged();
    }

    @Test
    void testWithEmptyMetrics() {
        when(mockMap.get("metrics")).thenReturn("");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        reporter.recordLatency("test-service", "instance-1", 75);

        verifyMetricsMerged();
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

        verifyMetricsMerged(times(4));
    }

    @Test
    void testWithDifferentServices() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        reporter.recordLatency("service-a", "instance-1", 50);
        reporter.recordLatency("service-b", "instance-1", 75);
        reporter.recordLatency("service-c", "instance-1", 100);

        verifyMetricsMerged(times(3));
    }

    @Test
    void testWithDifferentInstances() {
        when(mockMap.get("metrics")).thenReturn(null);

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(mockRedissonClient, config);
        
        reporter.recordLatency("test-service", "instance-1", 50);
        reporter.recordLatency("test-service", "instance-2", 75);
        reporter.recordLatency("test-service", "instance-3", 100);

        verifyMetricsMerged(times(3));
    }
}
