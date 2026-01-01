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
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final List<RedisOperatorNode> operators;

    private RedisPipelineDefinition registeredDefinition;

    private RedisStreamBuilder(RedisStreamExecutionEnvironment env,
                              RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String streamId,
                              String topic,
                              String consumerGroup,
                              SubscriptionOptions subscriptionOptions,
                              List<RedisOperatorNode> operators) {
        this.env = Objects.requireNonNull(env, "env");
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.streamId = Objects.requireNonNull(streamId, "streamId");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.subscriptionOptions = subscriptionOptions;
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
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, List.of());
    }

    private RedisStreamBuilder<T> withOperator(RedisOperatorNode node) {
        List<RedisOperatorNode> next = new ArrayList<>(operators.size() + 1);
        next.addAll(operators);
        next.add(node);
        return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, next);
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
                config.isStateSchemaEvolutionEnabled(), config.getStateSchemaMismatchPolicy());
        return new RedisKeyedStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
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
        private final String streamId;
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
                                       String streamId,
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
            this.streamId = streamId;
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
            List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
            ops.add((value, ctx, emit) -> {
                V v = castValue(value);
                K key = currentKeyOrCompute(v);
                int partitionId = ctx.currentPartitionId();
                stateStore.setCurrentPartitionId(partitionId);
                stateStore.setCurrentKey(key);
                try {
                    R mapped = mapper.apply(v);
                    emit.emit(mapped);
                } finally {
                    stateStore.clearCurrentKey();
                    stateStore.clearCurrentPartitionId();
                }
            });

            Function<R, K> inheritedKeySelector = r -> {
                K k = stateStore.currentKey();
                if (k == null) {
                    throw new IllegalStateException("No current key is set for keyed stream mapping");
                }
                return k;
            };

            return new RedisKeyedStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                    subscriptionOptions, ops, inheritedKeySelector, stateStore, operatorId);
        }

        @Override
        public <R> DataStream<R> process(KeyedProcessFunction<K, V, R> processFunction) {
            Objects.requireNonNull(processFunction, "processFunction");

            List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
            int processIndex = ops.size();
            ops.add((value, ctx, emit) -> {
                V v = castValue(value);
                K key = keySelector.apply(v);
                int partitionId = ctx.currentPartitionId();
                stateStore.setCurrentPartitionId(partitionId);
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
                                stateStore.setCurrentPartitionId(partitionId);
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
                                    stateStore.clearCurrentPartitionId();
                                }
                            });
                        }

                        @Override
                        public void registerEventTimeTimer(long time) {
                            ctx.registerEventTimeTimer(time, () -> {
                                stateStore.setCurrentPartitionId(partitionId);
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
                                    stateStore.clearCurrentPartitionId();
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
                    stateStore.clearCurrentPartitionId();
                }
            });

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops);
        }

        @Override
        public WindowedStream<K, V> window(WindowAssigner<V> windowAssigner) {
            throw new UnsupportedOperationException("Windowed execution is not supported by Redis runtime yet");
        }

        @Override
        public DataStream<V> reduce(ReduceFunction<V> reducer) {
            Objects.requireNonNull(reducer, "reducer");
            String stateName = "__internal:reduce:" + operatorId + ":" + upstreamOperators.size();

            List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
            ops.add((value, ctx, emit) -> {
                V v = castValue(value);
                K key = currentKeyOrCompute(v);
                int partitionId = ctx.currentPartitionId();
                stateStore.setCurrentPartitionId(partitionId);
                stateStore.setCurrentKey(key);
                try {
                    String field = stateStore.stateFieldForKey(key);
                    RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, field);
                    RMap<String, String> state = ref.map();
                    String json = state.get(field);
                    V current = null;
                    if (json != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Class<V> type = (Class<V>) v.getClass();
                            current = objectMapper.readValue(json, type);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize reduce state", e);
                        }
                    }

                    V reduced;
                    try {
                        reduced = current == null ? v : reducer.reduce(current, v);
                    } catch (Exception e) {
                        throw new RuntimeException("Reduce function failed", e);
                    }

                    if (reduced == null) {
                        state.remove(field);
                    } else {
                        try {
                            state.put(field, objectMapper.writeValueAsString(reduced));
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to serialize reduce state", e);
                        }
                    }
                    stateStore.touch(ref.redisKey(), stateName, state);
                    emit.emit(reduced);
                } finally {
                    stateStore.clearCurrentKey();
                    stateStore.clearCurrentPartitionId();
                }
            });

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops);
        }

        @Override
        public DataStream<V> sum(Function<V, ? extends Number> fieldSelector) {
            Objects.requireNonNull(fieldSelector, "fieldSelector");
            String stateName = "__internal:sum:" + operatorId + ":" + upstreamOperators.size();

            List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
            ops.add((value, ctx, emit) -> {
                V v = castValue(value);
                if (!(v instanceof Number numberValue)) {
                    throw new UnsupportedOperationException(
                            "Redis runtime sum() only supports Number elements, but got: " +
                                    (v == null ? "null" : v.getClass().getName()));
                }

                K key = currentKeyOrCompute(v);
                int partitionId = ctx.currentPartitionId();
                stateStore.setCurrentPartitionId(partitionId);
                stateStore.setCurrentKey(key);
                try {
                    String field = stateStore.stateFieldForKey(key);
                    RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, field);
                    RMap<String, String> state = ref.map();
                    Number current = decodeNumber(state.get(field));
                    Number next = addNumbers(current, fieldSelector.apply(v));
                    state.put(field, encodeNumber(next));
                    stateStore.touch(ref.redisKey(), stateName, state);

                    @SuppressWarnings("unchecked")
                    V out = (V) castToSameNumberType(next, numberValue);
                    emit.emit(out);
                } finally {
                    stateStore.clearCurrentKey();
                    stateStore.clearCurrentPartitionId();
                }
            });

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops);
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

        private K currentKeyOrCompute(V v) {
            K current = stateStore.currentKey();
            if (current != null) {
                return current;
            }
            return keySelector.apply(v);
        }
    }

    private static Number addNumbers(Number a, Number b) {
        if (b == null) {
            return a == null ? 0L : a;
        }
        if (a == null) {
            return b;
        }
        if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
            return a.doubleValue() + b.doubleValue();
        }
        return a.longValue() + b.longValue();
    }

    private static Number castToSameNumberType(Number value, Number sample) {
        if (sample instanceof Integer) return value.intValue();
        if (sample instanceof Long) return value.longValue();
        if (sample instanceof Double) return value.doubleValue();
        if (sample instanceof Float) return value.floatValue();
        if (sample instanceof Short) return value.shortValue();
        if (sample instanceof Byte) return value.byteValue();
        return value;
    }

    private static String encodeNumber(Number number) {
        if (number == null) {
            return "l:0";
        }
        if (number instanceof Double || number instanceof Float) {
            return "d:" + number.doubleValue();
        }
        return "l:" + number.longValue();
    }

    private static Number decodeNumber(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return 0L;
        }
        if (encoded.startsWith("d:")) {
            return Double.parseDouble(encoded.substring(2));
        }
        if (encoded.startsWith("l:")) {
            return Long.parseLong(encoded.substring(2));
        }
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(encoded);
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
    }
}
