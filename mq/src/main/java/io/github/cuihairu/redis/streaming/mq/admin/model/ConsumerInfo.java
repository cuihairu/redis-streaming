package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;

/**
 * 单个消费者信息
 */
@Data
@Builder
public class ConsumerInfo {
    /**
     * 消费者名称
     */
    private String name;

    /**
     * 待处理消息数
     */
    private long pending;

    /**
     * 空闲时间（毫秒）
     */
    private long idleTime;
}
