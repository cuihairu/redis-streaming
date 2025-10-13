package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.time.Instant;

/**
 * 队列信息
 */
@Data
@Builder
public class QueueInfo {
    /**
     * 队列名称
     */
    private String topic;

    /**
     * 消息总数
     */
    private long length;

    /**
     * 消费者组数量
     */
    private int consumerGroupCount;

    /**
     * 第一条消息ID
     */
    private StreamMessageId firstMessageId;

    /**
     * 最后一条消息ID
     */
    private StreamMessageId lastMessageId;

    /**
     * 队列创建时间（第一条消息的时间）
     */
    private Instant createdAt;

    /**
     * 最后更新时间（最后一条消息的时间）
     */
    private Instant lastUpdatedAt;

    /**
     * 队列是否存在
     */
    private boolean exists;
}
