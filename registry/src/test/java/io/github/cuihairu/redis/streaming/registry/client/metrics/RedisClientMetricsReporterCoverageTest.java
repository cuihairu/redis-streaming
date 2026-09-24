package io.github.cuihairu.redis.streaming.registry.client.metrics;

import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers RedisClientMetricsReporter toLong/toDouble numeric parsing fallbacks by
 * feeding non-numeric metric values through the public mutate entry points.
 */
class RedisClientMetricsReporterCoverageTest {

    @Test
    @SuppressWarnings("unchecked")
    void nonNumericMetricValuesFallBackToDefaults() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(map.get("metrics")).thenReturn("{\"clientInflight\":\"abc\",\"clientErrorRate\":\"xyz\"}");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, new ServiceConsumerConfig());
        reporter.incrementInflight("svc", "i");
        reporter.recordOutcome("svc", "i", true);
        reporter.recordLatency("svc", "i", 5);

        verify(map, atLeastOnce()).put(eq("metrics"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAndNumericValuesAreParsed() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(map.get("metrics")).thenReturn(null).thenReturn("{\"clientInflight\":2,\"clientErrorRate\":10.0}");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, new ServiceConsumerConfig());
        reporter.incrementInflight("svc", "i");
        reporter.decrementInflight("svc", "i");
        reporter.recordOutcome("svc", "i", false);
        verify(map, atLeastOnce()).put(eq("metrics"), anyString());
    }
}
