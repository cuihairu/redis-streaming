package io.github.cuihairu.redis.streaming.registry.metrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * 网络指标收集器
 * - 优先从 Tomcat MBean 读取请求/错误/连接数（若存在）
 * - 附加提供 Linux /proc/net/dev 的 rx/tx 字节计数（若存在）
 */
public class NetworkMetricCollector implements MetricCollector {
    private static final File PROC_NET_DEV = new File("/proc/net/dev");

    @Override
    public String getMetricType() { return "network"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public Object collectMetric() throws Exception {
        Map<String, Object> out = new HashMap<>();

        // 1) Tomcat MBeans（可选）
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            // GlobalRequestProcessor: requestCount / errorCount
            Set<ObjectName> grps = mbs.queryNames(new ObjectName("Catalina:type=GlobalRequestProcessor,*"), null);
            long requests = 0, errors = 0;
            if (grps != null) {
                for (ObjectName on : grps) {
                    try {
                        Object rc = mbs.getAttribute(on, "requestCount");
                        Object ec = mbs.getAttribute(on, "errorCount");
                        if (rc instanceof Number) requests += ((Number) rc).longValue();
                        if (ec instanceof Number) errors += ((Number) ec).longValue();
                    } catch (Throwable ignore) {}
                }
            }
            if (requests > 0) { out.put("requests", requests); out.put(MetricKeys.NETWORK_REQUESTS, requests); }
            if (errors > 0)   { out.put("errors", errors);     out.put(MetricKeys.NETWORK_ERRORS, errors); }

            // ThreadPool: currentThreadsBusy 作为活跃连接近似
            Set<ObjectName> tps = mbs.queryNames(new ObjectName("Catalina:type=ThreadPool,*"), null);
            long busy = 0;
            if (tps != null) {
                for (ObjectName on : tps) {
                    try {
                        Object curBusy = mbs.getAttribute(on, "currentThreadsBusy");
                        if (curBusy instanceof Number) busy += ((Number) curBusy).longValue();
                    } catch (Throwable ignore) {}
                }
            }
            if (busy > 0) { out.put("connections", busy); out.put(MetricKeys.NETWORK_CONNECTIONS, busy); }
        } catch (Throwable ignore) {
            // no tomcat or no permission
        }

        // 2) Linux /proc/net/dev（可选）
        if (PROC_NET_DEV.exists() && PROC_NET_DEV.canRead()) {
            long rx = 0, tx = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(PROC_NET_DEV))) {
                String line;
                int skip = 2;
                while ((line = br.readLine()) != null) {
                    if (skip > 0) { skip--; continue; }
                    String[] parts = line.split(":");
                    if (parts.length != 2) continue;
                    String[] nums = parts[1].trim().split("\\s+");
                    if (nums.length >= 16) {
                        rx += parseLong(nums[0]);
                        tx += parseLong(nums[8]);
                    }
                }
            } catch (Throwable ignore) {}
            out.put("rxBytes", rx);
            out.put("txBytes", tx);
        }

        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
