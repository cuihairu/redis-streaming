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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
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
            Objects.requireNonNull(windowAssigner, "windowAssigner");
            return new RedisWindowedStreamImpl(windowAssigner);
        }

        private final class RedisWindowedStreamImpl implements WindowedStream<K, V> {
            private static final String D = "\u0001";

            private final WindowAssigner<V> assigner;
            private final long allowedLatenessMs;
            private final AtomicReference<Class<?>> keyClassRef = new AtomicReference<>();
            private final AtomicReference<Class<?>> valueClassRef = new AtomicReference<>();

            private RedisWindowedStreamImpl(WindowAssigner<V> assigner) {
                this.assigner = assigner;
                long ms = 0L;
                try {
                    if (config.getWindowAllowedLateness() != null) {
                        ms = Math.max(0L, config.getWindowAllowedLateness().toMillis());
                    }
                } catch (Exception ignore) {
                }
                this.allowedLatenessMs = ms;
            }

            private long windowCloseTime(long windowEndMs) {
                long close = windowEndMs + allowedLatenessMs;
                if (allowedLatenessMs > 0 && close < windowEndMs) {
                    return Long.MAX_VALUE;
                }
                return close;
            }

            @Override
            public DataStream<V> reduce(ReduceFunction<V> reducer) {
                Objects.requireNonNull(reducer, "reducer");
                String stateName = "__internal:window:reduce:" + operatorId + ":" + upstreamOperators.size();

                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    K key = currentKeyOrCompute(v);
                    int partitionId = ctx.currentPartitionId();
                    long eventTimeMs = ctx.currentEventTime();
                    if (key != null) keyClassRef.compareAndSet(null, key.getClass());
                    if (v != null) valueClassRef.compareAndSet(null, v.getClass());

                    stateStore.setCurrentPartitionId(partitionId);
                    stateStore.setCurrentKey(key);
                    try {
                        String keyField = stateStore.stateFieldForKey(key);
                        String dueKey = windowDueKey(partitionId, stateName);
                        stateStore.registerStateKey(dueKey);
                        RScoredSortedSet<String> due = redissonClient.getScoredSortedSet(dueKey, StringCodec.INSTANCE);
                        long watermark = ctx.currentWatermark();

                        for (WindowAssigner.Window w : assigner.assignWindows(v, eventTimeMs)) {
                            if (w == null) continue;
                            long closeTime = windowCloseTime(w.getEnd());
                            if (watermark >= closeTime) {
                                try {
                                    RedisRuntimeMetrics.get().incWindowLateDropped(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                continue;
                            }
                            String member = windowMember(keyField, w.getStart(), w.getEnd());
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            V current = null;
                            if (json != null) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Class<V> type = (Class<V>) v.getClass();
                                    current = objectMapper.readValue(json, type);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window reduce state", e);
                                }
                            }
                            V reduced;
                            try {
                                reduced = current == null ? v : reducer.reduce(current, v);
                            } catch (Exception e) {
                                throw new RuntimeException("Window reduce function failed", e);
                            }
                            if (reduced == null) {
                                state.remove(member);
                                due.remove(member);
                            } else {
                                try {
                                    state.put(member, objectMapper.writeValueAsString(reduced));
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to serialize window reduce state", e);
                                }
                                due.add(closeTime, member);
                            }
                            stateStore.touch(ref.redisKey(), stateName, state);
                        }

                        fireDueWindows(due, watermark, (member, windowStart, windowEnd) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null) {
                                return;
                            }
                            try {
                                @SuppressWarnings("unchecked")
                                Class<V> type = (Class<V>) valueClassRef.get();
                                if (type == null) {
                                    return;
                                }
                                V out = objectMapper.readValue(json, type);
                                try {
                                    RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                emit.emit(out);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to emit window reduce result", e);
                            } finally {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
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
            public <R> DataStream<R> aggregate(AggregateFunction<V, R> aggregateFunction) {
                Objects.requireNonNull(aggregateFunction, "aggregateFunction");
                @SuppressWarnings("unchecked")
                Class<? extends AggregateFunction.Accumulator<V>> accClass =
                        (Class<? extends AggregateFunction.Accumulator<V>>) aggregateFunction.createAccumulator().getClass();
                String stateName = "__internal:window:aggregate:" + operatorId + ":" + upstreamOperators.size();

                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    K key = currentKeyOrCompute(v);
                    int partitionId = ctx.currentPartitionId();
                    long eventTimeMs = ctx.currentEventTime();
                    if (key != null) keyClassRef.compareAndSet(null, key.getClass());
                    if (v != null) valueClassRef.compareAndSet(null, v.getClass());

                    stateStore.setCurrentPartitionId(partitionId);
                    stateStore.setCurrentKey(key);
                    try {
                        String keyField = stateStore.stateFieldForKey(key);
                        String dueKey = windowDueKey(partitionId, stateName);
                        stateStore.registerStateKey(dueKey);
                        RScoredSortedSet<String> due = redissonClient.getScoredSortedSet(dueKey, StringCodec.INSTANCE);
                        long watermark = ctx.currentWatermark();

                        for (WindowAssigner.Window w : assigner.assignWindows(v, eventTimeMs)) {
                            if (w == null) continue;
                            long closeTime = windowCloseTime(w.getEnd());
                            if (watermark >= closeTime) {
                                try {
                                    RedisRuntimeMetrics.get().incWindowLateDropped(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                continue;
                            }
                            String member = windowMember(keyField, w.getStart(), w.getEnd());
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            AggregateFunction.Accumulator<V> acc;
                            String json = state.get(member);
                            if (json == null) {
                                acc = aggregateFunction.createAccumulator();
                            } else {
                                try {
                                    acc = objectMapper.readValue(json, accClass);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window accumulator", e);
                                }
                            }
                            try {
                                acc = aggregateFunction.add(v, acc);
                            } catch (Exception e) {
                                throw new RuntimeException("Window aggregate add failed", e);
                            }
                            try {
                                state.put(member, objectMapper.writeValueAsString(acc));
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to serialize window accumulator", e);
                            }
                            due.add(closeTime, member);
                            stateStore.touch(ref.redisKey(), stateName, state);
                        }

                        fireDueWindows(due, watermark, (member, windowStart, windowEnd) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null) {
                                return;
                            }
                            try {
                                AggregateFunction.Accumulator<V> acc = objectMapper.readValue(json, accClass);
                                R out = aggregateFunction.getResult(acc);
                                try {
                                    RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                emit.emit(out);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to emit window aggregate result", e);
                            } finally {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
                    } finally {
                        stateStore.clearCurrentKey();
                        stateStore.clearCurrentPartitionId();
                    }
                });

                return cast(new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops));
            }

            @Override
            public <R> DataStream<R> apply(WindowFunction<K, V, R> windowFunction) {
                Objects.requireNonNull(windowFunction, "windowFunction");
                String stateName = "__internal:window:apply:" + operatorId + ":" + upstreamOperators.size();

                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    K key = currentKeyOrCompute(v);
                    int partitionId = ctx.currentPartitionId();
                    long eventTimeMs = ctx.currentEventTime();
                    if (key != null) keyClassRef.compareAndSet(null, key.getClass());
                    if (v != null) valueClassRef.compareAndSet(null, v.getClass());

                    stateStore.setCurrentPartitionId(partitionId);
                    stateStore.setCurrentKey(key);
                    try {
                        String keyField = stateStore.stateFieldForKey(key);
                        String dueKey = windowDueKey(partitionId, stateName);
                        stateStore.registerStateKey(dueKey);
                        RScoredSortedSet<String> due = redissonClient.getScoredSortedSet(dueKey, StringCodec.INSTANCE);
                        long watermark = ctx.currentWatermark();

                        for (WindowAssigner.Window w : assigner.assignWindows(v, eventTimeMs)) {
                            if (w == null) continue;
                            long closeTime = windowCloseTime(w.getEnd());
                            if (watermark >= closeTime) {
                                try {
                                    RedisRuntimeMetrics.get().incWindowLateDropped(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                continue;
                            }
                            String member = windowMember(keyField, w.getStart(), w.getEnd());
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            List<String> items = new ArrayList<>();
                            String cur = state.get(member);
                            if (cur != null && !cur.isBlank()) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    List<String> parsed = objectMapper.readValue(cur, List.class);
                                    if (parsed != null) {
                                        items.addAll(parsed);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window elements", e);
                                }
                            }
                            try {
                                items.add(objectMapper.writeValueAsString(v));
                                state.put(member, objectMapper.writeValueAsString(items));
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to serialize window elements", e);
                            }
                            due.add(closeTime, member);
                            stateStore.touch(ref.redisKey(), stateName, state);
                        }

                        fireDueWindows(due, watermark, (member, windowStart, windowEnd) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            Class<?> valueClass = valueClassRef.get();
                            if (valueClass == null) {
                                return;
                            }
                            List<String> items;
                            try {
                                @SuppressWarnings("unchecked")
                                List<String> parsed = objectMapper.readValue(json, List.class);
                                items = parsed == null ? List.of() : parsed;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to deserialize window elements", e);
                            }
                            List<V> values = new ArrayList<>(items.size());
                            for (String item : items) {
                                if (item == null) continue;
                                try {
                                    @SuppressWarnings("unchecked")
                                    V vv = (V) objectMapper.readValue(item, valueClass);
                                    values.add(vv);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window element", e);
                                }
                            }
                            K k = decodeKey(member);
                            WindowAssigner.Window w = new io.github.cuihairu.redis.streaming.window.TimeWindow(windowStart, windowEnd);
                            ArrayDeque<R> buffer = new ArrayDeque<>();
                            WindowFunction.Collector<R> collector = buffer::addLast;
                            try {
                                windowFunction.apply(k, w, values, collector);
                            } catch (Exception e) {
                                throw new RuntimeException("Window function failed", e);
                            } finally {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            while (!buffer.isEmpty()) {
                                emit.emit(buffer.removeFirst());
                            }
                        });
                    } finally {
                        stateStore.clearCurrentKey();
                        stateStore.clearCurrentPartitionId();
                    }
                });

                return cast(new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops));
            }

            @Override
            public DataStream<V> sum(Function<V, ? extends Number> fieldSelector) {
                Objects.requireNonNull(fieldSelector, "fieldSelector");
                String stateName = "__internal:window:sum:" + operatorId + ":" + upstreamOperators.size();

                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    if (!(v instanceof Number numberValue)) {
                        throw new UnsupportedOperationException(
                                "Redis runtime window sum() only supports Number elements, but got: " +
                                        (v == null ? "null" : v.getClass().getName()));
                    }
                    K key = currentKeyOrCompute(v);
                    int partitionId = ctx.currentPartitionId();
                    long eventTimeMs = ctx.currentEventTime();
                    if (key != null) keyClassRef.compareAndSet(null, key.getClass());
                    if (v != null) valueClassRef.compareAndSet(null, v.getClass());

                    stateStore.setCurrentPartitionId(partitionId);
                    stateStore.setCurrentKey(key);
                    try {
                        String keyField = stateStore.stateFieldForKey(key);
                        String dueKey = windowDueKey(partitionId, stateName);
                        stateStore.registerStateKey(dueKey);
                        RScoredSortedSet<String> due = redissonClient.getScoredSortedSet(dueKey, StringCodec.INSTANCE);
                        long watermark = ctx.currentWatermark();

                        for (WindowAssigner.Window w : assigner.assignWindows(v, eventTimeMs)) {
                            if (w == null) continue;
                            long closeTime = windowCloseTime(w.getEnd());
                            if (watermark >= closeTime) {
                                try {
                                    RedisRuntimeMetrics.get().incWindowLateDropped(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                continue;
                            }
                            String member = windowMember(keyField, w.getStart(), w.getEnd());
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            Map<String, Object> cur = new HashMap<>();
                            String json = state.get(member);
                            if (json != null && !json.isBlank()) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                                    if (parsed != null) {
                                        cur.putAll(parsed);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window sum state", e);
                                }
                            }
                            Number current = decodeNumber(cur.get("sum"));
                            Number next = addNumbers(current, fieldSelector.apply(v));
                            cur.put("sum", next);
                            cur.put("sample", numberValue.getClass().getName());
                            try {
                                state.put(member, objectMapper.writeValueAsString(cur));
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to serialize window sum state", e);
                            }
                            due.add(closeTime, member);
                            stateStore.touch(ref.redisKey(), stateName, state);
                        }

                        fireDueWindows(due, watermark, (member, windowStart, windowEnd) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> cur = objectMapper.readValue(json, Map.class);
                                Number sum = decodeNumber(cur == null ? null : cur.get("sum"));
                                String sampleName = cur == null ? null : String.valueOf(cur.get("sample"));
                                Number outNumber = castToSameNumberType(sum, sampleName);
                                @SuppressWarnings("unchecked")
                                V out = (V) outNumber;
                                try {
                                    RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                emit.emit(out);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to emit window sum result", e);
                            } finally {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
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
            public DataStream<Long> count() {
                String stateName = "__internal:window:count:" + operatorId + ":" + upstreamOperators.size();

                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    K key = currentKeyOrCompute(v);
                    int partitionId = ctx.currentPartitionId();
                    long eventTimeMs = ctx.currentEventTime();
                    if (key != null) keyClassRef.compareAndSet(null, key.getClass());
                    if (v != null) valueClassRef.compareAndSet(null, v.getClass());

                    stateStore.setCurrentPartitionId(partitionId);
                    stateStore.setCurrentKey(key);
                    try {
                        String keyField = stateStore.stateFieldForKey(key);
                        String dueKey = windowDueKey(partitionId, stateName);
                        stateStore.registerStateKey(dueKey);
                        RScoredSortedSet<String> due = redissonClient.getScoredSortedSet(dueKey, StringCodec.INSTANCE);
                        long watermark = ctx.currentWatermark();

                        for (WindowAssigner.Window w : assigner.assignWindows(v, eventTimeMs)) {
                            if (w == null) continue;
                            long closeTime = windowCloseTime(w.getEnd());
                            if (watermark >= closeTime) {
                                try {
                                    RedisRuntimeMetrics.get().incWindowLateDropped(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                continue;
                            }
                            String member = windowMember(keyField, w.getStart(), w.getEnd());
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            long cur = 0L;
                            String s = state.get(member);
                            if (s != null && !s.isBlank()) {
                                try {
                                    cur = Long.parseLong(s);
                                } catch (Exception ignore) {
                                }
                            }
                            cur++;
                            state.put(member, Long.toString(cur));
                            due.add(closeTime, member);
                            stateStore.touch(ref.redisKey(), stateName, state);
                        }

                        fireDueWindows(due, watermark, (member, windowStart, windowEnd) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            RMap<String, String> state = ref.map();
                            String s = state.get(member);
                            if (s == null || s.isBlank()) {
                                return;
                            }
                            try {
                                try {
                                    RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                emit.emit(Long.parseLong(s));
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to emit window count result", e);
                            } finally {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
                    } finally {
                        stateStore.clearCurrentKey();
                        stateStore.clearCurrentPartitionId();
                    }
                });

                return cast(new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops));
            }

            private String windowMember(String keyField, long start, long end) {
                return (keyField == null ? "" : keyField) + D + start + D + end;
            }

            private String windowDueKey(int partitionId, String stateName) {
                return config.getStateKeyPrefix() + ":" + config.getJobName()
                        + ":cg:" + consumerGroup
                        + ":topic:" + topic
                        + ":p:" + partitionId
                        + ":windowDue:" + operatorId + ":" + stateName;
            }

            private void fireDueWindows(RScoredSortedSet<String> due,
                                        long watermark,
                                        WindowFireHandler handler) throws Exception {
                int max = Math.max(1, config.getWindowMaxFiresPerRecord());
                for (int i = 0; i < max; i++) {
                    org.redisson.client.protocol.ScoredEntry<String> first = due.firstEntry();
                    if (first == null) {
                        return;
                    }
                    if (first.getScore() > watermark) {
                        return;
                    }
                    org.redisson.client.protocol.ScoredEntry<String> entry = due.pollFirstEntry();
                    if (entry == null || entry.getValue() == null) {
                        return;
                    }
                    ParsedWindow pw = parseWindow(entry.getValue());
                    handler.fire(entry.getValue(), pw.start, pw.end);
                }
            }

            private ParsedWindow parseWindow(String member) {
                try {
                    String[] parts = member.split(D, 3);
                    if (parts.length == 3) {
                        long start = Long.parseLong(parts[1]);
                        long end = Long.parseLong(parts[2]);
                        return new ParsedWindow(parts[0], start, end);
                    }
                } catch (Exception ignore) {
                }
                return new ParsedWindow(member, 0L, 0L);
            }

            @SuppressWarnings("unchecked")
            private K decodeKey(String member) {
                ParsedWindow pw = parseWindow(member);
                String keyField = pw.keyField;
                if (keyField == null) {
                    return null;
                }
                if (keyField.startsWith("s:")) {
                    return (K) keyField.substring(2);
                }
                if (keyField.startsWith("n:")) {
                    String n = keyField.substring(2);
                    try {
                        if (n.contains(".")) {
                            return (K) Double.valueOf(n);
                        }
                        return (K) Long.valueOf(n);
                    } catch (Exception ignore) {
                        return (K) n;
                    }
                }
                if (keyField.startsWith("j:")) {
                    String json = keyField.substring(2);
                    try {
                        Class<?> cls = keyClassRef.get();
                        if (cls != null) {
                            return (K) objectMapper.readValue(json, cls);
                        }
                        return (K) objectMapper.readValue(json, Map.class);
                    } catch (Exception ignore) {
                        return (K) json;
                    }
                }
                if (keyField.startsWith("t:")) {
                    return (K) keyField.substring(2);
                }
                return (K) keyField;
            }

            private Number decodeNumber(Object v) {
                if (v == null) {
                    return 0L;
                }
                if (v instanceof Number n) {
                    return n;
                }
                try {
                    String s = String.valueOf(v);
                    if (s.contains(".")) {
                        return Double.parseDouble(s);
                    }
                    return Long.parseLong(s);
                } catch (Exception ignore) {
                    return 0L;
                }
            }

            private Number addNumbers(Number a, Number b) {
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

            private Number castToSameNumberType(Number value, Number sample) {
                if (sample == null) {
                    return value;
                }
                if (sample instanceof Integer) {
                    return value.intValue();
                }
                if (sample instanceof Long) {
                    return value.longValue();
                }
                if (sample instanceof Double) {
                    return value.doubleValue();
                }
                if (sample instanceof Float) {
                    return value.floatValue();
                }
                if (sample instanceof Short) {
                    return value.shortValue();
                }
                if (sample instanceof Byte) {
                    return value.byteValue();
                }
                return value;
            }

            private Number castToSameNumberType(Number value, String sampleClassName) {
                if (sampleClassName == null || sampleClassName.isBlank()) {
                    return value;
                }
                return switch (sampleClassName) {
                    case "java.lang.Integer" -> value.intValue();
                    case "java.lang.Long" -> value.longValue();
                    case "java.lang.Double" -> value.doubleValue();
                    case "java.lang.Float" -> value.floatValue();
                    case "java.lang.Short" -> value.shortValue();
                    case "java.lang.Byte" -> value.byteValue();
                    default -> value;
                };
            }

            private record ParsedWindow(String keyField, long start, long end) {
            }

            @FunctionalInterface
            private interface WindowFireHandler {
                void fire(String member, long windowStart, long windowEnd) throws Exception;
            }
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
