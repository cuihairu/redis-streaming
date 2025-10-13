package io.github.cuihairu.redis.streaming.registry.heartbeat;

/**
 * 更新决策枚举
 *
 * <p>语义说明：</p>
 * <ul>
 *   <li>metadata: 静态业务元数据（region, version 等），注册时设置，几乎不变</li>
 *   <li>metrics: 动态监控指标（cpu, memory, qps 等），每次心跳可能变化</li>
 * </ul>
 */
public enum UpdateDecision {
    /**
     * 不需要更新（完全跳过，连心跳时间都不更新）
     */
    NO_UPDATE,

    /**
     * 仅更新心跳时间戳（最常见场景，无数据变化）
     */
    HEARTBEAT_ONLY,

    /**
     * 更新 metrics + 心跳时间（高频场景，监控指标变化）
     */
    METRICS_UPDATE,

    /**
     * 更新 metadata + 心跳时间（低频场景，业务配置变更）
     */
    METADATA_UPDATE,

    /**
     * 同时更新 metadata + metrics + 心跳时间（罕见场景，首次注册或重大变更）
     */
    FULL_UPDATE
}