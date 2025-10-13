package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;

/**
 * 消费者组统计信息
 */
@Data
@Builder
public class ConsumerGroupStats {
    /**
     * 消费者组名称
     */
    private String groupName;

    /**
     * 队列名称
     */
    private String topic;

    /**
     * 待处理消息数
     */
    private long pendingCount;

    /**
     * 消费者数量
     */
    private int consumerCount;

    /**
     * 消费滞后（Lag）- 队列最新消息ID与消费位置的差距
     */
    private long lag;

    /**
     * 是否有消费者在线
     */
    private boolean hasActiveConsumers;

    /**
     * 最慢的消费者名称
     */
    private String slowestConsumer;

    /**
     * 最大空闲时间（毫秒）
     */
    private long maxIdleTime;
}
