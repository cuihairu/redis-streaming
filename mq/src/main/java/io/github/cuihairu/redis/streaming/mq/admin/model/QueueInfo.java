package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;
import org.redisson.api.StreamMessageId;

import java.time.Instant;

/**
 * Queue information
 */
@Data
@Builder
public class QueueInfo {
    /**
     * Queue name
     */
    private String topic;

    /**
     * Total message count
     */
    private long length;

    /**
     * Consumer group count
     */
    private int consumerGroupCount;

    /**
     * First message ID
     */
    private StreamMessageId firstMessageId;

    /**
     * Last message ID
     */
    private StreamMessageId lastMessageId;

    /**
     * Queue creation time (timestamp of the first message)
     */
    private Instant createdAt;

    /**
     * Last update time (timestamp of the last message)
     */
    private Instant lastUpdatedAt;

    /**
     * Whether the queue exists
     */
    private boolean exists;
}
