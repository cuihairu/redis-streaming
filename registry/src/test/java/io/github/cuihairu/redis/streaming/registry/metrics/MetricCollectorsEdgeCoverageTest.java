package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers CPU collector fallback branches via proxied OperatingSystemMXBean seams
 * (no main-code changes): -1 loads hit the "unavailable" branches, a non-com.sun
 * bean hits the fallback reporting path.
 */
class MetricCollectorsEdgeCoverageTest {

    private static Object invokeDefault(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "getAvailableProcessors" -> 4;
            case "getSystemLoadAverage" -> 0.25d;
            case "getProcessCpuLoad", "getCpuLoad" -> -1.0d;
            case "toString" -> "proxy-os-bean";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private static void injectOsBean(CpuMetricCollector collector, OperatingSystemMXBean bean) throws Exception {
        Field f = CpuMetricCollector.class.getDeclaredField("osBean");
        f.setAccessible(true);
        f.set(collector, bean);
    }

    @Test
    void cpuCollectorSkipsNegativeLoads() throws Exception {
        CpuMetricCollector collector = new CpuMetricCollector();
        OperatingSystemMXBean negativeLoads = (OperatingSystemMXBean) Proxy.newProxyInstance(
                MetricCollectorsEdgeCoverageTest.class.getClassLoader(),
                new Class<?>[]{com.sun.management.OperatingSystemMXBean.class},
                (proxy, method, args) -> invokeDefault(proxy, method, args));
        injectOsBean(collector, negativeLoads);

        Map<?, ?> out = (Map<?, ?>) collector.collectMetric();
        assertFalse(out.containsKey("processCpuLoad"));
        assertFalse(out.containsKey("systemCpuLoad"));
        assertEquals(4, ((Number) out.get("availableProcessors")).intValue());
        assertTrue(out.containsKey("loadAverage"));
    }

    @Test
    void cpuCollectorFallsBackForNonSunBean() throws Exception {
        CpuMetricCollector collector = new CpuMetricCollector();
        OperatingSystemMXBean plain = (OperatingSystemMXBean) Proxy.newProxyInstance(
                MetricCollectorsEdgeCoverageTest.class.getClassLoader(),
                new Class<?>[]{OperatingSystemMXBean.class},
                (proxy, method, args) -> invokeDefault(proxy, method, args));
        injectOsBean(collector, plain);

        Map<?, ?> out = (Map<?, ?>) collector.collectMetric();
        assertEquals(4, ((Number) out.get("availableProcessors")).intValue());
        assertTrue(out.containsKey("loadAverage"));
        assertFalse(out.containsKey(MetricKeys.CPU_PROCESS_LOAD));
    }

    @Test
    void memoryAndDiskAndGcAndApplicationCollectorsDoNotThrow() throws Exception {
        assertNotNull(new MemoryMetricCollector().collectMetric());
        assertNotNull(new DiskMetricCollector().collectMetric());
        Object gc = new GcMetricCollector().collectMetric();
        assertNotNull(gc);
        assertTrue(((Map<?, ?>) gc).containsKey("count"));
        Object app = new ApplicationMetricCollector().collectMetric();
        assertNotNull(app);
        assertTrue(((Map<?, ?>) app).containsKey("threadCount"));
    }
}
