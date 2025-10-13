package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.util.List;

/**
 * 消费者组信息
 */
@Data
@Builder
public class ConsumerGroupInfo {
    /**
     * 消费者组名称
     */
    private String name;

    /**
     * 消费者数量
     */
    private int consumers;

    /**
     * 待处理消息数（Pending）
     */
    private long pending;

    /**
     * 最后投递的消息ID
     */
    private StreamMessageId lastDeliveredId;

    /**
     * 消费者列表
     */
    private List<ConsumerInfo> consumerList;

    /**
     * 总消费消息数（估算）
     */
    private long totalConsumed;
}
