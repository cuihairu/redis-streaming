package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CpuMetricCollector
 */
class CpuMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("cpu", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsAvailableProcessors() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("availableProcessors"));
        assertTrue(metrics.containsKey(MetricKeys.CPU_AVAILABLE_PROCESSORS));
        assertTrue(metrics.get("availableProcessors") instanceof Integer);
        assertTrue((Integer) metrics.get("availableProcessors") > 0);
    }

    @Test
    void testCollectMetricContainsLoadAverage() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("loadAverage"));
        assertTrue(metrics.get("loadAverage") instanceof Double);
    }

    @Test
    void testAvailableProcessorsMatchesSystem() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertEquals(osBean.getAvailableProcessors(), metrics.get("availableProcessors"));
    }

    @Test
    void testLoadAverageMatchesSystem() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertEquals(osBean.getSystemLoadAverage(), metrics.get("loadAverage"));
    }

    @Test
    void testIsAvailable() {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When & Then
        assertEquals(CollectionCost.MEDIUM, collector.getCost());
    }

    @Test
    void testContainsProcessCpuLoadWhenAvailable() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - if Sun-specific MXBean is available, process CPU load should be present
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            // Process CPU load may be -1 initially, so check key exists
            if (metrics.containsKey("processCpuLoad")) {
                assertTrue(metrics.containsKey(MetricKeys.CPU_PROCESS_LOAD));
                Object load = metrics.get("processCpuLoad");
                assertTrue(load instanceof Double);
                double cpuLoad = (Double) load;
                assertTrue(cpuLoad >= -1.0);
                assertTrue(cpuLoad <= 1.0);
            }
        }
    }

    @Test
    void testContainsSystemCpuLoadWhenAvailable() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - if Sun-specific MXBean is available, system CPU load should be present
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            // System CPU load may be -1 initially, so check key exists
            if (metrics.containsKey("systemCpuLoad")) {
                assertTrue(metrics.containsKey(MetricKeys.CPU_SYSTEM_LOAD));
                Object load = metrics.get("systemCpuLoad");
                assertTrue(load instanceof Double);
                double cpuLoad = (Double) load;
                assertTrue(cpuLoad >= -1.0);
                assertTrue(cpuLoad <= 1.0);
            }
        }
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values
        assertEquals(metrics.get("availableProcessors"), metrics.get(MetricKeys.CPU_AVAILABLE_PROCESSORS));
        if (metrics.containsKey("processCpuLoad")) {
            assertEquals(metrics.get("processCpuLoad"), metrics.get(MetricKeys.CPU_PROCESS_LOAD));
        }
        if (metrics.containsKey("systemCpuLoad")) {
            assertEquals(metrics.get("systemCpuLoad"), metrics.get(MetricKeys.CPU_SYSTEM_LOAD));
        }
    }

    @Test
    void testCpuLoadInRange() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - if CPU loads are present and valid (not -1), they should be in range [0, 1]
        if (metrics.containsKey("processCpuLoad")) {
            double processLoad = (Double) metrics.get("processCpuLoad");
            if (processLoad >= 0) {
                assertTrue(processLoad <= 1.0, "Process CPU load should be between 0 and 1");
            }
        }
        if (metrics.containsKey("systemCpuLoad")) {
            double systemLoad = (Double) metrics.get("systemCpuLoad");
            if (systemLoad >= 0) {
                assertTrue(systemLoad <= 1.0, "System CPU load should be between 0 and 1");
            }
        }
    }

    @Test
    void testMultipleCollections() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - available processors should be constant
        assertEquals(metrics1.get("availableProcessors"), metrics2.get("availableProcessors"));
    }

    @Test
    void testAvailableProcessorsReasonable() throws Exception {
        // Given
        CpuMetricCollector collector = new CpuMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - should have at least 1 processor and at most 128 (reasonable upper bound)
        int processors = (Integer) metrics.get("availableProcessors");
        assertTrue(processors >= 1);
        assertTrue(processors <= 128);
    }
}
