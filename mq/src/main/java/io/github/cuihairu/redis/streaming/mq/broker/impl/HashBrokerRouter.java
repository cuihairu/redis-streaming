package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.partition.HashPartitioner;

import java.util.Map;

/**
 * Default router: allow forced partition via header {@link io.github.cuihairu.redis.streaming.mq.MqHeaders#FORCE_PARTITION_ID}, else hash by key.
 */
public class HashBrokerRouter implements BrokerRouter {
    private final HashPartitioner partitioner = new HashPartitioner();

    @Override
    public int routePartition(String topic, String key, Map<String, String> headers, int partitionCount) {
        if (partitionCount <= 0) return 0;
        // Honor forced partition header if provided
        if (headers != null) {
            String forced = headers.get(io.github.cuihairu.redis.streaming.mq.MqHeaders.FORCE_PARTITION_ID);
            if (forced != null) {
                try {
                    int fp = Integer.parseInt(forced);
                    int pid = fp % partitionCount;
                    return pid < 0 ? pid + partitionCount : pid;
                } catch (Exception ignore) {}
            }
        }
        return partitioner.partition(key, partitionCount);
    }
}
