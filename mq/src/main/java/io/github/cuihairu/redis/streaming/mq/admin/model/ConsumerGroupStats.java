package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;

/**
 * Consumer group statistics
 */
@Data
@Builder
public class ConsumerGroupStats {
    /**
     * Consumer group name
     */
    private String groupName;

    /**
     * Queue (topic) name
     */
    private String topic;

    /**
     * Pending message count
     */
    private long pendingCount;

    /**
     * Number of consumers
     */
    private int consumerCount;

    /**
     * Consumer lag - the gap between the latest message ID in the queue and the consumption position
     */
    private long lag;

    /**
     * Whether any consumer is online
     */
    private boolean hasActiveConsumers;

    /**
     * Name of the slowest consumer
     */
    private String slowestConsumer;

    /**
     * Maximum idle time (milliseconds)
     */
    private long maxIdleTime;
}
