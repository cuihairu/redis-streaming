package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable pipeline definition (accumulates sinks) that can be frozen into a {@link RedisPipeline}.
 */
public final class RedisPipelineDefinition {
    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String consumerGroup;
    private final SubscriptionOptions subscriptionOptions;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks = new ArrayList<>();

    public RedisPipelineDefinition(RedisRuntimeConfig config,
                                  RedissonClient redissonClient,
                                  ObjectMapper objectMapper,
                                  String topic,
                                  String consumerGroup,
                                  SubscriptionOptions subscriptionOptions,
                                  List<RedisOperatorNode> operators) {
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.subscriptionOptions = subscriptionOptions;
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
    }

    public synchronized void addSink(StreamSink<Object> sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    public synchronized boolean hasSinks() {
        return !sinks.isEmpty();
    }

    public String topic() {
        return topic;
    }

    public String consumerGroup() {
        return consumerGroup;
    }

    public synchronized RedisPipeline<Object> freeze() {
        if (sinks.isEmpty()) {
            throw new IllegalStateException("No sinks registered for pipeline: " + topic + " group=" + consumerGroup);
        }
        return RedisPipeline.forMqSource(config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, operators, sinks);
    }
}
