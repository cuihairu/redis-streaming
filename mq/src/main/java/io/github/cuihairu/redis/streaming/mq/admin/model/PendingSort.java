package io.github.cuihairu.redis.streaming.mq.admin.model;

/**
 * Sorting strategy for pending messages aggregation.
 */
public enum PendingSort {
    IDLE,
    DELIVERIES,
    ID
}

