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

        double pct = total > 0 ? (double) used / total * 100 : 0;
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("totalSpace", total);
        out.put(MetricKeys.DISK_TOTAL_SPACE, total);
        out.put("freeSpace", free);
        out.put(MetricKeys.DISK_FREE_SPACE, free);
        out.put("usedSpace", used);
        out.put(MetricKeys.DISK_USED_SPACE, used);
        out.put("usagePercent", pct);
        out.put(MetricKeys.DISK_USAGE_PERCENT, pct);
        return out;
    }

    @Override
    public boolean isAvailable() {
        return new File("/").exists(); // 简单检查
    }
}
