package io.github.cuihairu.redis.streaming.registry.metrics;

import java.io.File;
import java.util.Map;

/**
 * 磁盘指标收集器
 */
public class DiskMetricCollector implements MetricCollector {

    @Override
    public String getMetricType() {
        return "disk";
    }

    @Override
    public Object collectMetric() {
        File root = new File("/");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        long used = total - free;

        return Map.of(
                "total", total,
                "free", free,
                "used", used,
                "usagePercent", total > 0 ? (double) used / total * 100 : 0
        );
    }

    @Override
    public boolean isAvailable() {
        return new File("/").exists(); // 简单检查
    }
}