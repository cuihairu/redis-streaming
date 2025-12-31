package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
    private final String topic;
    private final String consumerGroup;
    private final SubscriptionOptions subscriptionOptions;
    private final List<RedisOperatorNode> operators;

    private RedisPipelineDefinition registeredDefinition;

    private RedisStreamBuilder(RedisStreamExecutionEnvironment env,
                              RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String topic,
                              String consumerGroup,
                              SubscriptionOptions subscriptionOptions,
                              List<RedisOperatorNode> operators) {
        this.env = Objects.requireNonNull(env, "env");
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.subscriptionOptions = subscriptionOptions;
        this.operators = List.copyOf(operators);
    }

    public static RedisStreamBuilder<Message> forMqSource(RedisStreamExecutionEnvironment env,
                                                         RedisRuntimeConfig config,
                                                         RedissonClient redissonClient,
                                                         ObjectMapper objectMapper,
                                                         String topic,
                                                         String consumerGroup) {
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, topic, consumerGroup, null, List.of());
    }

    private RedisStreamBuilder<T> withOperator(RedisOperatorNode node) {
        List<RedisOperatorNode> next = new ArrayList<>(operators.size() + 1);
        next.addAll(operators);
        next.add(node);
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, next);
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
        String operatorId = "keyBy-" + UUID.randomUUID().toString().substring(0, 8);
        RedisKeyedStateStore<K> store = new RedisKeyedStateStore<>(redissonClient, objectMapper,
                config.getStateKeyPrefix(), config.getJobName(), operatorId);
        return new RedisKeyedStreamBuilder<>(env, config, redissonClient, objectMapper, topic, consumerGroup,
                subscriptionOptions, operators, keySelector, store, operatorId);
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
        RedisPipelineDefinition def = new RedisPipelineDefinition(
                config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, operators);
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

    private static final class RedisKeyedStreamBuilder<K, V> implements KeyedStream<K, V> {
        private final RedisStreamExecutionEnvironment env;
        private final RedisRuntimeConfig config;
        private final RedissonClient redissonClient;
        private final ObjectMapper objectMapper;
        private final String topic;
        private final String consumerGroup;
        private final SubscriptionOptions subscriptionOptions;
        private final List<RedisOperatorNode> upstreamOperators;
        private final Function<V, K> keySelector;
        private final RedisKeyedStateStore<K> stateStore;
        private final String operatorId;

        private RedisKeyedStreamBuilder(RedisStreamExecutionEnvironment env,
                                       RedisRuntimeConfig config,
                                       RedissonClient redissonClient,
                                       ObjectMapper objectMapper,
                                       String topic,
                                       String consumerGroup,
                                       SubscriptionOptions subscriptionOptions,
                                       List<RedisOperatorNode> upstreamOperators,
                                       Function<V, K> keySelector,
                                       RedisKeyedStateStore<K> stateStore,
                                       String operatorId) {
            this.env = env;
            this.config = config;
            this.redissonClient = redissonClient;
            this.objectMapper = objectMapper;
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.subscriptionOptions = subscriptionOptions;
            this.upstreamOperators = upstreamOperators;
            this.keySelector = keySelector;
            this.stateStore = stateStore;
            this.operatorId = operatorId;
        }

        @Override
        public <R> KeyedStream<K, R> map(Function<V, R> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            Function<R, K> inheritedKey = r -> {
                // keep original key assignment independent of mapping (like Flink keyed stream)
                throw new UnsupportedOperationException("KeyedStream.map() is not supported in Redis runtime yet");
            };
            // NOTE: For now, keep Redis runtime keyed semantics focused on process/reduce/sum.
            throw new UnsupportedOperationException("KeyedStream.map() is not supported in Redis runtime yet");
        }

        @Override
        public <R> DataStream<R> process(KeyedProcessFunction<K, V, R> processFunction) {
            Objects.requireNonNull(processFunction, "processFunction");

            List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
            int processIndex = ops.size();
            ops.add((value, ctx, emit) -> {
                V v = castValue(value);
                K key = keySelector.apply(v);
                stateStore.setCurrentKey(key);
                try {
                    KeyedProcessFunction.Context kctx = new KeyedProcessFunction.Context() {
                        @Override
                        public long currentProcessingTime() {
                            return ctx.currentProcessingTime();
                        }

                        @Override
                        public long currentWatermark() {
                            return ctx.currentWatermark();
                        }

                        @Override
                        public void registerProcessingTimeTimer(long time) {
                            ctx.registerProcessingTimeTimer(time, () -> {
                                stateStore.setCurrentKey(key);
                                try {
                                    processFunction.onProcessingTime(time, key, this,
                                            out -> {
                                                try {
                                                    ctx.emitFrom(processIndex + 1, out);
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    stateStore.clearCurrentKey();
                                }
                            });
                        }

                        @Override
                        public void registerEventTimeTimer(long time) {
                            ctx.registerEventTimeTimer(time, () -> {
                                stateStore.setCurrentKey(key);
                                try {
                                    processFunction.onEventTime(time, key, this,
                                            out -> {
                                                try {
                                                    ctx.emitFrom(processIndex + 1, out);
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    stateStore.clearCurrentKey();
                                }
                            });
                        }
                    };

                    processFunction.processElement(key, v, kctx, out -> {
                        try {
                            emit.emit(out);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } finally {
                    stateStore.clearCurrentKey();
                }
            });

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, topic, consumerGroup, subscriptionOptions, ops);
        }

        @Override
        public WindowedStream<K, V> window(WindowAssigner<V> windowAssigner) {
            throw new UnsupportedOperationException("Windowed execution is not supported by Redis runtime yet");
        }

        @Override
        public DataStream<V> reduce(ReduceFunction<V> reducer) {
            throw new UnsupportedOperationException("Keyed reduce is not supported by Redis runtime yet");
        }

        @Override
        public DataStream<V> sum(Function<V, ? extends Number> fieldSelector) {
            throw new UnsupportedOperationException("Keyed sum is not supported by Redis runtime yet");
        }

        @Override
        public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
            Objects.requireNonNull(stateDescriptor, "stateDescriptor");
            return stateStore.getValueState(stateDescriptor);
        }

        @SuppressWarnings("unchecked")
        private V castValue(Object o) {
            return (V) o;
        }
    }
}
