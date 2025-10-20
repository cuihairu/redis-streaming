package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GC 指标收集器
 * 汇总所有 GC 的总次数与总耗时（毫秒）
 */
public class GcMetricCollector implements MetricCollector {

    @Override
    public String getMetricType() {
        return "gc";
    }

    @Override
    public Object collectMetric() {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long count = 0L;
        long time = 0L;
        if (gcs != null) {
            for (GarbageCollectorMXBean gc : gcs) {
                try {
                    long c = gc.getCollectionCount();
                    long t = gc.getCollectionTime();
                    if (c > 0) count += c;
                    if (t > 0) time += t;
                } catch (Throwable ignore) {
                    // ignore per-bean failure
                }
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("count", count);
        out.put(MetricKeys.GC_COUNT, count);
        out.put("time", time);
        out.put(MetricKeys.GC_TIME, time);
        return out;
    }
}
