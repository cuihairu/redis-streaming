package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.lease.LeaseManager;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRecord;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.mq.retry.ExponentialBackoffRetryPolicy;
import io.github.cuihairu.redis.streaming.mq.retry.DeliveryRetryPolicy;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.control.ReassignableMessageConsumer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.api.stream.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Streams based message consumer implementation with consumer group support.
 * <p>Internal responsibilities live in same-package collaborators:
 * {@link RedisMessageDispatchProcessor} (per-record parse/dispatch/ack/retry) and
 * {@link RedisPartitionAssignmentCoordinator} (lease/rebalance/metrics).
 */
@Slf4j
public class RedisMessageConsumer implements MessageConsumer, PausableMessageConsumer, ReassignableMessageConsumer {

    final RedissonClient redissonClient;
    final String consumerName;
    final ScheduledExecutorService consumerPool;
    final ScheduledExecutorService schedulerPool;
    final AtomicBoolean running = new AtomicBoolean(false);
    final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    final AtomicLong inFlight = new AtomicLong(0);
    /** Batches fetched from Redis whose per-message accounting has not started yet (barrier view). */
    private final AtomicLong readsInFlight = new AtomicLong(0);
    final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    final Map<PartitionKey, PartitionWorker> workers = new ConcurrentHashMap<>();
    final TopicPartitionRegistry partitionRegistry;
    final Broker broker; // optional broker; if present, use it for read/ack
    final LeaseManager leaseManager;
    final DeliveryRetryPolicy retryPolicy;
    final MqOptions options;
    final StreamKeys keys;
    final PayloadLifecycleManager payloadLifecycleManager;
    final Semaphore inFlightLimiter;
    final io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService deadLetterService;

    final RedisMessageDispatchProcessor dispatch = new RedisMessageDispatchProcessor(this);
    final RedisPartitionAssignmentCoordinator assignment = new RedisPartitionAssignmentCoordinator(this);

    public RedisMessageConsumer(RedissonClient redissonClient, String consumerName,
                                TopicPartitionRegistry partitionRegistry,
                                MqOptions options) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.keys = StreamKeys.of(this.options);
        // Separate pools: one for workers, one for scheduled tasks (sizes configurable)
        this.consumerPool = Executors.newScheduledThreadPool(this.options.getWorkerThreads());
        this.schedulerPool = Executors.newScheduledThreadPool(this.options.getSchedulerThreads());
        this.partitionRegistry = partitionRegistry;
        this.broker = null;
        this.leaseManager = new LeaseManager(redissonClient);
        this.retryPolicy = new ExponentialBackoffRetryPolicy(
                this.options.getRetryMaxAttempts(),
                this.options.getRetryBaseBackoffMs(),
                this.options.getRetryMaxBackoffMs());
        this.payloadLifecycleManager = new PayloadLifecycleManager(redissonClient, this.options);
        this.inFlightLimiter = this.options.getMaxInFlight() > 0 ? new Semaphore(this.options.getMaxInFlight()) : null;
        this.deadLetterService = new io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService(redissonClient, null, this.keys);
    }

    public RedisMessageConsumer(RedissonClient redissonClient, String consumerName,
                                TopicPartitionRegistry partitionRegistry,
                                MqOptions options,
                                Broker broker) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.keys = StreamKeys.of(this.options);
        this.consumerPool = Executors.newScheduledThreadPool(this.options.getWorkerThreads());
        this.schedulerPool = Executors.newScheduledThreadPool(this.options.getSchedulerThreads());
        this.partitionRegistry = partitionRegistry;
        this.broker = broker;
        this.leaseManager = new LeaseManager(redissonClient);
        this.retryPolicy = new ExponentialBackoffRetryPolicy(
                this.options.getRetryMaxAttempts(),
                this.options.getRetryBaseBackoffMs(),
                this.options.getRetryMaxBackoffMs());
        this.payloadLifecycleManager = new PayloadLifecycleManager(redissonClient, this.options);
        this.inFlightLimiter = this.options.getMaxInFlight() > 0 ? new Semaphore(this.options.getMaxInFlight()) : null;
        this.deadLetterService = new io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService(redissonClient);
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribe(topic, options.getDefaultConsumerGroup(), handler);
    }

    @Override
    public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
        if (closed.get()) {
            throw new IllegalStateException("Consumer is closed");
        }

        // Ensure topic meta exists so consumers can see the expected partition count even before any producer sends.
        // This is important for deterministic partition pinning and multi-partition workloads.
        try {
            partitionRegistry.ensureTopic(topic, options.getDefaultPartitionCount());
        } catch (Exception ignore) {
        }

        Subscription sub = new Subscription(topic, consumerGroup, handler);
        subscriptions.put(topic, sub);

        // Ensure groups exist on all partitions. Be robust when stream key doesn't exist yet:
        // - Try XINFO GROUPS; if key missing, still attempt XGROUP CREATE ... MKSTREAM
        // - If BUSYGROUP (already exists), treat as EXISTS
        int pc = partitionRegistry.getPartitionCount(topic);
        org.redisson.api.RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
        final String lua =
                // Try to check existing groups; if XINFO fails (no key), continue to CREATE
                "local exists=false \n" +
                "local info = redis.pcall('XINFO','GROUPS', KEYS[1]) \n" +
                "if type(info) == 'table' and info.err == nil then \n" +
                "  for i=1,#info do local g = info[i]; for j=1,#g,2 do if g[j]=='name' and g[j+1]==ARGV[1] then exists=true; break end end if exists then break end end \n" +
                "end \n" +
                "if exists then return 'EXISTS' end \n" +
                "local r = redis.pcall('XGROUP','CREATE', KEYS[1], ARGV[1], ARGV[2], 'MKSTREAM') \n" +
                "if type(r)=='table' and r.err then if string.find(r.err,'BUSYGROUP') then return 'EXISTS' else return r.err end end \n" +
                "return r";
        for (int i = 0; i < pc; i++) {
            String streamKey = keys.partitionStreamKey(topic, i);
            try {
                Object r = script.eval(org.redisson.api.RScript.Mode.READ_WRITE, lua,
                        org.redisson.api.RScript.ReturnType.STRING,
                        java.util.Collections.singletonList(streamKey), consumerGroup, "0-0");
                if (!"OK".equals(String.valueOf(r)) && !"EXISTS".equals(String.valueOf(r))) {
                    log.debug("Ensure group via Lua returned {} for {} @ {}", String.valueOf(r), consumerGroup, streamKey);
                }
            } catch (Exception e) {
                // Ignore BUSYGROUP or race; continue. Log at debug to avoid noise
                log.debug("Ensure group via Lua ignored for {} @ {}: {}", consumerGroup, streamKey, e.toString());
            }
        }

        log.info("Subscribed: topic='{}', group='{}', consumer='{}'", topic, consumerGroup, consumerName);
    }

    @Override
    public void subscribe(String topic, String consumerGroup, MessageHandler handler,
                          io.github.cuihairu.redis.streaming.mq.SubscriptionOptions opts) {
        subscribe(topic, consumerGroup, handler);
        if (opts != null) {
            Subscription sub = subscriptions.get(topic);
            if (sub != null) {
                sub.batchOverride = opts.getBatchCount();
                sub.timeoutOverrideMs = opts.getPollTimeoutMs();
                sub.partitionModulo = opts.getPartitionModulo();
                sub.partitionRemainder = opts.getPartitionRemainder();
            }
        }
    }

    @Override
    public void unsubscribe(String topic) {
        Subscription removed = subscriptions.remove(topic);
        if (removed != null) {
            // stop workers of this topic
            workers.keySet().stream()
                    .filter(pk -> pk.topic.equals(topic) && pk.group.equals(removed.consumerGroup))
                    .forEach(pk -> {
                        PartitionWorker w = workers.remove(pk);
                        if (w != null) {
                            w.stop();
                        }
                    });
            log.info("Unsubscribed from topic '{}'", topic);
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Redis consumer '{}'", consumerName);

            // Do an initial rebalance synchronously to reduce startup races in short-lived tests.
            // Subsequent rebalances run on the scheduler thread.
            assignment.rebalanceAssignments();

            // periodic rebalance and lease renewal
            schedulerPool.scheduleWithFixedDelay(assignment::rebalanceAssignments,
                    options.getRebalanceIntervalSec(), options.getRebalanceIntervalSec(), TimeUnit.SECONDS);
            schedulerPool.scheduleWithFixedDelay(assignment::renewLeases,
                    options.getRenewIntervalSec(), options.getRenewIntervalSec(), TimeUnit.SECONDS);

            // pending processing: run immediately to reduce flakiness when tests inject a pending entry shortly after start
            schedulerPool.scheduleWithFixedDelay(this::processPendingMessages,
                    0, options.getPendingScanIntervalSec(), TimeUnit.SECONDS);
            // due retry moving: start immediately to reduce flakiness in short-running tests
            schedulerPool.scheduleWithFixedDelay(this::moveDueRetries,
                    0, options.getRetryMoverIntervalSec(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Redis consumer '{}'", consumerName);
            // stop all workers
            workers.values().forEach(PartitionWorker::stop);
            workers.clear();
            try {
                MqMetrics.get().setInFlight(consumerName, inFlight.get(), options.getMaxInFlight());
            } catch (Throwable ignore) {
            }
            try {
                MqMetrics.get().setMaxLeasedPartitions(consumerName, assignment.getMaxLeasedPartitions());
            } catch (Throwable ignore) {
            }
            subscriptions.forEach((topic, sub) -> assignment.publishPartitionMetrics(topic, sub.consumerGroup, assignment.computeEligiblePartitions(topic, sub.consumerGroup)));
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
            schedulerPool.shutdown();
            consumerPool.shutdown();
            try {
                schedulerPool.awaitTermination(5, TimeUnit.SECONDS);
                consumerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            subscriptions.clear();
            log.info("Redis consumer '{}' closed", consumerName);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void pause() {
        paused.set(true);
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public long inFlight() {
        return inFlight.get();
    }

    @Override
    public long inFlightBarrier() {
        return inFlight.get() + readsInFlight.get();
    }

    void runPartitionWorker(PartitionWorker worker) {
        String topic = worker.topic;
        String group = worker.group;
        int partitionId = worker.partitionId;
        MessageHandler handler = worker.handler;
        String leaseKey = keys.leaseKey(topic, group, partitionId);

        try {
            while (running.get() && !closed.get() && worker.running.get()) {
                try {
                    if (paused.get()) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    int batch = worker.batchOverride != null ? worker.batchOverride : options.getConsumerBatchCount();
                    long to = worker.timeoutOverrideMs != null ? worker.timeoutOverrideMs : options.getConsumerPollTimeoutMs();
                    // Checkpoint barrier: mark the read BEFORE it starts, not per message
                    // after it. readGroup blocks up to the poll timeout and then hands back
                    // messages that are already in this consumer's PEL; if the checkpoint
                    // drain sampled inFlight==0 while the read was in flight, those messages
                    // would be invoked DURING the paused window and race the two-phase-commit
                    // phases (an element landing after its epoch's buffer snapshot is dropped
                    // when the epoch is finalized). The read slot lives in readsInFlight so
                    // the public inFlight() keeps its handler-only semantics.
                    readsInFlight.incrementAndGet();
                    try {
                        if (broker != null) {
                            java.util.List<BrokerRecord> records = broker.readGroup(topic, group, consumerName, partitionId, batch, to);
                            for (BrokerRecord br : records) {
                                dispatch.processIncomingRecord(topic, group, partitionId, br.getId(), br.getData(), null, handler, true);
                            }
                        } else {
                            String streamKey = keys.partitionStreamKey(topic, partitionId);
                            RStream<String, Object> stream = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
                            Map<StreamMessageId, Map<String, Object>> messages = stream.readGroup(
                                    group,
                                    consumerName,
                                    StreamReadGroupArgs.neverDelivered()
                                            .count(batch)
                                            .timeout(Duration.ofMillis(to))
                            );
                            for (Map.Entry<StreamMessageId, Map<String, Object>> entry : messages.entrySet()) {
                                dispatch.processIncomingRecord(topic, group, partitionId, entry.getKey().toString(), entry.getValue(), stream, handler, false);
                            }
                        }
                    } finally {
                        readsInFlight.decrementAndGet();
                    }

                } catch (Exception e) {
                    if (running.get() && !closed.get() && worker.running.get()) {
                        log.error("Error consuming from {}:{}", topic, partitionId, e);
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } finally {
            // MQ-15: release the lease only while this worker is still the registered owner of
            // the slot, or the slot was already cleared by stop()/unsubscribe() (preserving the
            // existing prompt-release semantics). If renewLeases() dropped this worker and a
            // successor was registered meanwhile — same consumer name, same lease key — a
            // release here would compare-and-delete the successor's live lease and orphan the
            // partition for the whole remaining lease TTL.
            PartitionKey pk = new PartitionKey(topic, group, partitionId);
            final boolean[] replaced = {false};
            workers.compute(pk, (k, current) -> {
                if (current == null || current == worker) {
                    return null;
                }
                replaced[0] = true;
                return current;
            });
            if (!replaced[0]) {
                try {
                    leaseManager.releaseIfOwner(leaseKey, consumerName);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void processPendingMessages() {
        if (!running.get() || closed.get()) {
            return;
        }
        if (paused.get()) {
            return;
        }

        // Only for partitions we own. The sweep holds one read slot across listPending +
        // claim so the checkpoint barrier cannot pass while a claim is in flight — the
        // paused check above predates those Redis roundtrips, so a claim issued just before
        // pause() would otherwise be invoked inside the paused window and race the
        // two-phase-commit phases. Per-message accounting still lands in inFlight.
        readsInFlight.incrementAndGet();
        try {
            workers.forEach((pk, worker) -> {
                try {
                    String streamKey = keys.partitionStreamKey(pk.topic, pk.partitionId);
                    RStream<String, Object> stream = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);

                    @SuppressWarnings("deprecation")
                    List<PendingEntry> pendingEntries = stream.listPending(pk.group,
                            StreamMessageId.MIN, StreamMessageId.MAX, options.getClaimBatchSize());

                    for (PendingEntry pendingEntry : pendingEntries) {
                        StreamMessageId messageId = pendingEntry.getId();
                        if (pendingEntry.getIdleTime() > options.getClaimIdleMs()) {
                            try {
                                Map<StreamMessageId, Map<String, Object>> claimed = stream.claim(
                                        pk.group, consumerName, options.getClaimIdleMs(), TimeUnit.MILLISECONDS, messageId);
                                for (Map.Entry<StreamMessageId, Map<String, Object>> entry : claimed.entrySet()) {
                                    StreamMessageId claimedId = entry.getKey();
                                    Map<String, Object> data = entry.getValue();
                                    Message message;
                                    try {
                                        message = StreamEntryCodec.parsePartitionEntry(pk.topic, claimedId.toString(), data, payloadLifecycleManager);
                                    } catch (RuntimeException ex) {
                                        // Any parse failure (including malformed timestamp, corrupt payload, etc.)
                                        // is treated as poison — send to DLQ and ACK original to avoid infinite
                                        // re-claim loops (MQ-06). Payload-missing already handled by the same path.
                                        dispatch.handleMissingPayload(pk.topic, pk.group, pk.partitionId, claimedId.toString(), data, stream);
                                        continue;
                                    }
                                    boolean permitAcquired = false;
                                    try {
                                        permitAcquired = dispatch.acquireInFlightPermit();
                                        inFlight.incrementAndGet();
                                        MessageHandleResult result = worker.handler.handle(message);
                                        dispatch.dispatchResult(message.getTopic(), pk.group, claimedId.toString(), pk.partitionId, message, result, data, stream);
                                    } catch (Exception e) {
                                        log.error("Error reprocessing pending {} from {}", claimedId, streamKey, e);
                                        dispatch.requeueOrDeadLetter(stream, pk.group, claimedId.toString(), pk.partitionId, message, data);
                                    } finally {
                                        inFlight.decrementAndGet();
                                        if (permitAcquired) {
                                            dispatch.releaseInFlightPermit();
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.error("Error claiming pending {} from {}", messageId, streamKey, e);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error processing pending for {}", pk, e);
                }
            });
        } finally {
            readsInFlight.decrementAndGet();
        }
    }


    void moveDueRetries() {
        if (!running.get() || closed.get()) return;
        if (paused.get()) return;
        // Process per subscribed topic; use a per-topic lock to avoid duplicate movers
        subscriptions.forEach((topic, sub) -> {
            String lockKey = keys.retryLockKey(topic);
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                locked = lock.tryLock(options.getRetryLockWaitMs(), options.getRetryLockLeaseMs(), TimeUnit.MILLISECONDS);
                if (!locked) return;
                String bucketKey = keys.retryBucketKey(topic);
                String lua = "local z=KEYS[1]; local now=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); local ts=ARGV[3]; local sp=ARGV[4]; "
                        + "local ids=redis.call('ZRANGEBYSCORE', z, '-inf', now, 'LIMIT', 0, limit); local moved={}; "
                        + "for i=1,#ids do local id=ids[i]; "
                        + "local t=redis.call('HGET', id, 'topic') or ''; "
                        + "local pid=tonumber(redis.call('HGET', id, 'partitionId')) or 0; "
                        // MQ-14: keep "absent" distinct from "": HGET returns false only when the
                        // payload field was never stored (null payload). An empty string is truthy
                        // in Lua and must round-trip verbatim as "".
                        + "local payload=redis.call('HGET', id, 'payload'); "
                        + "local retry=redis.call('HGET', id, 'retryCount') or '0'; local maxr=redis.call('HGET', id, 'maxRetries') or '0'; "
                        + "local k=redis.call('HGET', id, 'key') or ''; local hdr=redis.call('HGET', id, 'headers') or ''; local orig=redis.call('HGET', id, 'originalMessageId') or ''; "
                        + "if t=='' then redis.call('ZREM', z, id); redis.call('DEL', id); "
                        + "else local sk=sp..':'..t..':p:'..pid; local args={'timestamp',ts,'retryCount',retry,'maxRetries',maxr,'topic',t,'partitionId',tostring(pid)}; "
                        + "if payload then table.insert(args,'payload'); table.insert(args,payload); end; "
                        + "if k~='' then table.insert(args,'key'); table.insert(args,k); end; if hdr~='' then table.insert(args,'headers'); table.insert(args,hdr); end; "
                        + "if orig~='' then table.insert(args,'originalMessageId'); table.insert(args,orig); end; "
                        + "redis.call('XADD', sk, '*', unpack(args)); redis.call('ZREM', z, id); redis.call('DEL', id); table.insert(moved, id); end; end; return moved;";
                // Use StringCodec and pass ARGV as strings to avoid non-string arg issues in Lua (tonumber())
                RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
                List<Object> moved = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.LIST, java.util.Collections.singletonList(bucketKey),
                        String.valueOf(System.currentTimeMillis()), String.valueOf(options.getRetryMoverBatch()), Instant.now().toString(), keys.getStreamPrefix() + keys.tenantSegment());
                if (moved != null && !moved.isEmpty()) {
                    log.debug("Moved {} retry items for topic {}", moved.size(), topic);
                }
            } catch (Exception e) {
                log.error("Error moving due retries for topic {}", topic, e);
            } finally {
                if (locked) {
                    try { lock.unlock(); } catch (Exception ignore) {}
                }
            }
        });
    }

    @Override
    public boolean updatePartitionAssignment(String topic, int partitionModulo, int partitionRemainder) {
        return assignment.updatePartitionAssignment(topic, partitionModulo, partitionRemainder);
    }
}