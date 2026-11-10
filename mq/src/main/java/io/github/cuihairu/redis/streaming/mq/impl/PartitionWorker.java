package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandler;

import java.util.concurrent.atomic.AtomicBoolean;


/** State of one partition reader thread; {@code running} is the cooperative stop flag. */
final class PartitionWorker {
        final String topic;
        final String group;
        final int partitionId;
        final MessageHandler handler;
        final AtomicBoolean running = new AtomicBoolean(true);
        Integer batchOverride; // nullable per-subscription override
        Long timeoutOverrideMs; // nullable per-subscription override

        PartitionWorker(String topic, String group, int partitionId, MessageHandler handler) {
            this.topic = topic;
            this.group = group;
            this.partitionId = partitionId;
            this.handler = handler;
        }

        void stop() { running.set(false); }
}
