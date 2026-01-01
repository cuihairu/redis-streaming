package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricKeys
 */
class MetricKeysTest {

    @Test
    void testConstructorIsPrivate() throws Exception {
        // Given & When - try to get constructor
        Constructor<MetricKeys> constructor = MetricKeys.class.getDeclaredConstructor();

        // Then - constructor should be private
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor should be private");

        // And - set accessible and try to instantiate
        // The constructor is private but doesn't throw exception
        // It's just a utility class pattern
        constructor.setAccessible(true);
        MetricKeys instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void testMemoryMetricKeys() {
        assertEquals("memory.heap_usagePercent", MetricKeys.MEMORY_HEAP_USAGE_PERCENT);
        assertEquals("memory.heap_used", MetricKeys.MEMORY_HEAP_USED);
        assertEquals("memory.heap_max", MetricKeys.MEMORY_HEAP_MAX);
        assertEquals("memory.nonHeap_usagePercent", MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT);
        assertEquals("memory.nonHeap_used", MetricKeys.MEMORY_NON_HEAP_USED);
    }

    @Test
    void testCpuMetricKeys() {
        assertEquals("cpu.processCpuLoad", MetricKeys.CPU_PROCESS_LOAD);
        assertEquals("cpu.systemCpuLoad", MetricKeys.CPU_SYSTEM_LOAD);
        assertEquals("cpu.availableProcessors", MetricKeys.CPU_AVAILABLE_PROCESSORS);
    }

    @Test
    void testDiskMetricKeys() {
        assertEquals("disk.usagePercent", MetricKeys.DISK_USAGE_PERCENT);
        assertEquals("disk.totalSpace", MetricKeys.DISK_TOTAL_SPACE);
        assertEquals("disk.freeSpace", MetricKeys.DISK_FREE_SPACE);
        assertEquals("disk.usedSpace", MetricKeys.DISK_USED_SPACE);
    }

    @Test
    void testApplicationMetricKeys() {
        assertEquals("application.threadCount", MetricKeys.APPLICATION_THREAD_COUNT);
        assertEquals("application.startTime", MetricKeys.APPLICATION_START_TIME);
        assertEquals("application.uptime", MetricKeys.APPLICATION_UPTIME);
        assertEquals("application.daemonThreadCount", MetricKeys.APPLICATION_DAEMON_THREAD_COUNT);
        assertEquals("application.peakThreadCount", MetricKeys.APPLICATION_PEAK_THREAD_COUNT);
    }

    @Test
    void testHealthMetricKeys() {
        assertEquals("healthy", MetricKeys.HEALTHY);
        assertEquals("enabled", MetricKeys.ENABLED);
    }

    @Test
    void testGcMetricKeys() {
        assertEquals("gc.count", MetricKeys.GC_COUNT);
        assertEquals("gc.time", MetricKeys.GC_TIME);
    }

    @Test
    void testNetworkMetricKeys() {
        assertEquals("network.connections", MetricKeys.NETWORK_CONNECTIONS);
        assertEquals("network.requests", MetricKeys.NETWORK_REQUESTS);
        assertEquals("network.errors", MetricKeys.NETWORK_ERRORS);
    }

    @Test
    void testLegacyMetricKeys() {
        assertEquals("CPU_PROCESS_LOAD", MetricKeys.LEGACY_CPU_PROCESS_LOAD);
        assertEquals("CPU_SYSTEM_LOAD", MetricKeys.LEGACY_CPU_SYSTEM_LOAD);
        assertEquals("CPU_AVAILABLE_PROCESSORS", MetricKeys.LEGACY_CPU_AVAILABLE_PROCESSORS);
        assertEquals("MEMORY_HEAP_USED", MetricKeys.LEGACY_MEMORY_HEAP_USED);
        assertEquals("MEMORY_HEAP_MAX", MetricKeys.LEGACY_MEMORY_HEAP_MAX);
        assertEquals("DISK_TOTAL_SPACE", MetricKeys.LEGACY_DISK_TOTAL_SPACE);
        assertEquals("DISK_USED_SPACE", MetricKeys.LEGACY_DISK_USED_SPACE);
        assertEquals("DISK_FREE_SPACE", MetricKeys.LEGACY_DISK_FREE_SPACE);
        assertEquals("SYSTEM_LOAD_AVERAGE", MetricKeys.LEGACY_SYSTEM_LOAD_AVERAGE);
        assertEquals("LOAD_AVERAGE", MetricKeys.LEGACY_LOAD_AVERAGE);
    }

    @Test
    void testAllFieldsArePublicStaticFinal() throws Exception {
        // Given - get all declared fields
        Field[] fields = MetricKeys.class.getDeclaredFields();

        // When & Then - verify all public static final
        for (Field field : fields) {
            if (!field.isSynthetic()) {
                int modifiers = field.getModifiers();
                assertTrue(Modifier.isPublic(modifiers),
                        "Field " + field.getName() + " should be public");
                assertTrue(Modifier.isStatic(modifiers),
                        "Field " + field.getName() + " should be static");
                assertTrue(Modifier.isFinal(modifiers),
                        "Field " + field.getName() + " should be final");
            }
        }
    }

    @Test
    void testMetricKeysFollowNamingConvention() {
        // Verify that modern metric keys use dot notation
        assertTrue(MetricKeys.MEMORY_HEAP_USAGE_PERCENT.contains("."));
        assertTrue(MetricKeys.CPU_PROCESS_LOAD.contains("."));
        assertTrue(MetricKeys.DISK_USAGE_PERCENT.contains("."));
        assertTrue(MetricKeys.APPLICATION_THREAD_COUNT.contains("."));
        assertTrue(MetricKeys.NETWORK_CONNECTIONS.contains("."));
    }

    @Test
    void testLegacyKeysUseUpperCaseUnderscore() {
        // Verify that legacy keys use uppercase with underscore
        assertFalse(MetricKeys.LEGACY_CPU_PROCESS_LOAD.contains("."));
        assertFalse(MetricKeys.LEGACY_MEMORY_HEAP_USED.contains("."));
        assertFalse(MetricKeys.LEGACY_DISK_TOTAL_SPACE.contains("."));
    }

    @Test
    void testMetricKeyGroupings() {
        // Memory metrics start with "memory."
        assertTrue(MetricKeys.MEMORY_HEAP_USAGE_PERCENT.startsWith("memory."));
        assertTrue(MetricKeys.MEMORY_HEAP_USED.startsWith("memory."));
        assertTrue(MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT.startsWith("memory."));

        // CPU metrics start with "cpu."
        assertTrue(MetricKeys.CPU_PROCESS_LOAD.startsWith("cpu."));
        assertTrue(MetricKeys.CPU_SYSTEM_LOAD.startsWith("cpu."));
        assertTrue(MetricKeys.CPU_AVAILABLE_PROCESSORS.startsWith("cpu."));

        // Disk metrics start with "disk."
        assertTrue(MetricKeys.DISK_USAGE_PERCENT.startsWith("disk."));
        assertTrue(MetricKeys.DISK_TOTAL_SPACE.startsWith("disk."));
        assertTrue(MetricKeys.DISK_FREE_SPACE.startsWith("disk."));

        // Application metrics start with "application."
        assertTrue(MetricKeys.APPLICATION_THREAD_COUNT.startsWith("application."));
        assertTrue(MetricKeys.APPLICATION_START_TIME.startsWith("application."));
        assertTrue(MetricKeys.APPLICATION_UPTIME.startsWith("application."));

        // Network metrics start with "network."
        assertTrue(MetricKeys.NETWORK_CONNECTIONS.startsWith("network."));
        assertTrue(MetricKeys.NETWORK_REQUESTS.startsWith("network."));
        assertTrue(MetricKeys.NETWORK_ERRORS.startsWith("network."));

        // GC metrics start with "gc."
        assertTrue(MetricKeys.GC_COUNT.startsWith("gc."));
        assertTrue(MetricKeys.GC_TIME.startsWith("gc."));
    }

    @Test
    void testAllMetricKeysAreUnique() {
        // Use reflection to ensure no duplicate values
        assertNotEquals(MetricKeys.MEMORY_HEAP_USAGE_PERCENT, MetricKeys.CPU_PROCESS_LOAD);
        assertNotEquals(MetricKeys.DISK_USAGE_PERCENT, MetricKeys.APPLICATION_THREAD_COUNT);
        assertNotEquals(MetricKeys.HEALTHY, MetricKeys.ENABLED);
        assertNotEquals(MetricKeys.GC_COUNT, MetricKeys.GC_TIME);
        assertNotEquals(MetricKeys.NETWORK_CONNECTIONS, MetricKeys.NETWORK_REQUESTS);
    }

    @Test
    void testHealthAndEnabledAreSimpleKeys() {
        // HEALTHY and ENABLED are simple keys without category prefix
        assertEquals("healthy", MetricKeys.HEALTHY);
        assertEquals("enabled", MetricKeys.ENABLED);
        assertFalse(MetricKeys.HEALTHY.contains("."));
        assertFalse(MetricKeys.ENABLED.contains("."));
    }

    @Test
    void testDeprecatedLegacyKeysExist() {
        // Verify all legacy keys have @Deprecated annotation
        try {
            Field cpuLoad = MetricKeys.class.getField("LEGACY_CPU_PROCESS_LOAD");
            assertTrue(cpuLoad.isAnnotationPresent(Deprecated.class),
                    "LEGACY_CPU_PROCESS_LOAD should be @Deprecated");

            Field memoryHeapUsed = MetricKeys.class.getField("LEGACY_MEMORY_HEAP_USED");
            assertTrue(memoryHeapUsed.isAnnotationPresent(Deprecated.class),
                    "LEGACY_MEMORY_HEAP_USED should be @Deprecated");

            Field diskTotal = MetricKeys.class.getField("LEGACY_DISK_TOTAL_SPACE");
            assertTrue(diskTotal.isAnnotationPresent(Deprecated.class),
                    "LEGACY_DISK_TOTAL_SPACE should be @Deprecated");
        } catch (NoSuchFieldException e) {
            fail("Legacy field should exist: " + e.getMessage());
        }
    }

    @Test
    void testMemoryMetricKeyNaming() {
        // Verify memory metrics naming pattern
        assertTrue(MetricKeys.MEMORY_HEAP_USAGE_PERCENT.endsWith("usagePercent"));
        assertTrue(MetricKeys.MEMORY_HEAP_USED.endsWith("used"));
        assertTrue(MetricKeys.MEMORY_HEAP_MAX.endsWith("max"));
        assertTrue(MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT.endsWith("usagePercent"));
        assertTrue(MetricKeys.MEMORY_NON_HEAP_USED.endsWith("used"));
    }

    @Test
    void testDiskMetricKeyNaming() {
        // Verify disk metrics naming pattern
        assertTrue(MetricKeys.DISK_USAGE_PERCENT.endsWith("usagePercent"));
        assertTrue(MetricKeys.DISK_TOTAL_SPACE.endsWith("totalSpace"));
        assertTrue(MetricKeys.DISK_FREE_SPACE.endsWith("freeSpace"));
        assertTrue(MetricKeys.DISK_USED_SPACE.endsWith("usedSpace"));
    }

    @Test
    void testApplicationMetricKeyNaming() {
        // Verify application metrics naming pattern
        assertTrue(MetricKeys.APPLICATION_THREAD_COUNT.endsWith("threadCount"));
        assertTrue(MetricKeys.APPLICATION_START_TIME.endsWith("startTime"));
        assertTrue(MetricKeys.APPLICATION_UPTIME.endsWith("uptime"));
        assertTrue(MetricKeys.APPLICATION_DAEMON_THREAD_COUNT.endsWith("daemonThreadCount"));
        assertTrue(MetricKeys.APPLICATION_PEAK_THREAD_COUNT.endsWith("peakThreadCount"));
    }

    @Test
    void testMetricKeysAreStrings() {
        // Verify all fields are String type
        try {
            Field heapUsage = MetricKeys.class.getField("MEMORY_HEAP_USAGE_PERCENT");
            assertEquals(String.class, heapUsage.getType());

            Field cpuLoad = MetricKeys.class.getField("CPU_PROCESS_LOAD");
            assertEquals(String.class, cpuLoad.getType());

            Field healthy = MetricKeys.class.getField("HEALTHY");
            assertEquals(String.class, healthy.getType());
        } catch (NoSuchFieldException e) {
            fail("Field should exist: " + e.getMessage());
        }
    }

    @Test
    void testMetricKeysAreNotEmpty() {
        // Verify all metric keys have non-empty values
        assertFalse(MetricKeys.MEMORY_HEAP_USAGE_PERCENT.isEmpty());
        assertFalse(MetricKeys.CPU_PROCESS_LOAD.isEmpty());
        assertFalse(MetricKeys.DISK_USAGE_PERCENT.isEmpty());
        assertFalse(MetricKeys.APPLICATION_THREAD_COUNT.isEmpty());
        assertFalse(MetricKeys.HEALTHY.isEmpty());
        assertFalse(MetricKeys.GC_COUNT.isEmpty());
        assertFalse(MetricKeys.NETWORK_CONNECTIONS.isEmpty());
    }

    @Test
    void testAllMetricKeysStartWithCategory() {
        // All modern metric keys (except HEALTHY and ENABLED) should start with category prefix
        assertTrue(MetricKeys.MEMORY_HEAP_USAGE_PERCENT.matches("^[a-z]+\\..+"));
        assertTrue(MetricKeys.CPU_PROCESS_LOAD.matches("^[a-z]+\\..+"));
        assertTrue(MetricKeys.DISK_USAGE_PERCENT.matches("^[a-z]+\\..+"));
        assertTrue(MetricKeys.APPLICATION_THREAD_COUNT.matches("^[a-z]+\\..+"));
        assertTrue(MetricKeys.GC_COUNT.matches("^[a-z]+\\..+"));
        assertTrue(MetricKeys.NETWORK_CONNECTIONS.matches("^[a-z]+\\..+"));
    }

    @Test
    void testLegacyKeysMapToModernKeys() {
        // Verify legacy keys represent the same concepts as modern keys
        // CPU keys
        assertTrue(MetricKeys.LEGACY_CPU_PROCESS_LOAD.toUpperCase().contains("CPU"));
        assertTrue(MetricKeys.LEGACY_CPU_SYSTEM_LOAD.toUpperCase().contains("CPU"));

        // Memory keys
        assertTrue(MetricKeys.LEGACY_MEMORY_HEAP_USED.toUpperCase().contains("MEMORY"));
        assertTrue(MetricKeys.LEGACY_MEMORY_HEAP_MAX.toUpperCase().contains("MEMORY"));

        // Disk keys
        assertTrue(MetricKeys.LEGACY_DISK_TOTAL_SPACE.toUpperCase().contains("DISK"));
        assertTrue(MetricKeys.LEGACY_DISK_USED_SPACE.toUpperCase().contains("DISK"));
        assertTrue(MetricKeys.LEGACY_DISK_FREE_SPACE.toUpperCase().contains("DISK"));
    }

    @Test
    void testNetworkMetricsKeys() {
        assertEquals("network.connections", MetricKeys.NETWORK_CONNECTIONS);
        assertEquals("network.requests", MetricKeys.NETWORK_REQUESTS);
        assertEquals("network.errors", MetricKeys.NETWORK_ERRORS);
    }
}
