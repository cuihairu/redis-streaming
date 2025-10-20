package io.github.cuihairu.redis.streaming.registry.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;

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

        String hostname = "unknown";
        try {
            hostname = SystemUtils.getLocalHostname();
        } catch (Throwable ignore) {}

        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("threadCount", threadBean.getThreadCount());
        out.put(MetricKeys.APPLICATION_THREAD_COUNT, threadBean.getThreadCount());
        out.put("peakThreadCount", threadBean.getPeakThreadCount());
        out.put(MetricKeys.APPLICATION_PEAK_THREAD_COUNT, threadBean.getPeakThreadCount());
        out.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        out.put(MetricKeys.APPLICATION_DAEMON_THREAD_COUNT, threadBean.getDaemonThreadCount());
        out.put("uptime", runtimeBean.getUptime());
        out.put(MetricKeys.APPLICATION_UPTIME, runtimeBean.getUptime());
        out.put("startTime", runtimeBean.getStartTime());
        out.put(MetricKeys.APPLICATION_START_TIME, runtimeBean.getStartTime());
        out.put("vmName", runtimeBean.getVmName());
        out.put("vmVersion", runtimeBean.getVmVersion());
        out.put("pid", pid);
        out.put("hostname", hostname);
        return out;
    }
}
