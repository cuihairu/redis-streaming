package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricKeys;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.Map;

/**
 * Heartbeat configuration
 *
 * <p>Semantic definitions:</p>
 * <ul>
 *   <li>metadata: Static business metadata (region, version), rarely changes</li>
 *   <li>metrics: Dynamic monitoring indicators (cpu, memory), high-frequency changes</li>
 * </ul>
 */
@Setter
@Getter
public class HeartbeatConfig {

    // ========== Heartbeat time configuration ==========

    /**
     * Base heartbeat interval (only updates timestamp)
     */
    private Duration heartbeatInterval = Duration.ofSeconds(3);

    /**
     * Metrics update interval (heartbeat + metrics)
     */
    private Duration metricsInterval = Duration.ofSeconds(60);

    // ========== Metrics related configuration ==========

    /**
     * Metrics change threshold configuration
     * When monitoring indicators exceed thresholds, triggers METRICS_UPDATE
     */
    private Map<String, ChangeThreshold> changeThresholds = Map.of(
            MetricKeys.MEMORY_HEAP_USAGE_PERCENT, new ChangeThreshold(10.0, ChangeThresholdType.ABSOLUTE),     // Memory change 10%
            MetricKeys.CPU_PROCESS_LOAD, new ChangeThreshold(0.20, ChangeThresholdType.ABSOLUTE),              // CPU change 0.20 (i.e. 20%)
            MetricKeys.DISK_USAGE_PERCENT, new ChangeThreshold(5.0, ChangeThresholdType.ABSOLUTE),             // Disk change 5%
            MetricKeys.APPLICATION_THREAD_COUNT, new ChangeThreshold(100, ChangeThresholdType.ABSOLUTE),       // Thread count change 100
            MetricKeys.HEALTHY, new ChangeThreshold(0, ChangeThresholdType.ANY)                                // Any health status change
    );

    // ========== Metadata related configuration (new) ==========

    /**
     * Whether to enable metadata change detection
     * <p>Default false, because metadata (region, version, etc.) rarely changes</p>
     * <p>If the application dynamically modifies metadata at runtime, this option can be enabled</p>
     */
    private boolean enableMetadataChangeDetection = false;

    /**
     * Metadata minimum update interval (seconds)
     * <p>Prevents frequent metadata updates, default 600 seconds (10 minutes)</p>
     * <p>Only effective when enableMetadataChangeDetection=true</p>
     */
    private int metadataUpdateIntervalSeconds = 600;

    // ========== Force update configuration ==========

    /**
     * Force update when health status changes
     */
    private boolean forceUpdateOnHealthChange = true;

    /**
     * Force full update on startup
     */
    private boolean forceUpdateOnStartup = true;

    /**
     * After how many consecutive heartbeat-only cycles, force a metrics update
     * <p>Prevents long periods without metrics updates, default 20 times (approximately 1 minute)</p>
     */
    private int forceMetricsUpdateThreshold = 20;

    // ========== Helper methods ==========

    /**
     * Get metrics update interval in milliseconds
     */
    public long getMetricsUpdateIntervalMs() {
        return metricsInterval.toMillis();
    }

    /**
     * Get the force metrics update threshold
     */
    public int getForceUpdateThreshold() {
        return forceMetricsUpdateThreshold;
    }
}
