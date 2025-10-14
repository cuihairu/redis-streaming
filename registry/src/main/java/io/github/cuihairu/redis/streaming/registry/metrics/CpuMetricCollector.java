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
        // 需要 com.sun.management.OperatingSystemMXBean 获取详细CPU信息
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            double processCpuLoad = sunOsBean.getProcessCpuLoad();
            return Map.of(
                    "processCpuLoad", processCpuLoad >= 0 ? processCpuLoad * 100 : -1,
                    "availableProcessors", osBean.getAvailableProcessors(),
                    "loadAverage", osBean.getSystemLoadAverage()
            );
        }

        return Map.of(
                "availableProcessors", osBean.getAvailableProcessors(),
                "loadAverage", osBean.getSystemLoadAverage()
        );
    }

    @Override
    public CollectionCost getCost() {
        return CollectionCost.MEDIUM;
    }
}
