package io.github.cuihairu.redis.streaming.mq.broker;

import java.util.Map;

/** Decide partition for a message under a given topic and partition count. */
public interface BrokerRouter {
    int routePartition(String topic, String key, Map<String,String> headers, int partitionCount);
}

