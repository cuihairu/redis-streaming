package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * 指标收集器接口
 */
public interface MetricCollector {

    /**
     * 指标类型标识
     */
    String getMetricType();

    /**
     * 收集指标数据
     */
    Object collectMetric() throws Exception;

    /**
     * 指标是否可用（检查依赖环境）
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 指标收集的开销级别
     */
    default CollectionCost getCost() {
        return CollectionCost.LOW;
    }
}