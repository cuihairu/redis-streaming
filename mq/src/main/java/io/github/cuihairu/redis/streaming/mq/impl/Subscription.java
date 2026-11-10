package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandler;


/** A single topic subscription with its handler and optional partition-pinning overrides. */
final class Subscription {
        final String topic;
        final String consumerGroup;
        final MessageHandler handler;
        Integer batchOverride; // nullable
        Long timeoutOverrideMs; // nullable
        // volatile: read by the scheduler-thread rebalance/renew tasks, written at runtime
        // by updatePartitionAssignment (dynamic scaling)
        volatile Integer partitionModulo; // nullable
        volatile Integer partitionRemainder; // nullable

        Subscription(String topic, String consumerGroup, MessageHandler handler) {
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.handler = handler;
        }
}
