package io.github.cuihairu.redis.streaming.registry.metrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Linux-specific network metrics collector parsing /proc/net/dev.
 * Returns sum of rx/tx bytes for all interfaces: {"rxBytes":long, "txBytes":long}
 */
public class NetworkMetricCollector implements MetricCollector {
    private static final File PROC_NET_DEV = new File("/proc/net/dev");

    @Override
    public String getMetricType() { return "network"; }

    @Override
    public boolean isAvailable() { return PROC_NET_DEV.exists() && PROC_NET_DEV.canRead(); }

    @Override
    public Object collectMetric() throws Exception {
        if (!isAvailable()) return Map.of();
        long rx = 0, tx = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(PROC_NET_DEV))) {
            String line;
            int skip = 2;
            while ((line = br.readLine()) != null) {
                if (skip > 0) { skip--; continue; }
                // format: iface:  rx_bytes ... tx_bytes ...
                String[] parts = line.split(":");
                if (parts.length != 2) continue;
                String[] nums = parts[1].trim().split("\\s+");
                if (nums.length >= 16) {
                    rx += parseLong(nums[0]);
                    tx += parseLong(nums[8]);
                }
            }
        }
        Map<String,Object> m = new HashMap<>();
        m.put("rxBytes", rx);
        m.put("txBytes", tx);
        return m;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}

