package io.github.cuihairu.redis.streaming.mq.partition;

/**
 * Partitioner selects a partition for a given key and partition count.
 */
public interface Partitioner {

    /**
     * Select partition index in range [0, partitionCount).
     * If key is null, the implementation may fallback to a default strategy.
     */
    int partition(String key, int partitionCount);
}

