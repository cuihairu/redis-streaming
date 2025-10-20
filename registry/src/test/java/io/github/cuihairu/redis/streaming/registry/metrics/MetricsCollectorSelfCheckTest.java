package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 简单的采集自检：收集一次并校验关键键存在。
 */
public class MetricsCollectorSelfCheckTest {

    @Test
    public void collect_once_contains_required_keys() {
        // enable memory/cpu/application/disk/gc collectors
        MetricsConfig cfg = new MetricsConfig();
        Set<String> enabled = new HashSet<>(Arrays.asList("memory","cpu","application","disk","gc"));
        cfg.setEnabledMetrics(enabled);
        Map<String, Duration> intervals = new HashMap<>();
        intervals.put("memory", Duration.ofSeconds(1));
        intervals.put("cpu", Duration.ofSeconds(1));
        intervals.put("application", Duration.ofSeconds(1));
        intervals.put("disk", Duration.ofSeconds(1));
        intervals.put("gc", Duration.ofSeconds(1));
        cfg.setCollectionIntervals(intervals);

        List<MetricCollector> collectors = new ArrayList<>();
        collectors.add(new CpuMetricCollector());
        collectors.add(new MemoryMetricCollector());
        collectors.add(new ApplicationMetricCollector());
        collectors.add(new DiskMetricCollector());
        collectors.add(new GcMetricCollector());

        MetricsCollectionManager mgr = new MetricsCollectionManager(collectors, cfg);

        Map<String, Object> m = mgr.collectMetrics(true);

        // nested groups present
        assertTrue(m.containsKey("cpu"));
        assertTrue(m.containsKey("memory"));
        assertTrue(m.containsKey("disk"));
        assertTrue(m.containsKey("application"));
        assertTrue(m.containsKey("gc"));

        Map<?,?> cpu = (Map<?,?>) m.get("cpu");
        Map<?,?> mem = (Map<?,?>) m.get("memory");
        Map<?,?> disk = (Map<?,?>) m.get("disk");
        Map<?,?> app = (Map<?,?>) m.get("application");
        Map<?,?> gc = (Map<?,?>) m.get("gc");

        assertNotNull(cpu);
        assertNotNull(mem);
        assertNotNull(disk);
        assertNotNull(app);
        assertNotNull(gc);

        // memory/disk/application required keys
        assertTrue(mem.containsKey("heap_used"));
        assertTrue(mem.containsKey("heap_max"));
        assertTrue(disk.containsKey("totalSpace"));
        assertTrue(disk.containsKey("usedSpace"));
        assertTrue(disk.containsKey("freeSpace"));
        assertTrue(app.containsKey("startTime"));
        assertTrue(app.containsKey("uptime"));
        assertTrue(app.containsKey("daemonThreadCount"));
        assertTrue(app.containsKey("peakThreadCount"));
        assertTrue(cpu.containsKey("availableProcessors"));

        // gc required keys
        assertTrue(gc.containsKey("count"));
        assertTrue(gc.containsKey("time"));

        // flattened (dotted) keys also present
        assertTrue(m.containsKey(MetricKeys.MEMORY_HEAP_USED));
        assertTrue(m.containsKey(MetricKeys.DISK_USED_SPACE));
        assertTrue(m.containsKey(MetricKeys.APPLICATION_UPTIME));
        assertTrue(m.containsKey(MetricKeys.GC_COUNT));
        assertTrue(m.containsKey(MetricKeys.CPU_AVAILABLE_PROCESSORS));
    }
}

