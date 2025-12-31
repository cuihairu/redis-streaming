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
 * Frozen pipeline definition for Redis-backed execution.
 */
public final class RedisPipeline<T> {
    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String consumerGroup;
    private final SubscriptionOptions subscriptionOptions;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks;

    private RedisPipeline(RedisRuntimeConfig config,
                          RedissonClient redissonClient,
                          ObjectMapper objectMapper,
                          String topic,
                          String consumerGroup,
                          SubscriptionOptions subscriptionOptions,
                          List<RedisOperatorNode> operators,
                          List<StreamSink<Object>> sinks) {
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.subscriptionOptions = subscriptionOptions;
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
    }

    public static RedisPipeline<Object> forMqSource(RedisRuntimeConfig config,
                                                    RedissonClient redissonClient,
                                                    ObjectMapper objectMapper,
                                                    String topic,
                                                    String consumerGroup,
                                                    SubscriptionOptions subscriptionOptions,
                                                    List<RedisOperatorNode> operators,
                                                    List<StreamSink<Object>> sinks) {
        return new RedisPipeline<>(config, redissonClient, objectMapper, topic, consumerGroup,
                subscriptionOptions, operators, sinks);
    }

    public String topic() {
        return topic;
    }

    public String consumerGroup() {
        return consumerGroup;
    }

    public SubscriptionOptions subscriptionOptions() {
        return subscriptionOptions;
    }

    public RedisPipelineRunner<T> buildRunner() {
        return new RedisPipelineRunner<>(config, redissonClient, objectMapper, operators, sinks);
    }

    static List<StreamSink<Object>> copySinks(List<?> sinks) {
        List<StreamSink<Object>> out = new ArrayList<>();
        for (Object s : sinks) {
            @SuppressWarnings("unchecked")
            StreamSink<Object> cast = (StreamSink<Object>) s;
            out.add(cast);
        }
        return out;
    }
}

