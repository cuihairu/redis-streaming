package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricKeys;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.Map;

/**
 * 心跳配置
 *
 * <p>语义说明：</p>
 * <ul>
 *   <li>metadata: 静态业务元数据（region, version），几乎不变</li>
 *   <li>metrics: 动态监控指标（cpu, memory），高频变化</li>
 * </ul>
 */
@Setter
@Getter
public class HeartbeatConfig {

    // ========== 心跳时间配置 ==========

    /**
     * 基础心跳间隔（仅更新时间戳）
     */
    private Duration heartbeatInterval = Duration.ofSeconds(3);

    /**
     * Metrics 更新间隔（心跳 + metrics）
     */
    private Duration metricsInterval = Duration.ofSeconds(60);

    // ========== Metrics 相关配置 ==========

    /**
     * Metrics 变化阈值配置
     * 当监控指标超过阈值时，触发 METRICS_UPDATE
     */
    private Map<String, ChangeThreshold> changeThresholds = Map.of(
            MetricKeys.MEMORY_HEAP_USAGE_PERCENT, new ChangeThreshold(10.0, ChangeThresholdType.ABSOLUTE),     // 内存变化10%
            MetricKeys.CPU_PROCESS_LOAD, new ChangeThreshold(20.0, ChangeThresholdType.ABSOLUTE),   // CPU变化20%
            MetricKeys.DISK_USAGE_PERCENT, new ChangeThreshold(5.0, ChangeThresholdType.ABSOLUTE), // 磁盘变化5%
            MetricKeys.APPLICATION_THREAD_COUNT, new ChangeThreshold(100, ChangeThresholdType.ABSOLUTE), // 线程数变化100
            MetricKeys.HEALTHY, new ChangeThreshold(0, ChangeThresholdType.ANY)              // 健康状态任何变化
    );

    // ========== Metadata 相关配置（新增）==========

    /**
     * 是否启用 metadata 变化检测
     * <p>默认 false，因为 metadata（region, version 等）几乎不变</p>
     * <p>如果应用运行时会动态修改 metadata，可以启用此选项</p>
     */
    private boolean enableMetadataChangeDetection = false;

    /**
     * Metadata 最小更新间隔（秒）
     * <p>防止 metadata 频繁更新，默认 600 秒（10分钟）</p>
     * <p>只有当 enableMetadataChangeDetection=true 时才生效</p>
     */
    private int metadataUpdateIntervalSeconds = 600;

    // ========== 强制更新配置 ==========

    /**
     * 健康状态变化时强制更新
     */
    private boolean forceUpdateOnHealthChange = true;

    /**
     * 启动时强制完整更新
     */
    private boolean forceUpdateOnStartup = true;

    /**
     * 连续多少次仅心跳后，强制进行 metrics 更新
     * <p>防止长时间不更新 metrics，默认 20 次（约 1 分钟）</p>
     */
    private int forceMetricsUpdateThreshold = 20;

    // ========== 辅助方法 ==========

    /**
     * 获取 metrics 更新间隔的毫秒数
     */
    public long getMetricsUpdateIntervalMs() {
        return metricsInterval.toMillis();
    }

    /**
     * 获取强制 metrics 更新的阈值
     */
    public int getForceUpdateThreshold() {
        return forceMetricsUpdateThreshold;
    }
}
