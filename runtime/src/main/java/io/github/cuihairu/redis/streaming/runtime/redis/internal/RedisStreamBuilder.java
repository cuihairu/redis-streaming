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
                config.getKeyedStateHotKeyPolicy(), config.getKeyedStateHotKeyThrottleMaxMs(),
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

            /**
             * Registers a windowed operator, encapsulating the shared per-record pipeline:
             * key/partition binding, due-set bookkeeping, late-event dropping and the
             * accumulate-then-fire cycle. Kinds differ only via the two callbacks.
             *
             * @param kind      logical state-name discriminator (reduce/aggregate/apply/sum/count)
             * @param guard     optional per-record pre-check (e.g. sum requires Number)
             * @param accumulator updates the stored member state for a single window
             * @param emitter   decodes the stored member state and emits the fire result
             */
            private <R> DataStream<R> registerWindowedOperator(
                    String kind,
                    WindowGuard<V> guard,
                    WindowAccumulator<V> accumulator,
                    WindowEmitter emitter) {
                String stateName = "__internal:window:" + kind + ":" + operatorId + ":" + upstreamOperators.size();
                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                // Window triggers (todo B3): one trigger instance per (partition, key, window)
                // bucket, obtained fresh from WindowAssigner#getDefaultTrigger on first sight
                // (per its contract). The registry lives per registered operator for as long
                // as the buckets are due; entries are dropped when their bucket leaves the
                // due set. After a restart the registry starts empty, so due windows fire by
                // the stock close-time semantics (a stateful custom trigger loses its counts).
                Map<String, WindowAssigner.Trigger<V>> bucketTriggers = new java.util.concurrent.ConcurrentHashMap<>();
                // RT-M3: partitions that have windowed records on this operator; the idle
                // flush hook sweeps each one's due set.
                java.util.Set<Integer> knownPartitions = java.util.concurrent.ConcurrentHashMap.newKeySet();
                java.util.concurrent.atomic.AtomicBoolean idleFlushHookRegistered = new java.util.concurrent.atomic.AtomicBoolean(false);
                ops.add((value, ctx, emit) -> {
                    V v = castValue(value);
                    if (guard != null) {
                        guard.check(v);
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
                        WindowFireHandler fireHandler = (member, windowStart, windowEnd, purgeState) -> {
                            RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                            emitter.emit(ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState);
                        };

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
                            accumulator.accumulate(ref, stateName, member, v, due, closeTime);
                            stateStore.touch(ref.redisKey(), stateName, ref.map());

                            // Window trigger wiring (todo B3): consult the bucket's trigger on
                            // every element. The stock EventTimeTrigger answers CONTINUE here,
                            // which is exactly the pre-wiring behavior.
                            String triggerKey = partitionId + D + member;
                            WindowAssigner.Trigger<V> trigger = bucketTriggers.computeIfAbsent(
                                    triggerKey, k -> assigner.getDefaultTrigger());
                            WindowAssigner.TriggerResult tr = trigger.onElement(v, eventTimeMs, w);
                            if (tr == WindowAssigner.TriggerResult.FIRE) {
                                // partial fire: emit, keep accumulating, stay due
                                fireHandler.fire(member, w.getStart(), w.getEnd(), false);
                            } else if (tr == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                                fireHandler.fire(member, w.getStart(), w.getEnd(), true);
                                due.remove(member);
                                bucketTriggers.remove(triggerKey);
                            } else if (tr == WindowAssigner.TriggerResult.PURGE) {
                                purgeWindowState(stateName, member);
                                due.remove(member);
                                bucketTriggers.remove(triggerKey);
                            }
                            // CONTINUE: keep accumulating
                        }

                        // RT-M3: with watermarkIdleTimeout configured, register (once) an
                        // idle-flush hook that drains this operator's due sets across all
                        // known partitions — a quiet pipeline then still fires its remaining
                        // windows. The record path itself stays the sole owner of the due
                        // set while records flow, so trigger semantics (e.g. defer-once
                        // close triggers) and the per-record fire clamp are untouched.
                        knownPartitions.add(partitionId);
                        if (ctx.idleFlushEnabled() && idleFlushHookRegistered.compareAndSet(false, true)) {
                            WindowFireHandler flushHandler = fireHandler;
                            ctx.addIdleFlushHook(() -> {
                                long flushWatermark = ctx.currentWatermark();
                                for (Integer pid : knownPartitions) {
                                    stateStore.setCurrentPartitionId(pid);
                                    try {
                                        RScoredSortedSet<String> flushDue = redissonClient.getScoredSortedSet(
                                                windowDueKey(pid, stateName), StringCodec.INSTANCE);
                                        fireDueWindows(flushDue, flushWatermark, pid, stateName,
                                                bucketTriggers, flushHandler, Integer.MAX_VALUE);
                                    } catch (Exception e) {
                                        log.warn("Idle flush window drain failed (jobName={}, topic={}, group={}, op={}, p={})",
                                                config.getJobName(), topic, consumerGroup, stateName, pid, e);
                                    } finally {
                                        stateStore.clearCurrentPartitionId();
                                    }
                                }
                            });
                        }
                        fireDueWindows(due, watermark, partitionId, stateName,
                                bucketTriggers, fireHandler, Math.max(1, config.getWindowMaxFiresPerRecord()));
                    } finally {
                        stateStore.clearCurrentKey();
                        stateStore.clearCurrentPartitionId();
                    }
                });
                return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions, ops);
            }

            @FunctionalInterface
            private interface WindowGuard<X> {
                void check(X value);
            }

            @FunctionalInterface
            private interface WindowAccumulator<X> {
                void accumulate(RedisKeyedStateStore.StateMapRef ref, String stateName, String member, X value,
                                RScoredSortedSet<String> due, long closeTime) throws Exception;
            }

            @FunctionalInterface
            private interface WindowEmitter {
                /**
                 * Emits the fire result of one window member. With {@code purgeState} the
                 * member state is removed after emitting (final close fire / early
                 * FIRE_AND_PURGE); without it the member keeps accumulating (a trigger's
                 * early {@code FIRE}, which must not end the window).
                 */
                void emit(RedisKeyedStateStore.StateMapRef ref, String stateName, String member,
                          long windowStart, long windowEnd, int partitionId,
                          RedisPipelineRunner.Emitter emit, boolean purgeState) throws Exception;
            }

            /** Fires one window member: decodes its bounds and delegates to the emitter. */
            @FunctionalInterface
            private interface WindowFireHandler {
                void fire(String member, long windowStart, long windowEnd, boolean purgeState) throws Exception;
            }

            @Override
            public DataStream<V> reduce(ReduceFunction<V> reducer) {
                Objects.requireNonNull(reducer, "reducer");
                return registerWindowedOperator("reduce", null,
                        (ref, stateName, member, v, due, closeTime) -> {
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
                        },
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
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
                                // RT-H3: the emit failed — keep the accumulated window state so
                                // the redelivered message re-accumulates onto a complete window
                                // instead of a purged one; purge only after a successful emit.
                                throw new RuntimeException("Failed to emit window reduce result", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            @Override
            public <R> DataStream<R> aggregate(AggregateFunction<V, R> aggregateFunction) {
                Objects.requireNonNull(aggregateFunction, "aggregateFunction");
                @SuppressWarnings("unchecked")
                Class<? extends AggregateFunction.Accumulator<V>> accClass =
                        (Class<? extends AggregateFunction.Accumulator<V>>) aggregateFunction.createAccumulator().getClass();
                return registerWindowedOperator("aggregate", null,
                        (ref, stateName, member, v, due, closeTime) -> {
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
                        },
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
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
                                // RT-H3: the emit failed — keep the accumulated window state so
                                // the redelivered message re-accumulates onto a complete window
                                // instead of a purged one; purge only after a successful emit.
                                throw new RuntimeException("Failed to emit window aggregate result", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            @Override
            public <R> DataStream<R> apply(WindowFunction<K, V, R> windowFunction) {
                Objects.requireNonNull(windowFunction, "windowFunction");
                return registerWindowedOperator("apply", null,
                        (ref, stateName, member, v, due, closeTime) -> {
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
                        },
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
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
                            }
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            try {
                                while (!buffer.isEmpty()) {
                                    emit.emit(buffer.removeFirst());
                                }
                            } catch (Exception e) {
                                // RT-H3: a failed emit mid-drain used to leave the window state
                                // already purged (the purge ran in a finally before the buffered
                                // results were emitted), so the redelivered message re-fired a
                                // truncated window. Keep the state and purge only after every
                                // buffered result was delivered; redelivery may duplicate results
                                // (at-least-once) but never silently drops them.
                                throw new RuntimeException("Failed to emit window apply results", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            @Override
            public DataStream<V> sum(Function<V, ? extends Number> fieldSelector) {
                Objects.requireNonNull(fieldSelector, "fieldSelector");
                return registerWindowedOperator("sum",
                        v -> {
                            if (!(v instanceof Number)) {
                                throw new UnsupportedOperationException(
                                        "Redis runtime window sum() only supports Number elements, but got: "
                                                + (v == null ? "null" : v.getClass().getName()));
                            }
                        },
                        (ref, stateName, member, v, due, closeTime) -> {
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
                            Number next = io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.add(current, fieldSelector.apply(v));
                            cur.put("sum", next);
                            cur.put("sample", ((Number) v).getClass().getName());
                            try {
                                state.put(member, objectMapper.writeValueAsString(cur));
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to serialize window sum state", e);
                            }
                            due.add(closeTime, member);
                        },
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
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
                                Number outNumber = io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.castToSameType(sum, sampleName);
                                @SuppressWarnings("unchecked")
                                V out = (V) outNumber;
                                try {
                                    RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                                } catch (Exception ignore) {
                                }
                                emit.emit(out);
                            } catch (Exception e) {
                                // RT-H3: the emit failed — keep the accumulated window state so
                                // the redelivered message re-accumulates onto a complete window
                                // instead of a purged one; purge only after a successful emit.
                                throw new RuntimeException("Failed to emit window sum result", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            @Override
            public DataStream<Long> count() {
                return registerWindowedOperator("count", null,
                        (ref, stateName, member, v, due, closeTime) -> {
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
                        },
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
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
                                // RT-H3: the emit failed — keep the accumulated window state so
                                // the redelivered message re-accumulates onto a complete window
                                // instead of a purged one; purge only after a successful emit.
                                throw new RuntimeException("Failed to emit window count result", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            private String windowMember(String keyField, long start, long end) {
                // RT-L7: the key field is escaped under an "e:" marker so a key containing
                // the D separator cannot shift the start/end fields in parseWindow. Unmarked
                // members are legacy state (keys without D parsed fine before) and are kept
                // as-is instead of being run through the unescaper.
                return "e:" + escapeMemberKey(keyField == null ? "" : keyField) + D + start + D + end;
            }

            private static String escapeMemberKey(String keyField) {
                return keyField.replace("\\", "\\\\").replace(D, "\\1");
            }

            private static String unescapeMemberKey(String encoded) {
                if (!encoded.startsWith("e:")) {
                    return encoded; // legacy member written before escaping existed
                }
                String s = encoded.substring(2);
                if (s.indexOf('\\') < 0) {
                    return s;
                }
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < s.length()) {
                        char next = s.charAt(++i);
                        sb.append(next == '1' ? D : next);
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private String windowDueKey(int partitionId, String stateName) {
                return config.getStateKeyPrefix() + ":" + config.getJobName()
                        + ":cg:" + consumerGroup
                        + ":topic:" + topic
                        + ":p:" + partitionId
                        + ":windowDue:" + operatorId + ":" + stateName;
            }

            /**
             * Drains up to {@code maxFires} due windows whose close score is within the
             * watermark. Returns when the set is empty, the earliest member closes in the
             * future, or a bucket trigger answers CONTINUE (its explicit choice to keep
             * that window open; the next drain re-consults it).
             */
            private void fireDueWindows(RScoredSortedSet<String> due,
                                        long watermark,
                                        int partitionId,
                                        String stateName,
                                        Map<String, WindowAssigner.Trigger<V>> bucketTriggers,
                                        WindowFireHandler handler,
                                        int maxFires) throws Exception {
                for (int i = 0; i < maxFires; i++) {
                    org.redisson.client.protocol.ScoredEntry<String> first = due.firstEntry();
                    if (first == null || first.getScore() > watermark) {
                        return;
                    }
                    org.redisson.client.protocol.ScoredEntry<String> entry = due.pollFirstEntry();
                    if (entry == null) {
                        return; // raced with a concurrent drain
                    }
                    if (entry.getValue() == null || entry.getValue().isBlank()) {
                        continue; // vanished/raced member — drop it, never fire a garbage window
                    }
                    ParsedWindow pw = parseWindow(entry.getValue());
                    // Window trigger wiring (todo B3): the bucket's trigger gets the final say
                    // at close. The stock EventTimeTrigger answers FIRE_AND_PURGE (watermark
                    // >= window end is implied by the due score) — exactly the pre-wiring
                    // behavior. CONTINUE re-queues the member (earliest due score) for a
                    // later record; PURGE drops it silently; FIRE fires like FIRE_AND_PURGE
                    // since the window is closing anyway.
                    String triggerKey = partitionId + D + entry.getValue();
                    WindowAssigner.Trigger<V> trigger = bucketTriggers.computeIfAbsent(
                            triggerKey, k -> assigner.getDefaultTrigger());
                    WindowAssigner.TriggerResult result = trigger.onEventTime(watermark,
                            new io.github.cuihairu.redis.streaming.window.TimeWindow(pw.start, pw.end));
                    if (result == WindowAssigner.TriggerResult.CONTINUE) {
                        due.add(entry.getScore(), entry.getValue());
                        return;
                    }
                    if (result == WindowAssigner.TriggerResult.PURGE) {
                        purgeWindowState(stateName, entry.getValue());
                        bucketTriggers.remove(triggerKey);
                        continue;
                    }
                    try {
                        handler.fire(entry.getValue(), pw.start, pw.end, true);
                    } catch (Exception e) {
                        // RT-H3: the member was already polled off the due set; re-queue it at
                        // its original close score so the emission is retried by the next fire
                        // sweep. The emitter kept the window state (purge only happens after a
                        // successful emit), so the retry fires the complete window.
                        due.add(entry.getScore(), entry.getValue());
                        throw e;
                    }
                    bucketTriggers.remove(triggerKey);
                }
            }

            /** Clears one window member's accumulated state without emitting. */
            private void purgeWindowState(String stateName, String member) {
                RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                ref.map().remove(member);
                stateStore.touch(ref.redisKey(), stateName, ref.map());
            }

            private ParsedWindow parseWindow(String member) {
                try {
                    String[] parts = member.split(D, 3);
                    if (parts.length == 3) {
                        long start = Long.parseLong(parts[1]);
                        long end = Long.parseLong(parts[2]);
                        // RT-L7: undo the windowMember key escaping
                        return new ParsedWindow(unescapeMemberKey(parts[0]), start, end);
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


            private record ParsedWindow(String keyField, long start, long end) {
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
                    Number next = io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.add(current, fieldSelector.apply(v));
                    state.put(field, encodeNumber(next));
                    stateStore.touch(ref.redisKey(), stateName, state);

                    @SuppressWarnings("unchecked")
                    V out = (V) io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.castToSameType(next, numberValue);
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
