package io.github.cuihairu.redis.streaming.registry.client.metrics;

import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        RScript script = mock(RScript.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(map.get("metrics")).thenReturn("{\"clientInflight\":\"abc\",\"clientErrorRate\":\"xyz\"}");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, new ServiceConsumerConfig());
        reporter.incrementInflight("svc", "i");
        reporter.recordOutcome("svc", "i", true);
        reporter.recordLatency("svc", "i", 5);

        verifyMergeScriptInvoked(script, atLeastOnce());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAndNumericValuesAreParsed() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        RScript script = mock(RScript.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(map.get("metrics")).thenReturn(null).thenReturn("{\"clientInflight\":2,\"clientErrorRate\":10.0}");

        RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(redisson, new ServiceConsumerConfig());
        reporter.incrementInflight("svc", "i");
        reporter.decrementInflight("svc", "i");
        reporter.recordOutcome("svc", "i", false);
        verifyMergeScriptInvoked(script, atLeastOnce());
    }

    private static void verifyMergeScriptInvoked(RScript script, org.mockito.verification.VerificationMode mode) {
        // metrics are written through the server-side merge script (not a plain HSET)
        verify(script, mode).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.LONG), anyList(), eq("metrics"), anyString());
    }
}
