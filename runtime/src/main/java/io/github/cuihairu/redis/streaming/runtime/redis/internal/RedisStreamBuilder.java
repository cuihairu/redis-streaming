package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable stream builder that compiles into a {@link RedisPipelineDefinition} when the first sink is attached.
 */
public final class RedisStreamBuilder<T> implements DataStream<T> {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamBuilder.class);

    private final RedisStreamExecutionEnvironment env;
    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String streamId;
    private final String topic;
    private final String consumerGroup;
    private final SubscriptionOptions subscriptionOptions;
    /** Phase 3: the pipeline's MQ sources in order (index 0 is {@link #topic}). */
    private final List<String> sourceTopics;
    private final List<String> sourceConsumerGroups;
    private final List<SubscriptionOptions> sourceSubscriptionOptions;
    private final List<RedisOperatorNode> operators;
    private final boolean assignerOwnedEventTime;

    private RedisPipelineDefinition registeredDefinition;

    RedisStreamBuilder(RedisStreamExecutionEnvironment env,
                              RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String streamId,
                              String topic,
                              String consumerGroup,
                              SubscriptionOptions subscriptionOptions,
                              List<RedisOperatorNode> operators) {
        this(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, operators, false);
    }

    RedisStreamBuilder(RedisStreamExecutionEnvironment env,
                              RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String streamId,
                              String topic,
                              String consumerGroup,
                              SubscriptionOptions subscriptionOptions,
                              List<RedisOperatorNode> operators,
                              boolean assignerOwnedEventTime) {
        // singletonList, not List.of: a null subscriptionOptions means "MQ defaults" and is the
        // documented default of fromMqTopic(topic, group)
        this(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions,
                List.of(topic), List.of(consumerGroup), Collections.singletonList(subscriptionOptions),
                operators, assignerOwnedEventTime);
    }

    RedisStreamBuilder(RedisStreamExecutionEnvironment env,
                              RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String streamId,
                              String topic,
                              String consumerGroup,
                              SubscriptionOptions subscriptionOptions,
                              List<String> sourceTopics,
                              List<String> sourceConsumerGroups,
                              List<SubscriptionOptions> sourceSubscriptionOptions,
                              List<RedisOperatorNode> operators,
                              boolean assignerOwnedEventTime) {
        this.assignerOwnedEventTime = assignerOwnedEventTime;

        this.env = Objects.requireNonNull(env, "env");
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.streamId = Objects.requireNonNull(streamId, "streamId");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.subscriptionOptions = subscriptionOptions;
        this.sourceTopics = List.copyOf(sourceTopics);
        this.sourceConsumerGroups = List.copyOf(sourceConsumerGroups);
        // per-source options are nullable (null = MQ defaults), so copy defensively instead of
        // List.copyOf, which rejects null elements
        this.sourceSubscriptionOptions = Collections.unmodifiableList(new ArrayList<>(sourceSubscriptionOptions));
        this.operators = List.copyOf(operators);
    }

    public static RedisStreamBuilder<Message> forMqSource(RedisStreamExecutionEnvironment env,
                                                         RedisRuntimeConfig config,
                                                         RedissonClient redissonClient,
                                                         ObjectMapper objectMapper,
                                                         String streamId,
                                                         String topic,
                                                         String consumerGroup,
                                                         SubscriptionOptions subscriptionOptions) {
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, List.of(), false);
    }

    /**
     * Phase 3 dual-source (plan A) builder: one pipeline reading several MQ topics, each with its
     * own consumer group and per-subscription options. The sources share one operator chain and
     * one keyed-state key (source 0's topic/group), so both sides of a join keyed by the same
     * partition land in the same state — the sources must therefore be keyed by the join key and
     * carry the same partition count.
     */
    public static RedisStreamBuilder<Message> forMqSources(RedisStreamExecutionEnvironment env,
                                                          RedisRuntimeConfig config,
                                                          RedissonClient redissonClient,
                                                          ObjectMapper objectMapper,
                                                          String streamId,
                                                          List<String> topics,
                                                          List<String> consumerGroups,
                                                          List<SubscriptionOptions> subscriptionOptions) {
        Objects.requireNonNull(topics, "topics");
        Objects.requireNonNull(consumerGroups, "consumerGroups");
        Objects.requireNonNull(subscriptionOptions, "subscriptionOptions");
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("A source builder needs at least one MQ source");
        }
        if (topics.size() != consumerGroups.size() || topics.size() != subscriptionOptions.size()) {
            throw new IllegalArgumentException("topics, consumerGroups and subscriptionOptions must have the same size");
        }
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId,
                topics.get(0), consumerGroups.get(0), subscriptionOptions.get(0),
                topics, consumerGroups, subscriptionOptions, List.of(), false);
    }

    private RedisStreamBuilder<T> withOperator(RedisOperatorNode node) {
        List<RedisOperatorNode> next = new ArrayList<>(operators.size() + 1);
        next.addAll(operators);
        next.add(node);
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, next,
                assignerOwnedEventTime);
    }

    @Override
    public <R> DataStream<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return cast(withOperator((value, ctx, emit) -> emit.emit(mapper.apply(castValue(value)))));
    }

    @Override
    public DataStream<T> filter(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return withOperator((value, ctx, emit) -> {
            T v = castValue(value);
            if (predicate.test(v)) {
                emit.emit(v);
            }
        });
    }

    @Override
    public <R> DataStream<R> flatMap(Function<T, Iterable<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return cast(withOperator((value, ctx, emit) -> {
            Iterable<R> it = mapper.apply(castValue(value));
            if (it == null) return;
            for (R r : it) {
                emit.emit(r);
            }
        }));
    }

    @Override
    public <K> KeyedStream<K, T> keyBy(Function<T, K> keySelector) {
        Objects.requireNonNull(keySelector, "keySelector");
        String operatorId = streamId + "-keyBy-" + operators.size();
        RedisKeyedStateStore<K> store = new RedisKeyedStateStore<>(redissonClient, objectMapper,
                config.getStateKeyPrefix(), config.getJobName(), topic, consumerGroup, operatorId, config.getStateTtl(),
                config.getStateSizeReportEveryNStateWrites(), config.getKeyedStateShardCount(),
                config.getKeyedStateHotKeyFieldsWarnThreshold(), config.getKeyedStateHotKeyWarnInterval(),
                config.getKeyedStateHotKeyPolicy(), config.getKeyedStateHotKeyThrottleMaxMs(),
                config.isStateSchemaEvolutionEnabled(), config.getStateSchemaMismatchPolicy());
        return new RedisKeyedStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions,
                operators, keySelector, store, operatorId, assignerOwnedEventTime);
    }

    @Override
    public DataStream<T> addSink(StreamSink<T> sink) {
        Objects.requireNonNull(sink, "sink");
        RedisPipelineDefinition def = ensureRegistered();
        @SuppressWarnings("unchecked")
        StreamSink<Object> cast = (StreamSink<Object>) sink;
        def.addSink(cast);
        return this;
    }

    @Override
    public DataStream<T> assignTimestampsAndWatermarks(WatermarkGenerator<T> watermarkGenerator) {
        Objects.requireNonNull(watermarkGenerator, "watermarkGenerator");
        return withOperator((value, ctx, emit) -> {
            T v = castValue(value);
            WatermarkGenerator.WatermarkOutput output = new WatermarkGenerator.WatermarkOutput() {
                @Override
                public void emitWatermark(Watermark watermark) {
                    if (watermark != null) {
                        ctx.raiseWatermark(watermark.getTimestamp());
                    }
                }

                @Override
                public void markIdle() {
                    // RT-M3: with watermarkIdleTimeout configured, the next idle sweep
                    // flushes the watermark even without record silence
                    ctx.markIdle();
                }

                @Override
                public void markActive() {
                    ctx.markActive();
                }
            };
            watermarkGenerator.onEvent(v, ctx.currentEventTime(), output);
            watermarkGenerator.onPeriodicEmit(output);
            emit.emit(v);
        });
    }

    /**
     * Event-time assignment (Flink semantics): the {@link TimestampAssigner} decides the
     * record's event time, downstream operators see it via {@code ctx.currentEventTime()},
     * and the supplied generator owns watermark advancement — the built-in
     * message-timestamp heuristic is muted for the rest of this pipeline
     * ({@code RedisPipelineRunner} assigner-owned mode). A generator that never emits
     * leaves the watermark at {@code MIN_VALUE}: pair it with
     * {@code watermarkIdleTimeout} (RT-M3 idle flush) if quiet periods are expected.
     *
     * <p>This overload wires <em>one</em> generator instance for the whole pipeline. On a
     * dual-source pipeline a stateful generator's max-timestamp crosses the sources and its
     * candidates run ahead of the slower source's data — use the
     * {@link #assignTimestampsAndWatermarks(TimestampAssigner, java.util.function.Supplier)
     * per-source factory overload} there.
     */
    @Override
    public DataStream<T> assignTimestampsAndWatermarks(TimestampAssigner<T> timestampAssigner,
                                                      WatermarkGenerator<T> watermarkGenerator) {
        Objects.requireNonNull(timestampAssigner, "timestampAssigner");
        Objects.requireNonNull(watermarkGenerator, "watermarkGenerator");
        java.util.function.Supplier<WatermarkGenerator<T>> shared = () -> watermarkGenerator;
        return assignTimestampsAndWatermarks(timestampAssigner, shared,
                new java.util.concurrent.atomic.AtomicReferenceArray<>(sourceTopics.size()), true);
    }

    /**
     * Per-source factory overload (see {@code DataStream}): the factory builds one generator
     * instance per MQ source, lazily on that source's first record, so a stateful generator
     * only ever sees its own source's records. Both builder overloads funnel into this method;
     * {@code shareOneInstance} marks the single-instance form (still one entry, but resolved
     * through the same path so the wiring cannot drift).
     */
    @Override
    public DataStream<T> assignTimestampsAndWatermarks(TimestampAssigner<T> timestampAssigner,
                                                      java.util.function.Supplier<WatermarkGenerator<T>> generatorFactory) {
        Objects.requireNonNull(timestampAssigner, "timestampAssigner");
        Objects.requireNonNull(generatorFactory, "generatorFactory");
        return assignTimestampsAndWatermarks(timestampAssigner, generatorFactory,
                new java.util.concurrent.atomic.AtomicReferenceArray<>(sourceTopics.size()), false);
    }

    private DataStream<T> assignTimestampsAndWatermarks(TimestampAssigner<T> timestampAssigner,
                                                        java.util.function.Supplier<WatermarkGenerator<T>> generatorFactory,
                                                        java.util.concurrent.atomic.AtomicReferenceArray<WatermarkGenerator<T>> perSource,
                                                        boolean shareOneInstance) {
        List<RedisOperatorNode> next = new ArrayList<>(operators.size() + 1);
        next.addAll(operators);
        next.add((value, ctx, emit) -> {
            T v = castValue(value);
            long assigned = timestampAssigner.extractTimestamp(v, ctx.currentEventTime());
            ctx.overrideEventTimeMs(assigned);
            WatermarkGenerator<T> generator = generatorFor(generatorFactory, perSource, shareOneInstance,
                    ctx.currentSourceIndex());
            WatermarkGenerator.WatermarkOutput output = new WatermarkGenerator.WatermarkOutput() {
                @Override
                public void emitWatermark(Watermark watermark) {
                    if (watermark != null) {
                        ctx.raiseWatermark(watermark.getTimestamp());
                    }
                }

                @Override
                public void markIdle() {
                    // RT-M3: the next idle sweep flushes the watermark even without record silence
                    ctx.markIdle();
                }

                @Override
                public void markActive() {
                    ctx.markActive();
                }
            };
            generator.onEvent(v, assigned, output);
            generator.onPeriodicEmit(output);
            emit.emit(v);
        });
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, next, true);
    }

    /**
     * The generator instance this record drives: slot 0 for the single-instance form, the
     * record's own source otherwise, built lazily on first use ({@code AtomicReferenceArray}
     * for cross-worker visibility; a racy double build discards one instance harmlessly).
     */
    private WatermarkGenerator<T> generatorFor(java.util.function.Supplier<WatermarkGenerator<T>> generatorFactory,
                                               java.util.concurrent.atomic.AtomicReferenceArray<WatermarkGenerator<T>> perSource,
                                               boolean shareOneInstance, int sourceIndex) {
        int slot = shareOneInstance ? 0 : sourceIndex;
        WatermarkGenerator<T> generator = perSource.get(slot);
        if (generator == null) {
            perSource.compareAndSet(slot, null, generatorFactory.get());
            generator = perSource.get(slot);
        }
        return generator;
    }

    @Override
    public DataStream<T> print() {
        return print("");
    }

    @Override
    public DataStream<T> print(String prefix) {
        String p = prefix == null ? "" : prefix;
        return addSink(v -> log.info("{}{}", p, v));
    }

    private RedisPipelineDefinition ensureRegistered() {
        if (registeredDefinition != null) {
            return registeredDefinition;
        }
        RedisPipelineDefinition def = sourceTopics.size() == 1
                ? new RedisPipelineDefinition(config, redissonClient, objectMapper, topic, consumerGroup,
                        subscriptionOptions, operators, assignerOwnedEventTime)
                : new RedisPipelineDefinition(config, redissonClient, objectMapper, sourceTopics, sourceConsumerGroups,
                        sourceSubscriptionOptions, operators, assignerOwnedEventTime);
        env.registerPipelineDefinition(def);
        registeredDefinition = def;
        return def;
    }

    @SuppressWarnings("unchecked")
    private T castValue(Object o) {
        return (T) o;
    }

    @SuppressWarnings("unchecked")
    private static <R> DataStream<R> cast(DataStream<?> in) {
        return (DataStream<R>) in;
    }

}
