package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;

/**
 * Memory metric collector
 */
public class MemoryMetricCollector implements MetricCollector {

    @Override
    public String getMetricType() {
        return "memory";
    }

    @Override
    public Object collectMetric() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        // Convention: memory percentage is still reported as 0~100; consumers can also calculate from used/max
        double heapPct = heapUsage.getMax() > 0 ? (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0;
        double nonHeapPct = nonHeapUsage.getMax() > 0 ? (double) nonHeapUsage.getUsed() / nonHeapUsage.getMax() * 100 : 0;

        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("heap_used", heapUsage.getUsed());
        out.put(MetricKeys.MEMORY_HEAP_USED, heapUsage.getUsed());
        out.put("heap_max", heapUsage.getMax());
        out.put(MetricKeys.MEMORY_HEAP_MAX, heapUsage.getMax());
        out.put("heap_committed", heapUsage.getCommitted());
        out.put("heap_usagePercent", heapPct);
        out.put(MetricKeys.MEMORY_HEAP_USAGE_PERCENT, heapPct);
        out.put("nonHeap_used", nonHeapUsage.getUsed());
        out.put(MetricKeys.MEMORY_NON_HEAP_USED, nonHeapUsage.getUsed());
        out.put("nonHeap_max", nonHeapUsage.getMax());
        out.put("nonHeap_committed", nonHeapUsage.getCommitted());
        out.put("nonHeap_usagePercent", nonHeapPct);
        out.put(MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT, nonHeapPct);
        return out;
    }
}
