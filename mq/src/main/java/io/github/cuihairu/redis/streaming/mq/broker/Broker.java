package io.github.cuihairu.redis.streaming.mq.broker;

import io.github.cuihairu.redis.streaming.mq.Message;

/**
 * Broker facade: centralizes routing + persistence for produce path.
 * Fetch/commit can be added later; for now we only standardize produce.
 */
public interface Broker {
    /**
     * Produce a message. Implementations decide partition via BrokerRouter,
     * and persist via BrokerPersistence.
     *
     * @return generated message id (backend specific)
     */
    String produce(Message message);

    /**
     * Read messages from a topic partition within a consumer group (never-delivered semantics).
     * Implementations should mirror underlying backend group-consume behavior.
     */
    java.util.List<BrokerRecord> readGroup(String topic, String consumerGroup, String consumerName,
                                           int partitionId, int count, long timeoutMs);

    /** Acknowledge a message within the consumer group. */
    void ack(String topic, String consumerGroup, int partitionId, String messageId);
}
