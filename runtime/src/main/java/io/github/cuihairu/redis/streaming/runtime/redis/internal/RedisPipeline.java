package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Frozen pipeline definition for Redis-backed execution.
 *
 * <p>Phase 3 dual-source pipelines carry several MQ sources (one
 * {@code (topic, consumerGroup, SubscriptionOptions)} triple each); a single
 * {@link RedisPipelineRunner} per subtask consumes all of them, and the aligned event-time
 * watermark is the minimum over the sources.
 */
public final class RedisPipeline<T> {
    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final List<String> topics;
    private final List<String> consumerGroups;
    private final List<SubscriptionOptions> subscriptionOptions;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks;
    private final boolean assignerOwnedEventTime;

    private RedisPipeline(RedisRuntimeConfig config,
                          RedissonClient redissonClient,
                          ObjectMapper objectMapper,
                          String topic,
                          String consumerGroup,
                          SubscriptionOptions subscriptionOptions,
                          List<RedisOperatorNode> operators,
                          List<StreamSink<Object>> sinks) {
        this(config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, operators, sinks, false);
    }

    private RedisPipeline(RedisRuntimeConfig config,
                          RedissonClient redissonClient,
                          ObjectMapper objectMapper,
                          String topic,
                          String consumerGroup,
                          SubscriptionOptions subscriptionOptions,
                          List<RedisOperatorNode> operators,
                          List<StreamSink<Object>> sinks,
                          boolean assignerOwnedEventTime) {
        this(config, redissonClient, objectMapper,
                List.of(Objects.requireNonNull(topic, "topic")),
                List.of(Objects.requireNonNull(consumerGroup, "consumerGroup")),
                Collections.singletonList(subscriptionOptions), operators, sinks, assignerOwnedEventTime);
    }

    private RedisPipeline(RedisRuntimeConfig config,
                          RedissonClient redissonClient,
                          ObjectMapper objectMapper,
                          List<String> topics,
                          List<String> consumerGroups,
                          List<SubscriptionOptions> subscriptionOptions,
                          List<RedisOperatorNode> operators,
                          List<StreamSink<Object>> sinks,
                          boolean assignerOwnedEventTime) {
        Objects.requireNonNull(topics, "topics");
        Objects.requireNonNull(consumerGroups, "consumerGroups");
        Objects.requireNonNull(subscriptionOptions, "subscriptionOptions");
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("A pipeline needs at least one MQ source");
        }
        if (topics.size() != consumerGroups.size() || topics.size() != subscriptionOptions.size()) {
            throw new IllegalArgumentException("topics, consumerGroups and subscriptionOptions must have the same size");
        }
        this.assignerOwnedEventTime = assignerOwnedEventTime;
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
        this.consumerGroups = Collections.unmodifiableList(new ArrayList<>(consumerGroups));
        this.subscriptionOptions = Collections.unmodifiableList(new ArrayList<>(subscriptionOptions));
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

    /** {@code assignerOwnedEventTime}: see {@code RedisPipelineRunner} (user generator owns watermarks). */
    public static RedisPipeline<Object> forMqSource(RedisRuntimeConfig config,
                                                    RedissonClient redissonClient,
                                                    ObjectMapper objectMapper,
                                                    String topic,
                                                    String consumerGroup,
                                                    SubscriptionOptions subscriptionOptions,
                                                    List<RedisOperatorNode> operators,
                                                    List<StreamSink<Object>> sinks,
                                                    boolean assignerOwnedEventTime) {
        return new RedisPipeline<>(config, redissonClient, objectMapper, topic, consumerGroup,
                subscriptionOptions, operators, sinks, assignerOwnedEventTime);
    }

    /** Phase 3 dual-source factory: see {@code RedisPipelineDefinition}. */
    public static RedisPipeline<Object> forMqSource(RedisRuntimeConfig config,
                                                    RedissonClient redissonClient,
                                                    ObjectMapper objectMapper,
                                                    List<String> topics,
                                                    List<String> consumerGroups,
                                                    List<SubscriptionOptions> subscriptionOptions,
                                                    List<RedisOperatorNode> operators,
                                                    List<StreamSink<Object>> sinks,
                                                    boolean assignerOwnedEventTime) {
        return new RedisPipeline<>(config, redissonClient, objectMapper, topics, consumerGroups,
                subscriptionOptions, operators, sinks, assignerOwnedEventTime);
    }

    public String topic() {
        return topics.get(0);
    }

    public String consumerGroup() {
        return consumerGroups.get(0);
    }

    public SubscriptionOptions subscriptionOptions() {
        return subscriptionOptions.get(0);
    }

    /** Number of MQ sources this pipeline reads. */
    public int sourceCount() {
        return topics.size();
    }

    /** The topic of the {@code i}-th source. */
    public String topicAt(int sourceIndex) {
        return topics.get(sourceIndex);
    }

    /** The consumer group of the {@code i}-th source. */
    public String consumerGroupAt(int sourceIndex) {
        return consumerGroups.get(sourceIndex);
    }

    /** The per-subscription options of the {@code i}-th source, or {@code null} for the MQ defaults. */
    public SubscriptionOptions subscriptionOptionsAt(int sourceIndex) {
        return subscriptionOptions.get(sourceIndex);
    }

    /**
     * The index of {@code topic} among this pipeline's sources, or {@code -1} when the pipeline
     * does not read it.
     */
    public int sourceIndex(String topic) {
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i).equals(topic)) {
                return i;
            }
        }
        return -1;
    }

    public RedisPipelineRunner<T> buildRunner() {
        return new RedisPipelineRunner<>(config, redissonClient, objectMapper, topics, consumerGroups, operators, sinks,
                null, true, assignerOwnedEventTime);
    }

    public RedisPipelineRunner<T> buildRunner(java.util.concurrent.ScheduledExecutorService timerExecutor) {
        return new RedisPipelineRunner<>(config, redissonClient, objectMapper, topics, consumerGroups, operators, sinks,
                timerExecutor, false, assignerOwnedEventTime);
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
