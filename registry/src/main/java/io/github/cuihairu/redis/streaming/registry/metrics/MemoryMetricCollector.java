package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;

/**
 * 内存指标收集器
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

        return Map.of(
                "heap_used", heapUsage.getUsed(),
                "heap_max", heapUsage.getMax(),
                "heap_committed", heapUsage.getCommitted(),
                "heap_usagePercent", heapUsage.getMax() > 0 ? (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0,
                "nonheap_used", nonHeapUsage.getUsed(),
                "nonheap_max", nonHeapUsage.getMax(),
                "nonheap_committed", nonHeapUsage.getCommitted()
        );
    }
}