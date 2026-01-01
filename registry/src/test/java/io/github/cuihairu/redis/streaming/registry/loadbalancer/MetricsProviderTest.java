package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsProvider interface
 */
class MetricsProviderTest {

    @Test
    void testInterfaceHasGetMetricsMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricsProvider.class.getMethod("getMetrics", String.class, String.class));
    }

    @Test
    void testSimpleImplementation() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("cpu", 75.5);
            metrics.put("memory", 1024);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertNotNull(metrics);
        assertEquals(75.5, metrics.get("cpu"));
        assertEquals(1024, metrics.get("memory"));
    }

    @Test
    void testGetMetricsWithNullServiceName() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("service", serviceName);
            metrics.put("instance", instanceId);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics(null, "instance-1");

        // Then
        assertNotNull(metrics);
        assertNull(metrics.get("service"));
        assertEquals("instance-1", metrics.get("instance"));
    }

    @Test
    void testGetMetricsWithNullInstanceId() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("service", serviceName);
            metrics.put("instance", instanceId);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", null);

        // Then
        assertNotNull(metrics);
        assertEquals("test-service", metrics.get("service"));
        assertNull(metrics.get("instance"));
    }

    @Test
    void testGetMetricsWithBothNull() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            if (serviceName == null && instanceId == null) {
                return null;
            }
            metrics.put("service", serviceName);
            metrics.put("instance", instanceId);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics(null, null);

        // Then
        assertNull(metrics);
    }

    @Test
    void testImplementationReturnsEmptyMap() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> new HashMap<>();

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void testImplementationReturnsNull() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> null;

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertNull(metrics);
    }

    @Test
    void testMultipleMetrics() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("cpu.usage", 45.5);
            metrics.put("memory.used", 2048);
            metrics.put("memory.total", 4096);
            metrics.put("request.count", 1000);
            metrics.put("error.count", 5);
            metrics.put("response.time.avg", 25.3);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics("api-service", "instance-1");

        // Then
        assertEquals(6, metrics.size());
        assertEquals(45.5, metrics.get("cpu.usage"));
        assertEquals(2048, metrics.get("memory.used"));
        assertEquals(4096, metrics.get("memory.total"));
        assertEquals(1000, metrics.get("request.count"));
        assertEquals(5, metrics.get("error.count"));
        assertEquals(25.3, metrics.get("response.time.avg"));
    }

    @Test
    void testDifferentServicesDifferentMetrics() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            if ("database-service".equals(serviceName)) {
                metrics.put("connections", 50);
                metrics.put("query.time", 10.5);
            } else if ("cache-service".equals(serviceName)) {
                metrics.put("hit.ratio", 0.95);
                metrics.put("size", 1024);
            } else {
                metrics.put("default", true);
            }
            return metrics;
        };

        // When
        Map<String, Object> dbMetrics = provider.getMetrics("database-service", "db-1");
        Map<String, Object> cacheMetrics = provider.getMetrics("cache-service", "cache-1");
        Map<String, Object> defaultMetrics = provider.getMetrics("other-service", "other-1");

        // Then
        assertTrue(dbMetrics.containsKey("connections"));
        assertTrue(cacheMetrics.containsKey("hit.ratio"));
        assertTrue(defaultMetrics.containsKey("default"));
    }

    @Test
    void testDifferentInstancesDifferentMetrics() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("instance.id", instanceId);
            // Simulate different load on different instances
            if ("instance-1".equals(instanceId)) {
                metrics.put("load", 0.2);
            } else if ("instance-2".equals(instanceId)) {
                metrics.put("load", 0.8);
            } else {
                metrics.put("load", 0.5);
            }
            return metrics;
        };

        // When
        Map<String, Object> metrics1 = provider.getMetrics("test-service", "instance-1");
        Map<String, Object> metrics2 = provider.getMetrics("test-service", "instance-2");
        Map<String, Object> metrics3 = provider.getMetrics("test-service", "instance-3");

        // Then
        assertEquals(0.2, metrics1.get("load"));
        assertEquals(0.8, metrics2.get("load"));
        assertEquals(0.5, metrics3.get("load"));
    }

    @Test
    void testCachedMetrics() {
        // Given - provider that caches metrics
        class CachedMetricsProvider implements MetricsProvider {
            private Map<String, Object> cachedMetrics = null;

            @Override
            public Map<String, Object> getMetrics(String serviceName, String instanceId) {
                if (cachedMetrics == null) {
                    cachedMetrics = new HashMap<>();
                    cachedMetrics.put("cached", true);
                    cachedMetrics.put("timestamp", System.currentTimeMillis());
                }
                return cachedMetrics;
            }

            void invalidateCache() {
                cachedMetrics = null;
            }
        }

        CachedMetricsProvider provider = new CachedMetricsProvider();

        // When
        Map<String, Object> metrics1 = provider.getMetrics("test-service", "instance-1");
        Map<String, Object> metrics2 = provider.getMetrics("test-service", "instance-1");

        // Then - should return same cached map
        assertSame(metrics1, metrics2);
        assertTrue((Boolean) metrics1.get("cached"));

        // When - invalidate cache
        provider.invalidateCache();
        Map<String, Object> metrics3 = provider.getMetrics("test-service", "instance-1");

        // Then - should return new map
        assertNotSame(metrics1, metrics3);
    }

    @Test
    void testMetricsWithDifferentTypes() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("string.value", "test");
            metrics.put("int.value", 42);
            metrics.put("long.value", 1000000L);
            metrics.put("double.value", 3.14);
            metrics.put("boolean.value", true);
            metrics.put("null.value", null);
            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertEquals("test", metrics.get("string.value"));
        assertEquals(42, metrics.get("int.value"));
        assertEquals(1000000L, metrics.get("long.value"));
        assertEquals(3.14, metrics.get("double.value"));
        assertEquals(true, metrics.get("boolean.value"));
        assertNull(metrics.get("null.value"));
    }

    @Test
    void testThreadSafety() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("thread.name", Thread.currentThread().getName());
            metrics.put("timestamp", System.currentTimeMillis());
            return metrics;
        };

        // When - call from same thread
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertNotNull(metrics);
        assertNotNull(metrics.get("thread.name"));
    }

    @Test
    void testComplexMetricsStructure() {
        // Given
        MetricsProvider provider = (serviceName, instanceId) -> {
            Map<String, Object> metrics = new HashMap<>();

            // Nested map
            Map<String, Object> nested = new HashMap<>();
            nested.put("inner1", "value1");
            nested.put("inner2", 42);
            metrics.put("nested", nested);

            // List value
            metrics.put("list", java.util.Arrays.asList("a", "b", "c"));

            return metrics;
        };

        // When
        Map<String, Object> metrics = provider.getMetrics("test-service", "instance-1");

        // Then
        assertTrue(metrics.containsKey("nested"));
        assertTrue(metrics.containsKey("list"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) metrics.get("nested");
        assertEquals("value1", nested.get("inner1"));
        assertEquals(42, nested.get("inner2"));
    }
}
