package io.github.cuihairu.redis.streaming.mq.partition;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple hash-based partitioner. Uses String.hashCode() for key hashing.
 * Falls back to random selection when key is null.
 */
public class HashPartitioner implements Partitioner {

    @Override
    public int partition(String key, int partitionCount) {
        if (partitionCount <= 0) {
            return 0;
        }
        if (key == null) {
            return ThreadLocalRandom.current().nextInt(partitionCount);
        }
        int h = key.hashCode();
        int p = h % partitionCount;
        return p < 0 ? p + partitionCount : p;
    }
}

