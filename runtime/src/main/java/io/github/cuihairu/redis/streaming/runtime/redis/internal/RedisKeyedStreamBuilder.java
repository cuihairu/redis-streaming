package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Immutable stream builder that compiles into a {@link RedisPipelineDefinition} when the first sink is attached.
 */

    final class RedisKeyedStreamBuilder<K, V> implements KeyedStream<K, V> {
        private static final Logger log = LoggerFactory.getLogger(RedisKeyedStreamBuilder.class);
        private final RedisStreamExecutionEnvironment env;
        private final RedisRuntimeConfig config;
        private final RedissonClient redissonClient;
        private final ObjectMapper objectMapper;
        private final String streamId;
        private final String topic;
        private final String consumerGroup;
        private final SubscriptionOptions subscriptionOptions;
        private final List<String> sourceTopics;
        private final List<String> sourceConsumerGroups;
        private final List<SubscriptionOptions> sourceSubscriptionOptions;
        private final List<RedisOperatorNode> upstreamOperators;
        private final Function<V, K> keySelector;
        private final RedisKeyedStateStore<K> stateStore;
        private final String operatorId;
        private final boolean assignerOwnedEventTime;

        RedisKeyedStreamBuilder(RedisStreamExecutionEnvironment env,
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
                                       List<RedisOperatorNode> upstreamOperators,
                                       Function<V, K> keySelector,
                                       RedisKeyedStateStore<K> stateStore,
                                       String operatorId,
                                       boolean assignerOwnedEventTime) {
            this.assignerOwnedEventTime = assignerOwnedEventTime;
            this.env = env;
            this.config = config;
            this.redissonClient = redissonClient;
            this.objectMapper = objectMapper;
            this.streamId = streamId;
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.subscriptionOptions = subscriptionOptions;
            this.sourceTopics = sourceTopics;
            this.sourceConsumerGroups = sourceConsumerGroups;
            this.sourceSubscriptionOptions = sourceSubscriptionOptions;
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
                    subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions,
                    ops, inheritedKeySelector, stateStore, operatorId, assignerOwnedEventTime);
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
                // one section per key: the record's own read-modify-write of keyed state must not
                // interleave with another record of the same key (see RedisKeyedStateStore#keyFence)
                ReentrantLock fence = stateStore.keyFence(key);
                fence.lock();
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

                        @Override
                        public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
                            // The store's current key is set for the record being processed and
                            // re-set inside timer callbacks, so this handle is per-key in both
                            // paths; values live in the keyed state store behind checkpoints.
                            return stateStore.getValueState(stateDescriptor);
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
                    fence.unlock();
                }
            });

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                    subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, ops, assignerOwnedEventTime);
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
                    WindowEmitter emitter,
                    WindowEmitter rawEmitter) {
                String stateName = "__internal:window:" + kind + ":" + operatorId + ":" + upstreamOperators.size();
                List<RedisOperatorNode> ops = new ArrayList<>(upstreamOperators);
                // Audit P0-1: merging assigners (SessionWindow) must coalesce intersecting
                // window members into the union window, or every element seeds its own
                // [ts, ts+gap) member and fires alone — session semantics silently lost.
                // Mirroring the in-memory engine, merging assigners accumulate raw element
                // lists per member (the incremental state shape cannot be merged for
                // non-associative aggregations) and the kind's raw emitter computes the
                // fire result from that list. rawEmitter is unused for non-merging assigners.
                boolean merging = assigner.supportsWindowMerging();
                if (merging && rawEmitter == null) {
                    throw new IllegalStateException("window kind '" + kind + "' lacks a raw emitter for merging assigners");
                }
                WindowEmitter fireEmitter = merging ? rawEmitter : emitter;
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
                            fireEmitter.emit(ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState);
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
                            String member;
                            WindowAssigner.Window effective = w;
                            if (merging) {
                                // Audit P0-1: coalesce every open member of this key that
                                // intersects the seed into the union window, keeping raw
                                // elements; the element rides along in the union member.
                                member = mergeIntoRawSession(stateName, keyField, due, w, v, bucketTriggers, partitionId);
                                ParsedWindow union = parseWindow(member);
                                effective = new io.github.cuihairu.redis.streaming.window.TimeWindow(union.start, union.end);
                            } else {
                                member = windowMember(keyField, w.getStart(), w.getEnd());
                                RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                                accumulator.accumulate(ref, stateName, member, v, due, closeTime);
                                stateStore.touch(ref.redisKey(), stateName, ref.map());
                            }

                            // Window trigger wiring (todo B3): consult the bucket's trigger on
                            // every element. The stock EventTimeTrigger answers CONTINUE here,
                            // which is exactly the pre-wiring behavior.
                            String triggerKey = partitionId + D + member;
                            WindowAssigner.Trigger<V> trigger = bucketTriggers.computeIfAbsent(
                                    triggerKey, k -> assigner.getDefaultTrigger());
                            WindowAssigner.TriggerResult tr = trigger.onElement(v, eventTimeMs, effective);
                            if (tr == WindowAssigner.TriggerResult.FIRE) {
                                // partial fire: emit, keep accumulating, stay due
                                fireHandler.fire(member, effective.getStart(), effective.getEnd(), false);
                            } else if (tr == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                                fireHandler.fire(member, effective.getStart(), effective.getEnd(), true);
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
                                        fireDueWindowsByProcessingTime(flushDue, System.currentTimeMillis(),
                                                pid, stateName, bucketTriggers, flushHandler, Integer.MAX_VALUE);
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
                        // Processing-time sweep: the Redis engine's processing-time clock is the
                        // wall clock, so a bucket whose close time has passed fires here via the
                        // trigger's onProcessingTime instead of waiting for the watermark.
                        fireDueWindowsByProcessingTime(due, System.currentTimeMillis(), partitionId,
                                stateName, bucketTriggers, fireHandler,
                                Math.max(1, config.getWindowMaxFiresPerRecord()));
                    } finally {
                        stateStore.clearCurrentKey();
                        stateStore.clearCurrentPartitionId();
                    }
                });
                return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                    subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, ops, assignerOwnedEventTime);
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
                        },
                        // Raw emitter for merging assigners (audit P0-1): fold the reducer
                        // over the merged element list instead of decoding an incremental state
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            Class<?> type = valueClassRef.get();
                            if (type == null) {
                                return;
                            }
                            List<String> items;
                            try {
                                items = readRawElements(json);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to deserialize window elements", e);
                            }
                            V acc = null;
                            for (String item : items) {
                                if (item == null) continue;
                                V x;
                                try {
                                    x = decodeRawElement(item, type);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window element", e);
                                }
                                try {
                                    acc = acc == null ? x : reducer.reduce(acc, x);
                                } catch (Exception e) {
                                    throw new RuntimeException("Window reduce function failed", e);
                                }
                            }
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            try {
                                emit.emit(acc);
                            } catch (Exception e) {
                                // RT-H3: keep the raw list on a failed emit so the retry fires
                                // the complete merged window
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
                        },
                        // Raw emitter for merging assigners (audit P0-1): replay the merged
                        // element list through a fresh accumulator
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            List<String> items;
                            try {
                                items = readRawElements(json);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to deserialize window elements", e);
                            }
                            AggregateFunction.Accumulator<V> acc = aggregateFunction.createAccumulator();
                            for (String item : items) {
                                if (item == null) continue;
                                V x;
                                try {
                                    x = decodeRawElement(item, valueClassRef.get());
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window element", e);
                                }
                                try {
                                    acc = aggregateFunction.add(x, acc);
                                } catch (Exception e) {
                                    throw new RuntimeException("Window aggregate add failed", e);
                                }
                            }
                            R out;
                            try {
                                out = aggregateFunction.getResult(acc);
                            } catch (Exception e) {
                                throw new RuntimeException("Window aggregate getResult failed", e);
                            }
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            try {
                                emit.emit(out);
                            } catch (Exception e) {
                                // RT-H3: keep the raw list on a failed emit so the retry fires
                                // the complete merged window
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
                // apply's state is already the raw element list (audit P0-1): the same
                // accumulate/emitter pair serves merging assigners — pass it as both
                WindowAccumulator<V> appendElement =
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
                        };
                WindowEmitter emitElement =
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
                        };
                return registerWindowedOperator("apply", null, appendElement, emitElement, emitElement);
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
                        },
                        // Raw emitter for merging assigners (audit P0-1): fold the merged
                        // element list into the sum instead of decoding an incremental state
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
                                items = readRawElements(json);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to deserialize window elements", e);
                            }
                            Number sum = null;
                            String sampleName = null;
                            for (String item : items) {
                                if (item == null) continue;
                                Number x;
                                try {
                                    x = (Number) decodeRawElement(item, valueClass);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to deserialize window element", e);
                                }
                                sum = io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.add(sum, x);
                                sampleName = x.getClass().getName();
                            }
                            Number outNumber = io.github.cuihairu.redis.streaming.runtime.internal.NumberAggregationUtils.castToSameType(sum, sampleName);
                            @SuppressWarnings("unchecked")
                            V out = (V) outNumber;
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            try {
                                emit.emit(out);
                            } catch (Exception e) {
                                // RT-H3: keep the raw list on a failed emit so the retry fires
                                // the complete merged window
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
                        },
                        // Raw emitter for merging assigners (audit P0-1): the merged element
                        // list's size is the count
                        (ref, stateName, member, windowStart, windowEnd, partitionId, emit, purgeState) -> {
                            RMap<String, String> state = ref.map();
                            String json = state.get(member);
                            if (json == null || json.isBlank()) {
                                return;
                            }
                            List<String> items;
                            try {
                                items = readRawElements(json);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to deserialize window elements", e);
                            }
                            try {
                                RedisRuntimeMetrics.get().incWindowFired(config.getJobName(), topic, consumerGroup, operatorId, stateName, partitionId);
                            } catch (Exception ignore) {
                            }
                            try {
                                emit.emit((long) items.size());
                            } catch (Exception e) {
                                // RT-H3: keep the raw list on a failed emit so the retry fires
                                // the complete merged window
                                throw new RuntimeException("Failed to emit window count result", e);
                            }
                            if (purgeState) {
                                state.remove(member);
                                stateStore.touch(ref.redisKey(), stateName, state);
                            }
                        });
            }

            /** Decodes a window member's raw element list (the merged-state shape). */
            private List<String> readRawElements(String json) throws Exception {
                @SuppressWarnings("unchecked")
                List<String> items = objectMapper.readValue(json, List.class);
                return items == null ? List.of() : items;
            }

            /** Decodes one raw element of a window member. */
            private V decodeRawElement(String item, Class<?> type) throws Exception {
                @SuppressWarnings("unchecked")
                V v = (V) objectMapper.readValue(item, type);
                return v;
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

            /**
             * Drains due windows whose close score is within the current wall clock, consulting
             * each bucket trigger's {@code onProcessingTime}. This is the Redis engine's
             * processing-time fire path: a
             * {@link io.github.cuihairu.redis.streaming.window.triggers.ProcessingTimeTrigger}
             * (or any trigger that answers FIRE/FIRE_AND_PURGE from {@code onProcessingTime})
             * closes its window here rather than waiting for the watermark. Triggers that answer
             * CONTINUE (the stock {@code EventTimeTrigger}) are re-queued at their original close
             * score, so event-time firing is untouched.
             */
            private void fireDueWindowsByProcessingTime(RScoredSortedSet<String> due,
                                                        long now,
                                                        int partitionId,
                                                        String stateName,
                                                        Map<String, WindowAssigner.Trigger<V>> bucketTriggers,
                                                        WindowFireHandler handler,
                                                        int maxFires) throws Exception {
                for (int i = 0; i < maxFires; i++) {
                    org.redisson.client.protocol.ScoredEntry<String> first = due.firstEntry();
                    if (first == null || first.getScore() > now) {
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
                    String triggerKey = partitionId + D + entry.getValue();
                    WindowAssigner.Trigger<V> trigger = bucketTriggers.computeIfAbsent(
                            triggerKey, k -> assigner.getDefaultTrigger());
                    WindowAssigner.TriggerResult result = trigger.onProcessingTime(now,
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
                        due.add(entry.getScore(), entry.getValue());
                        throw e;
                    }
                    bucketTriggers.remove(triggerKey);
                }
            }

            /**
             * Audit P0-1: coalesces every open window member of {@code keyField} whose
             * [start, end) intersects {@code seed} into the union window, concatenates
             * their raw element lists, appends {@code value}, and stores the list under
             * the union member; absorbed members lose their state, due entry and bucket
             * trigger. Returns the union member string.
             *
             * <p>Mirrors the in-memory engine's merge: windows are half-open, so a member
             * ending exactly where the seed begins (an inactivity gap of exactly the
             * session gap) does not merge; open members of a key stay pairwise
             * non-intersecting (the invariant this method upholds), which is why a member
             * skipped as non-intersecting can never intersect the final union. The kept
             * trigger is the first absorbed member's instance — the trigger API has no
             * merge callback, so the absorbed triggers' state is dropped (same contract
             * as the in-memory engine).
             *
             * <p>Enumerating the due set per element is O(open members of the partition);
             * merging assigners are the session-shaped minority, and correctness is the
             * point — the incremental state shapes cannot be merged for non-associative
             * aggregations, which is why the raw element list is the merged state.
             */
            private String mergeIntoRawSession(String stateName,
                                               String keyField,
                                               RScoredSortedSet<String> due,
                                               WindowAssigner.Window seed,
                                               V value,
                                               Map<String, WindowAssigner.Trigger<V>> bucketTriggers,
                                               int partitionId) throws Exception {
                long start = seed.getStart();
                long end = seed.getEnd();
                List<String> absorbed = new ArrayList<>();
                WindowAssigner.Trigger<V> keptTrigger = null;
                for (String m : due) {
                    ParsedWindow pw = parseWindow(m);
                    if (!pw.keyField.equals(keyField)) {
                        continue;
                    }
                    if (!(pw.start < end && start < pw.end)) {
                        continue; // half-open: touching bounds do not intersect
                    }
                    absorbed.add(m);
                    if (keptTrigger == null) {
                        // capture before the removal loop erases the bucket entry
                        keptTrigger = bucketTriggers.get(partitionId + D + m);
                    }
                    start = Math.min(start, pw.start);
                    end = Math.max(end, pw.end);
                }
                String member = windowMember(keyField, start, end);
                List<String> items = new ArrayList<>();
                for (String m : absorbed) {
                    RedisKeyedStateStore.StateMapRef oldRef = stateStore.stateMapRef(stateName, m);
                    String json = oldRef.map().get(m);
                    if (json != null && !json.isBlank()) {
                        try {
                            items.addAll(readRawElements(json));
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize merged window elements", e);
                        }
                    }
                    oldRef.map().remove(m);
                    stateStore.touch(oldRef.redisKey(), stateName, oldRef.map());
                    due.remove(m);
                    bucketTriggers.remove(partitionId + D + m);
                }
                try {
                    items.add(objectMapper.writeValueAsString(value));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize window element", e);
                }
                RedisKeyedStateStore.StateMapRef ref = stateStore.stateMapRef(stateName, member);
                RMap<String, String> state = ref.map();
                try {
                    state.put(member, objectMapper.writeValueAsString(items));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize window elements", e);
                }
                stateStore.touch(ref.redisKey(), stateName, state);
                due.add(windowCloseTime(end), member);
                // keep the first absorbed bucket's trigger under the union member so a
                // stateful trigger's early-fire state survives the merge; the record path's
                // computeIfAbsent reuses this instance instead of minting a fresh trigger
                if (keptTrigger != null) {
                    bucketTriggers.putIfAbsent(partitionId + D + member, keptTrigger);
                }
                return member;
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

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                    subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, ops, assignerOwnedEventTime);
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

            return new RedisStreamBuilder<>(env, config, redissonClient, objectMapper, streamId, topic, consumerGroup,
                    subscriptionOptions, sourceTopics, sourceConsumerGroups, sourceSubscriptionOptions, ops, assignerOwnedEventTime);
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
