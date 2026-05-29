package io.github.cuihairu.redis.streaming.mq.admin.model;

import lombok.Builder;
import lombok.Data;

/**
 * Individual consumer information
 */
@Data
@Builder
public class ConsumerInfo {
    /**
     * Consumer name
     */
    private String name;

    /**
     * Pending message count
     */
    private long pending;

    /**
     * Idle time (milliseconds)
     */
    private long idleTime;
}
