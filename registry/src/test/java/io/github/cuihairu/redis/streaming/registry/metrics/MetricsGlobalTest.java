package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsGlobal
 */
class MetricsGlobalTest {

    private MetricsConfig originalConfig;

    @BeforeEach
    void setUp() {
        // Save the original config to restore after tests
        originalConfig = MetricsGlobal.getOrDefault();
        // Reset to null for clean testing
        MetricsGlobal.setDefaultConfig(null);
    }

    @AfterEach
    void tearDown() {
        // Restore the original config
        MetricsGlobal.setDefaultConfig(originalConfig);
    }

    @Test
    void testGetOrDefaultWhenNull() {
        MetricsGlobal.setDefaultConfig(null);

        MetricsConfig config = MetricsGlobal.getOrDefault();

        assertNotNull(config);
        // Should return a new default MetricsConfig
    }

    @Test
    void testGetOrDefaultWhenSet() {
        MetricsConfig customConfig = new MetricsConfig();
        customConfig.setDefaultCollectionInterval(Duration.ofMillis(100));
        MetricsGlobal.setDefaultConfig(customConfig);

        MetricsConfig config = MetricsGlobal.getOrDefault();

        assertSame(customConfig, config);
        assertEquals(Duration.ofMillis(100), config.getDefaultCollectionInterval());
    }

    @Test
    void testSetDefaultConfig() {
        MetricsConfig customConfig = new MetricsConfig();

        assertDoesNotThrow(() -> MetricsGlobal.setDefaultConfig(customConfig));
    }

    @Test
    void testSetDefaultConfigToNull() {
        MetricsConfig customConfig = new MetricsConfig();
        MetricsGlobal.setDefaultConfig(customConfig);

        MetricsGlobal.setDefaultConfig(null);

        MetricsConfig config = MetricsGlobal.getOrDefault();
        assertNotNull(config);
        assertNotSame(customConfig, config);
    }

    @Test
    void testSetDefaultConfigMultipleTimes() {
        MetricsConfig config1 = new MetricsConfig();
        config1.setDefaultCollectionInterval(Duration.ofMillis(100));

        MetricsConfig config2 = new MetricsConfig();
        config2.setDefaultCollectionInterval(Duration.ofMillis(200));

        MetricsGlobal.setDefaultConfig(config1);
        assertSame(config1, MetricsGlobal.getOrDefault());

        MetricsGlobal.setDefaultConfig(config2);
        assertSame(config2, MetricsGlobal.getOrDefault());
    }

    @Test
    void testGetOrDefaultReturnsNewInstanceWhenNull() {
        MetricsGlobal.setDefaultConfig(null);

        MetricsConfig config1 = MetricsGlobal.getOrDefault();
        MetricsConfig config2 = MetricsGlobal.getOrDefault();

        assertNotNull(config1);
        assertNotNull(config2);
        // Each call should create a new instance when defaultConfig is null
        assertNotSame(config1, config2);
    }

    @Test
    void testGetOrDefaultReturnsSameInstanceWhenSet() {
        MetricsConfig customConfig = new MetricsConfig();
        MetricsGlobal.setDefaultConfig(customConfig);

        MetricsConfig config1 = MetricsGlobal.getOrDefault();
        MetricsConfig config2 = MetricsGlobal.getOrDefault();

        assertSame(customConfig, config1);
        assertSame(customConfig, config2);
        assertSame(config1, config2);
    }

    @Test
    void testDefaultConfigValues() {
        MetricsConfig config = MetricsGlobal.getOrDefault();

        assertNotNull(config);
        // Verify default values from MetricsConfig
        assertNotNull(config.getDefaultCollectionInterval());
        assertFalse(config.getEnabledMetrics().isEmpty());
    }

    @Test
    void testThreadSafetyOfGetOrDefault() throws InterruptedException {
        MetricsGlobal.setDefaultConfig(null);

        // Simple test - multiple threads calling getOrDefault
        Thread[] threads = new Thread[10];
        MetricsConfig[] configs = new MetricsConfig[10];

        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                configs[index] = MetricsGlobal.getOrDefault();
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // All should return non-null configs
        for (MetricsConfig config : configs) {
            assertNotNull(config);
        }
    }

    @Test
    void testSetDefaultConfigWithEmptyMetrics() {
        MetricsConfig customConfig = new MetricsConfig();
        customConfig.setEnabledMetrics(Set.of());

        MetricsGlobal.setDefaultConfig(customConfig);

        MetricsConfig config = MetricsGlobal.getOrDefault();
        assertSame(customConfig, config);
        assertTrue(config.getEnabledMetrics().isEmpty());
    }

    @Test
    void testGetOrDefaultWithEnabledMetrics() {
        MetricsConfig config = MetricsGlobal.getOrDefault();

        assertNotNull(config.getEnabledMetrics());
        assertTrue(config.getEnabledMetrics().contains("memory"));
        assertTrue(config.getEnabledMetrics().contains("cpu"));
    }
}
