package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.time.Duration;

/**
 * Pending message
 */
@Data
@Builder
public class PendingMessage {
    /**
     * Message ID
     */
    private StreamMessageId messageId;

    /**
     * Consumer name
     */
    private String consumerName;

    /**
     * Idle time (how long since this message was last acknowledged)
     */
    private Duration idleTime;

    /**
     * Delivery count
     */
    private long deliveryCount;

    /**
     * First delivery time
     */
    private long firstDeliveryTime;
}
