package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;

/**
 * 应用程序指标收集器
 */
public class ApplicationMetricCollector implements MetricCollector {

    @Override
    public String getMetricType() {
        return "application";
    }

    @Override
    public Object collectMetric() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        return Map.of(
                "threadCount", threadBean.getThreadCount(),
                "peakThreadCount", threadBean.getPeakThreadCount(),
                "daemonThreadCount", threadBean.getDaemonThreadCount(),
                "uptime", runtimeBean.getUptime(),
                "startTime", runtimeBean.getStartTime(),
                "vmName", runtimeBean.getVmName(),
                "vmVersion", runtimeBean.getVmVersion()
        );
    }
}