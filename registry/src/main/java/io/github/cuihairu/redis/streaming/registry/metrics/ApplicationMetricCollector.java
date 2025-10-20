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

        long pid;
        try {
            pid = ProcessHandle.current().pid();
        } catch (Throwable t) {
            // Fallback to legacy JVM name parsing (pid@hostname)
            try {
                String name = runtimeBean.getName();
                pid = Long.parseLong(name.split("@")[0]);
            } catch (Exception e) {
                pid = -1L;
            }
        }

        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }

        return Map.of(
                "threadCount", threadBean.getThreadCount(),
                "peakThreadCount", threadBean.getPeakThreadCount(),
                "daemonThreadCount", threadBean.getDaemonThreadCount(),
                "uptime", runtimeBean.getUptime(),
                "startTime", runtimeBean.getStartTime(),
                "vmName", runtimeBean.getVmName(),
                "vmVersion", runtimeBean.getVmVersion(),
                "pid", pid,
                "hostname", hostname
        );
    }
}
