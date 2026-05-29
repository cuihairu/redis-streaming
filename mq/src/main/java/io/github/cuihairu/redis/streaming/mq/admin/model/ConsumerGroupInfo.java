package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.util.List;

/**
 * Consumer group information
 */
@Data
@Builder
public class ConsumerGroupInfo {
    /**
     * Consumer group name
     */
    private String name;

    /**
     * Number of consumers
     */
    private int consumers;

    /**
     * Pending message count
     */
    private long pending;

    /**
     * Last delivered message ID
     */
    private StreamMessageId lastDeliveredId;

    /**
     * Consumer list
     */
    private List<ConsumerInfo> consumerList;

    /**
     * Total consumed message count (estimated)
     */
    private long totalConsumed;
}
