package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * 指标收集成本级别
 */
public enum CollectionCost {
    /**
     * 低开销，如内存使用率
     */
    LOW,

    /**
     * 中等开销，如磁盘IO
     */
    MEDIUM,

    /**
     * 高开销，如网络延迟测试
     */
    HIGH
}