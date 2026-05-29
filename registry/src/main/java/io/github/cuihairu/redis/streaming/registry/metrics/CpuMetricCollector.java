package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;

/**
 * CPU metric collector
 */
public class CpuMetricCollector implements MetricCollector {

    private final OperatingSystemMXBean osBean;

    public CpuMetricCollector() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public String getMetricType() {
        return "cpu";
    }

    @Override
    public Object collectMetric() {
        // Note: CPU_*_LOAD is conventionally a 0~1 decimal; percentage conversion is left to the consumer
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            double processCpuLoad = sunOsBean.getProcessCpuLoad();  // 0~1 or -1
            double systemCpuLoad = sunOsBean.getCpuLoad();    // 0~1 or -1

            java.util.Map<String, Object> out = new java.util.HashMap<>();
            if (processCpuLoad >= 0) {
                out.put("processCpuLoad", processCpuLoad);
                out.put(MetricKeys.CPU_PROCESS_LOAD, processCpuLoad);
            }
            if (systemCpuLoad >= 0) {
                out.put("systemCpuLoad", systemCpuLoad);
                out.put(MetricKeys.CPU_SYSTEM_LOAD, systemCpuLoad);
            }
            out.put("availableProcessors", osBean.getAvailableProcessors());
            out.put(MetricKeys.CPU_AVAILABLE_PROCESSORS, osBean.getAvailableProcessors());
            out.put("loadAverage", osBean.getSystemLoadAverage()); // Informational; consumers should not use this as a fallback estimate
            return out;
        }

        // Fallback: report only available processors and system load average when detailed CPU info is unavailable
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("availableProcessors", osBean.getAvailableProcessors());
        out.put("loadAverage", osBean.getSystemLoadAverage());
        return out;
    }

    @Override
    public CollectionCost getCost() {
        return CollectionCost.MEDIUM;
    }
}
