package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.time.Duration;

/**
 * 待处理消息（Pending）
 */
@Data
@Builder
public class PendingMessage {
    /**
     * 消息ID
     */
    private StreamMessageId messageId;

    /**
     * 消费者名称
     */
    private String consumerName;

    /**
     * 空闲时间（该消息多久没被确认）
     */
    private Duration idleTime;

    /**
     * 投递次数
     */
    private long deliveryCount;

    /**
     * 首次投递时间
     */
    private long firstDeliveryTime;
}
