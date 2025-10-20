package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;

/**
 * CPU指标收集器
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
        // 说明：统一约定 CPU_*_LOAD 语义为 0~1 的小数，百分比转换交由消费端
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            double processCpuLoad = sunOsBean.getProcessCpuLoad();  // 0~1 或 -1
            double systemCpuLoad = sunOsBean.getCpuLoad();    // 0~1 或 -1

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
            out.put("loadAverage", osBean.getSystemLoadAverage()); // 信息项，消费端不再兜底估算
            return out;
        }

        // 降级：无法获取详细CPU信息时仅上报可用核数和系统平均负载
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
