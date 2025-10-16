package io.github.cuihairu.redis.streaming.mq.broker;

import io.github.cuihairu.redis.streaming.mq.Message;

/** Persist a message into backend storage under a specific partition. */
public interface BrokerPersistence {
    /**
     * Append message to topic's partition and return backend id.
     */
    String append(String topic, int partitionId, Message message);
}

