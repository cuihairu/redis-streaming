package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;

/**
 * One running subtask: a runner executing the pipeline plus its partition-pinned consumer.
 * Shared by the start loop and {@code scaleParallelism}'s add/remove bookkeeping.
 */
final class Subtask {
    final int pipelineIndex;
    final RedisPipelineRunner<?> runner;
    final MessageConsumer consumer;

    Subtask(int pipelineIndex, RedisPipelineRunner<?> runner, MessageConsumer consumer) {
        this.pipelineIndex = pipelineIndex;
        this.runner = runner;
        this.consumer = consumer;
    }
}
