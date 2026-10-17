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
 * Mutable pipeline definition (accumulates sinks) that can be frozen into a {@link RedisPipeline}.
 *
 * <p>Single-source pipelines keep their historical shape; Phase 3 dual-source pipelines
 * ({@code RedisStreamExecutionEnvironment.fromMqTopics}) carry one source per
 * {@code (topic, consumerGroup, SubscriptionOptions)} triple and are consumed by one
 * {@link RedisPipelineRunner} per subtask.
 */
public final class RedisPipelineDefinition {
    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final List<String> topics;
    private final List<String> consumerGroups;
    private final List<SubscriptionOptions> subscriptionOptions;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks = new ArrayList<>();
    private final boolean assignerOwnedEventTime;

    public RedisPipelineDefinition(RedisRuntimeConfig config,
                                  RedissonClient redissonClient,
                                  ObjectMapper objectMapper,
                                  String topic,
                                  String consumerGroup,
                                  SubscriptionOptions subscriptionOptions,
                                  List<RedisOperatorNode> operators) {
        this(config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, operators, false);
    }

    /**
     * @param assignerOwnedEventTime {@code true} when the chain wires
     *        {@code assignTimestampsAndWatermarks(assigner, generator)}: the user generator owns
     *        watermark advancement and the runner's message-timestamp heuristic is muted (see
     *        {@code RedisPipelineRunner}).
     */
    public RedisPipelineDefinition(RedisRuntimeConfig config,
                                  RedissonClient redissonClient,
                                  ObjectMapper objectMapper,
                                  String topic,
                                  String consumerGroup,
                                  SubscriptionOptions subscriptionOptions,
                                  List<RedisOperatorNode> operators,
                                  boolean assignerOwnedEventTime) {
        this(config, redissonClient, objectMapper,
                List.of(Objects.requireNonNull(topic, "topic")),
                List.of(Objects.requireNonNull(consumerGroup, "consumerGroup")),
                Collections.singletonList(subscriptionOptions),
                operators, assignerOwnedEventTime);
    }

    /**
     * Phase 3 dual-source (plan A) constructor: one pipeline consuming several MQ topics, each
     * with its own consumer group and per-subscription options. Index {@code i} of the three
     * lists is one source; the lists must have the same size (at least one). The aligned
     * event-time watermark is the minimum over the sources (see {@code RedisPipelineRunner}),
     * so a source going quiet holds the whole pipeline's windows open.
     *
     * <p>{@code subscriptionOptions} entries may be {@code null} (fall back to the MQ defaults);
     * topic and group entries may not.
     */
    public RedisPipelineDefinition(RedisRuntimeConfig config,
                                  RedissonClient redissonClient,
                                  ObjectMapper objectMapper,
                                  List<String> topics,
                                  List<String> consumerGroups,
                                  List<SubscriptionOptions> subscriptionOptions,
                                  List<RedisOperatorNode> operators,
                                  boolean assignerOwnedEventTime) {
        Objects.requireNonNull(topics, "topics");
        Objects.requireNonNull(consumerGroups, "consumerGroups");
        Objects.requireNonNull(subscriptionOptions, "subscriptionOptions");
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("A pipeline definition needs at least one MQ source");
        }
        if (topics.size() != consumerGroups.size() || topics.size() != subscriptionOptions.size()) {
            throw new IllegalArgumentException(
                    "topics, consumerGroups and subscriptionOptions must have the same size (topics="
                            + topics.size() + ", consumerGroups=" + consumerGroups.size()
                            + ", subscriptionOptions=" + subscriptionOptions.size() + ")");
        }
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i) == null) {
                throw new IllegalArgumentException("Source " + i + " topic must not be null");
            }
            if (consumerGroups.get(i) == null) {
                throw new IllegalArgumentException("Source " + i + " consumerGroup must not be null");
            }
        }
        this.assignerOwnedEventTime = assignerOwnedEventTime;
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
        this.consumerGroups = Collections.unmodifiableList(new ArrayList<>(consumerGroups));
        this.subscriptionOptions = Collections.unmodifiableList(new ArrayList<>(subscriptionOptions));
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
    }

    public synchronized void addSink(StreamSink<Object> sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    public synchronized boolean hasSinks() {
        return !sinks.isEmpty();
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
     * does not read it. Message callbacks resolve the arriving source this way (see
     * {@code RedisMessageConsumer}: a {@code Message} carries its topic).
     */
    public int sourceIndex(String topic) {
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i).equals(topic)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized RedisPipeline<Object> freeze() {
        if (sinks.isEmpty()) {
            throw new IllegalStateException("No sinks registered for pipeline: " + topic() + " group=" + consumerGroup());
        }
        return RedisPipeline.forMqSource(config, redissonClient, objectMapper, topics, consumerGroups, subscriptionOptions,
                operators, sinks, assignerOwnedEventTime);
    }
}
