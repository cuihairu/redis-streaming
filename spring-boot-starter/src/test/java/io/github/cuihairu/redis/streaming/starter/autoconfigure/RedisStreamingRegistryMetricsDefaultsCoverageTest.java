package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsGlobal;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers the null/empty outcomes of the provider-metrics property wiring in
 * {@code RedisStreamingRegistryAutoConfiguration#namingService(...)}: unset optional values must
 * keep the {@link MetricsConfig} defaults.
 */
class RedisStreamingRegistryMetricsDefaultsCoverageTest {

    private static MetricsConfig installAndGet(RedisStreamingProperties props) {
        MetricsGlobal.setDefaultConfig(null);
        RedissonClient redisson = mock(RedissonClient.class);
        NamingService namingService =
                new RedisStreamingRegistryAutoConfiguration().namingService(redisson, props);
        try {
            assertTrue(namingService.isRunning());
            return MetricsGlobal.getOrDefault();
        } finally {
            try {
                namingService.stop();
            } finally {
                MetricsGlobal.setDefaultConfig(null);
            }
        }
    }

    @Test
    void nullOptionalMetricsPropertiesKeepDefaults() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getMetrics().setEnabled(null);
        props.getRegistry().getMetrics().setIntervals(null);
        props.getRegistry().getMetrics().setDefaultInterval(null);
        props.getRegistry().getMetrics().setTimeout(null);

        MetricsConfig mc = installAndGet(props);

        assertEquals(Set.of("memory", "cpu", "application"), mc.getEnabledMetrics());
        assertEquals(Duration.ofSeconds(30), mc.getCollectionIntervals().get("memory"));
        assertEquals(Duration.ofMinutes(1), mc.getDefaultCollectionInterval());
        assertEquals(Duration.ofSeconds(5), mc.getCollectionTimeout());
    }

    @Test
    void emptyCollectionsAlsoKeepDefaults() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getMetrics().setEnabled(Set.of());
        props.getRegistry().getMetrics().setIntervals(Map.of());

        MetricsConfig mc = installAndGet(props);

        assertEquals(Set.of("memory", "cpu", "application"), mc.getEnabledMetrics());
        assertTrue(mc.getCollectionIntervals().containsKey("cpu"));
    }

    @Test
    void providedOptionalMetricsPropertiesAreApplied() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getMetrics().setEnabled(Set.of("disk"));
        props.getRegistry().getMetrics().setIntervals(Map.of("disk", Duration.ofSeconds(9)));
        props.getRegistry().getMetrics().setDefaultInterval(Duration.ofSeconds(11));
        props.getRegistry().getMetrics().setTimeout(Duration.ofMillis(77));

        MetricsConfig mc = installAndGet(props);

        assertEquals(Set.of("disk"), mc.getEnabledMetrics());
        assertEquals(Duration.ofSeconds(9), mc.getCollectionIntervals().get("disk"));
        assertEquals(Duration.ofSeconds(11), mc.getDefaultCollectionInterval());
        assertEquals(Duration.ofMillis(77), mc.getCollectionTimeout());
    }
}
