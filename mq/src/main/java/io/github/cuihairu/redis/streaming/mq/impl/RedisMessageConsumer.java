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
import io.github.cuihairu.redis.streaming.mq.retry.RetryEnvelope;
import io.github.cuihairu.redis.streaming.mq.retry.RetryPolicy;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.api.stream.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams based message consumer implementation with consumer group support
 */
@Slf4j
public class RedisMessageConsumer implements MessageConsumer {

    private final RedissonClient redissonClient;
    private final String consumerName;
    private final ScheduledExecutorService consumerPool;
    private final ScheduledExecutorService schedulerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<PartitionKey, PartitionWorker> workers = new ConcurrentHashMap<>();
    private final TopicPartitionRegistry partitionRegistry;
    private final Broker broker; // optional broker; if present, use it for read/ack
    private final LeaseManager leaseManager;
    private final RetryPolicy retryPolicy;
    private final MqOptions options;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadLifecycleManager payloadLifecycleManager;

    public RedisMessageConsumer(RedissonClient redissonClient, String consumerName,
                                TopicPartitionRegistry partitionRegistry,
                                MqOptions options) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.options = options == null ? MqOptions.builder().build() : options;
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
    }

    public RedisMessageConsumer(RedissonClient redissonClient, String consumerName,
                                TopicPartitionRegistry partitionRegistry,
                                MqOptions options,
                                Broker broker) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.options = options == null ? MqOptions.builder().build() : options;
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
            String streamKey = StreamKeys.partitionStream(topic, i);
            try {
                Object r = script.eval(org.redisson.api.RScript.Mode.READ_WRITE, lua,
                        org.redisson.api.RScript.ReturnType.STATUS,
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

            // initial assignment
            schedulerPool.submit(this::rebalanceAssignments);

            // periodic rebalance and lease renewal
            schedulerPool.scheduleWithFixedDelay(this::rebalanceAssignments,
                    0, options.getRebalanceIntervalSec(), TimeUnit.SECONDS);
            schedulerPool.scheduleWithFixedDelay(this::renewLeases,
                    options.getRenewIntervalSec(), options.getRenewIntervalSec(), TimeUnit.SECONDS);

            // pending processing (can wait for first scan)
            schedulerPool.scheduleWithFixedDelay(this::processPendingMessages,
                    options.getPendingScanIntervalSec(), options.getPendingScanIntervalSec(), TimeUnit.SECONDS);
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

    private void runPartitionWorker(PartitionWorker worker) {
        String topic = worker.topic;
        String group = worker.group;
        int partitionId = worker.partitionId;
        MessageHandler handler = worker.handler;

        while (running.get() && !closed.get() && worker.running.get()) {
            try {
                int batch = worker.batchOverride != null ? worker.batchOverride : options.getConsumerBatchCount();
                long to = worker.timeoutOverrideMs != null ? worker.timeoutOverrideMs : options.getConsumerPollTimeoutMs();
                if (broker != null) {
                    java.util.List<BrokerRecord> records = broker.readGroup(topic, group, consumerName, partitionId, batch, to);
                    for (BrokerRecord br : records) {
                        String id = br.getId();
                        Map<String, Object> data = br.getData();
                        Message message;
                        try {
                            message = StreamEntryCodec.parsePartitionEntry(topic, id, data, payloadLifecycleManager);
                        } catch (RuntimeException ex) {
                            if (isPayloadMissing(ex)) {
                                // Fallback: DLQ and ACK to avoid poison pending
                                handleMissingPayload(topic, group, partitionId, id, data);
                                continue;
                            }
                            throw ex;
                        }
                        Object pid = data.get("partitionId");
                        int pidInt = partitionId;
                        if (pid instanceof Number) {
                            pidInt = ((Number) pid).intValue();
                        } else if (pid != null) {
                            try { pidInt = Integer.parseInt(pid.toString()); } catch (Exception ignore) {}
                        }
                        message.getHeaders().put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID, Integer.toString(pidInt));
                        long start = System.nanoTime();
                        try {
                            MessageHandleResult result = handler.handle(message);
                            handleResultBroker(topic, group, id, partitionId, message, result, data);
                            MqMetrics.get().recordHandleLatency(topic, pidInt, (System.nanoTime() - start) / 1_000_000);
                            MqMetrics.get().incConsumed(topic, pidInt);
                        } catch (Exception e) {
                            log.error("Error handling message {} from {}:{}", id, topic, partitionId, e);
                            handleFailedMessageBroker(topic, group, id, partitionId, message, data);
                            MqMetrics.get().recordHandleLatency(topic, pidInt, (System.nanoTime() - start) / 1_000_000);
                            MqMetrics.get().incConsumed(topic, pidInt);
                        }
                    }
                } else {
                    String streamKey = StreamKeys.partitionStream(topic, partitionId);
                    RStream<String, Object> stream = redissonClient.getStream(org.redisson.client.codec.StringCodec.INSTANCE, streamKey);
                    Map<StreamMessageId, Map<String, Object>> messages = stream.readGroup(
                            group,
                            consumerName,
                            StreamReadGroupArgs.neverDelivered()
                                    .count(batch)
                                    .timeout(Duration.ofMillis(to))
                    );
                    for (Map.Entry<StreamMessageId, Map<String, Object>> entry : messages.entrySet()) {
                        StreamMessageId messageId = entry.getKey();
                        Map<String, Object> data = entry.getValue();
                        Message message;
                        try {
                            message = StreamEntryCodec.parsePartitionEntry(topic, messageId.toString(), data, payloadLifecycleManager);
                        } catch (RuntimeException ex) {
                            if (isPayloadMissing(ex)) {
                                handleMissingPayload(topic, group, partitionId, messageId.toString(), data, stream);
                                continue;
                            }
                            throw ex;
                        }
                        // include partitionId if present (support both numeric and string forms)
                        Object pid = data.get("partitionId");
                        int pidInt = partitionId;
                        if (pid instanceof Number) {
                            pidInt = ((Number) pid).intValue();
                        } else if (pid != null) {
                            try { pidInt = Integer.parseInt(pid.toString()); } catch (Exception ignore) {}
                        }
                        message.getHeaders().put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID, Integer.toString(pidInt));
                        long start = System.nanoTime();
                        try {
                            MessageHandleResult result = handler.handle(message);
                            handleResult(stream, group, messageId, partitionId, message, result, data);
                            MqMetrics.get().recordHandleLatency(topic, pidInt, (System.nanoTime() - start) / 1_000_000);
                            MqMetrics.get().incConsumed(topic, pidInt);
                        } catch (Exception e) {
                            log.error("Error handling message {} from {}", messageId, streamKey, e);
                            handleFailedMessage(stream, group, messageId, partitionId, message, data);
                            MqMetrics.get().recordHandleLatency(topic, pidInt, (System.nanoTime() - start) / 1_000_000);
                            MqMetrics.get().incConsumed(topic, pidInt);
                        }
                    }
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
    }

    private void processPendingMessages() {
        if (!running.get() || closed.get()) {
            return;
        }

        // Only for partitions we own
        workers.forEach((pk, worker) -> {
            try {
                String streamKey = StreamKeys.partitionStream(pk.topic, pk.partitionId);
                RStream<String, Object> stream = redissonClient.getStream(org.redisson.client.codec.StringCodec.INSTANCE, streamKey);

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
                                    if (isPayloadMissing(ex)) {
                                        handleMissingPayload(pk.topic, pk.group, pk.partitionId, claimedId.toString(), data, stream);
                                        continue;
                                    }
                                    throw ex;
                                }
                                try {
                                    MessageHandleResult result = worker.handler.handle(message);
                                    handleResult(stream, pk.group, claimedId, pk.partitionId, message, result, data);
                                } catch (Exception e) {
                                    log.error("Error reprocessing pending {} from {}", claimedId, streamKey, e);
                                    handleFailedMessage(stream, pk.group, claimedId, pk.partitionId, message, data);
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
    }

    private void handleResult(RStream<String, Object> stream, String consumerGroup,
                              StreamMessageId messageId, int partitionId,
                              Message message, MessageHandleResult result, Map<String, Object> messageData) {
        switch (result) {
            case SUCCESS:
                // Acknowledge the message
                ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId.toString(), messageData);
                log.debug("Message {} acknowledged", messageId);
                MqMetrics.get().incAcked(message.getTopic(), partitionId);
                break;

            case RETRY:
                requeueOrDeadLetter(stream, consumerGroup, messageId.toString(), partitionId, message, messageData);
                break;

            case FAIL:
            case DEAD_LETTER:
                sendToDeadLetterQueue(message);
                MqMetrics.get().incDeadLetter(message.getTopic(), partitionId);
                ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId.toString(), messageData);
                break;
        }
    }

    private void handleResultBroker(String topic, String consumerGroup, String messageId,
                                    int partitionId, Message message, MessageHandleResult result,
                                    Map<String, Object> messageData) {
        switch (result) {
            case SUCCESS:
                ackViaBackend(topic, consumerGroup, partitionId, null, messageId, messageData);
                log.debug("Message {} acknowledged", messageId);
                MqMetrics.get().incAcked(message.getTopic(), partitionId);
                break;
            case RETRY:
                requeueOrDeadLetter(null, consumerGroup, messageId, partitionId, message, messageData);
                break;
            case FAIL:
            case DEAD_LETTER:
                sendToDeadLetterQueue(message);
                MqMetrics.get().incDeadLetter(message.getTopic(), partitionId);
                ackViaBackend(topic, consumerGroup, partitionId, null, messageId, messageData);
                break;
        }
    }

    private void handleFailedMessage(RStream<String, Object> stream, String consumerGroup,
                                     StreamMessageId messageId, int partitionId, Message message,
                                     Map<String, Object> messageData) {
        requeueOrDeadLetter(stream, consumerGroup, messageId.toString(), partitionId, message, messageData);
    }

    private void handleFailedMessageBroker(String topic, String consumerGroup,
                                           String messageId, int partitionId, Message message,
                                           Map<String, Object> messageData) {
        requeueOrDeadLetter(null, consumerGroup, messageId, partitionId, message, messageData);
    }

    // Detect payload-missing exception patterns from payload loaders
    private boolean isPayloadMissing(Throwable ex) {
        if (ex == null) return false;
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Payload not found") || msg.contains("Failed to load payload"))) {
            return true;
        }
        Throwable c = ex.getCause();
        return c != null && isPayloadMissing(c);
    }

    // When payload key has expired/missing, DLQ and ACK the original to avoid poison pending
    private void handleMissingPayload(String topic, String group, int partitionId,
                                      String messageId, Map<String, Object> messageData) {
        handleMissingPayload(topic, group, partitionId, messageId, messageData, null);
    }

    private void handleMissingPayload(String topic, String group, int partitionId,
                                      String messageId, Map<String, Object> messageData,
                                      RStream<String, Object> streamOrNull) {
        try {
            Message m = new Message();
            m.setId(messageId);
            m.setTopic(topic);
            m.setPayload(null);
            m.setTimestamp(java.time.Instant.now());
            // best-effort fields from raw data
            Object key = messageData.get("key"); if (key != null) m.setKey(String.valueOf(key));
            int rc = 0; Object rco = messageData.get("retryCount");
            try { if (rco != null) rc = (rco instanceof Number) ? ((Number) rco).intValue() : Integer.parseInt(String.valueOf(rco)); } catch (Exception ignore) {}
            m.setRetryCount(rc);
            int mr = 3; Object mro = messageData.get("maxRetries");
            try { if (mro != null) mr = (mro instanceof Number) ? ((Number) mro).intValue() : Integer.parseInt(String.valueOf(mro)); } catch (Exception ignore) {}
            m.setMaxRetries(mr);

            java.util.Map<String,String> headers = new java.util.HashMap<>();
            Object hdr = messageData.get("headers");
            if (hdr instanceof java.util.Map) {
                ((java.util.Map<?,?>) hdr).forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
            } else if (hdr instanceof String) {
                try { headers.putAll(new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) hdr, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,String>>(){})); } catch (Exception ignore) {}
            }
            // annotate missing details for ops
            String ref = payloadLifecycleManager.extractPayloadHashRef(messageData);
            headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PAYLOAD_MISSING, "true");
            if (ref != null) headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PAYLOAD_MISSING_REF, ref);
            m.setHeaders(headers);

            // send to DLQ then ACK original
            sendToDeadLetterQueue(m);
            MqMetrics.get().incDeadLetter(topic, partitionId);
            try { MqMetrics.get().incPayloadMissing(topic, partitionId); } catch (Throwable ignore) {}
            ackViaBackend(topic, group, partitionId, streamOrNull, messageId, messageData);
        } catch (Exception e) {
            log.error("Failed to handle missing payload for {}:{} id={}", topic, partitionId, messageId, e);
        }
    }

    private void sendToDeadLetterQueue(Message message) {
        try {
            String dlqKey = StreamKeys.dlq(message.getTopic());
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqKey);
            Map<String, Object> dlqData = StreamEntryCodec.buildDlqEntry(message, payloadLifecycleManager);
            dlqStream.add(StreamAddArgs.entries(dlqData));
            log.warn("Message sent to dead letter queue: {}", dlqKey);
        } catch (Exception e) {
            log.error("Failed to send message to dead letter queue", e);
        }
    }

    // createMessageFromData migrated to StreamEntryCodec

    private void requeueOrDeadLetter(RStream<String, Object> stream,
                                     String consumerGroup,
                                     String messageId,
                                     int partitionId,
                                     Message message,
                                     Map<String, Object> messageData) {
        try {
            // If current retry count already at/over limit, send to DLQ immediately
            if (message.hasExceededMaxRetries()) {
                sendToDeadLetterQueue(message);
                MqMetrics.get().incDeadLetter(message.getTopic(), partitionId);
                ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
                return;
            }
            // ACK original and either re-enqueue directly (for tiny backoffs) or schedule via retry bucket
            ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
            int nextRetry = message.getRetryCount() + 1;
            long delayMs = retryPolicy.nextBackoffMs(nextRetry);
            long dueAt = System.currentTimeMillis() + delayMs;

            // If next retry would exceed max retries, dead-letter instead of rescheduling.
            if (nextRetry > message.getMaxRetries()) {
                sendToDeadLetterQueue(message);
                MqMetrics.get().incDeadLetter(message.getTopic(), partitionId);
                return;
            }

            String topic = message.getTopic();
            if (delayMs <= 50) {
                // Fast path: re-enqueue directly to stream for tiny backoffs to avoid relying on the mover
                try {
                    Map<String, Object> data = new HashMap<>();
                    data.put("payload", objectToJson(message.getPayload()));
                    data.put("timestamp", Instant.now().toString());
                    data.put("retryCount", nextRetry);
                    data.put("maxRetries", message.getMaxRetries());
                    data.put("topic", topic);
                    data.put("partitionId", partitionId);
                    if (message.getKey() != null) data.put("key", message.getKey());
                    if (message.getHeaders() != null && !message.getHeaders().isEmpty()) data.put("headers", objectToJson(message.getHeaders()));
                    RStream<String, Object> target = (stream != null)
                            ? stream
                            : redissonClient.getStream(StreamKeys.partitionStream(topic, partitionId));
                    target.add(StreamAddArgs.entries(data));
                    MqMetrics.get().incRetried(topic, partitionId);
                    log.debug("Message {} re-enqueued directly for retry ({} ms)", messageId, delayMs);
                } catch (Exception ex) {
                    log.error("Failed to directly re-enqueue message {} for retry", messageId, ex);
                }
                return;
            }

            // Default path: schedule into retry bucket with backoff (Hash + ZSET id)
            String itemId = java.util.UUID.randomUUID().toString();
            String itemKey = StreamKeys.retryItem(topic, itemId);
            // Use StringCodec to ensure plain string fields readable by Lua (HGET) and avoid binary values
            org.redisson.api.RMap<String, String> item = redissonClient.getMap(itemKey, org.redisson.client.codec.StringCodec.INSTANCE);
            // Store as strings for Lua simplicity; payload/headers JSON-encoded
            item.put("topic", topic);
            item.put("partitionId", Integer.toString(partitionId));
            item.put("payload", objectToJson(message.getPayload()));
            item.put("key", message.getKey() != null ? message.getKey() : "");
            item.put("headers", objectToJson(message.getHeaders() != null ? message.getHeaders() : java.util.Collections.emptyMap()));
            item.put("retryCount", Integer.toString(nextRetry));
            item.put("maxRetries", Integer.toString(message.getMaxRetries()));
            item.put("originalMessageId", message.getId() != null ? message.getId() : "");

            String bucketKey = StreamKeys.retryBucket(topic);
            // Use StringCodec so ZSET members are plain strings (keys), matching hash keys for Lua mover
            org.redisson.api.RScoredSortedSet<String> bucket = redissonClient.getScoredSortedSet(bucketKey, org.redisson.client.codec.StringCodec.INSTANCE);
            bucket.add(dueAt, itemKey);
            MqMetrics.get().incRetried(topic, partitionId);
            log.debug("Message {} scheduled for retry at {} ({} ms), item {}", messageId, dueAt, delayMs, itemKey);

            // Opportunistically trigger a near-term retry mover run to reduce latency/flakiness
            long jitterMs = Math.min(100L, Math.max(10L, delayMs));
            try {
                schedulerPool.schedule(this::moveDueRetries, jitterMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {}
        } catch (Exception e) {
            log.error("Failed to requeue message {} for topic {}", messageId, message.getTopic(), e);
        }
    }

    private String objectToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failure", e);
        }
    }

    private void ackViaBackend(String topic, String consumerGroup, int partitionId,
                               RStream<String, Object> streamOrNull, String messageId) {
        ackViaBackend(topic, consumerGroup, partitionId, streamOrNull, messageId, null);
    }

    private void ackViaBackend(String topic, String consumerGroup, int partitionId,
                               RStream<String, Object> streamOrNull, String messageId, Map<String, Object> messageData) {
        try {
            if (broker != null) {
                broker.ack(topic, consumerGroup, partitionId, messageId);
            } else if (streamOrNull != null) {
                streamOrNull.ack(consumerGroup, parseStreamId(messageId));
            } else {
                // fallback get stream just for ack
                String streamKey = StreamKeys.partitionStream(topic, partitionId);
                redissonClient.getStream(streamKey).ack(consumerGroup, parseStreamId(messageId));
            }

            // Clean up large payload hash if exists
            if (messageData != null) {
                payloadLifecycleManager.deletePayloadHashFromStreamData(messageData);
            }

            // Update commit frontier (best-effort): keep max acknowledged id per group/partition
            try {
                String frontierKey = StreamKeys.commitFrontier(topic, partitionId);
                org.redisson.api.RMap<String, String> map = redissonClient.getMap(frontierKey);
                String prev = map.get(consumerGroup);
                if (prev == null || compareStreamId(messageId, prev) > 0) {
                    map.put(consumerGroup, messageId);
                }
            } catch (Exception ignore) {}
        } catch (Exception ignore) {}
    }

    private StreamMessageId parseStreamId(String id) {
        try {
            if (id == null) return StreamMessageId.MIN;
            String[] parts = id.split("-", 2);
            if (parts.length == 2) {
                long ms = Long.parseLong(parts[0]);
                long seq = Long.parseLong(parts[1]);
                return new StreamMessageId(ms, seq);
            }
            long ms = Long.parseLong(id);
            return new StreamMessageId(ms);
        } catch (Exception e) {
            // fallback to MIN to avoid throwing
            return StreamMessageId.MIN;
        }
    }

    // Compare Redis Stream ID strings like "ms-seq" lexicographically by numeric parts
    private int compareStreamId(String a, String b) {
        try {
            String[] pa = a.split("-", 2); String[] pb = b.split("-", 2);
            long am = Long.parseLong(pa[0]); long bm = Long.parseLong(pb[0]);
            if (am != bm) return am < bm ? -1 : 1;
            long as = pa.length>1?Long.parseLong(pa[1]):0L; long bs = pb.length>1?Long.parseLong(pb[1]):0L;
            if (as != bs) return as < bs ? -1 : 1;
            return 0;
        } catch (Exception e) { return a.compareTo(b); }
    }

    private <T> T jsonToObject(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failure", e);
        }
    }

    private void moveDueRetries() {
        if (!running.get() || closed.get()) return;
        // Process per subscribed topic; use a per-topic lock to avoid duplicate movers
        subscriptions.forEach((topic, sub) -> {
            String lockKey = "streaming:mq:retry:lock:" + topic;
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                locked = lock.tryLock(options.getRetryLockWaitMs(), options.getRetryLockLeaseMs(), TimeUnit.MILLISECONDS);
                if (!locked) return;
                String bucketKey = StreamKeys.retryBucket(topic);
                String lua = "local z=KEYS[1]; local now=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); local ts=ARGV[3]; local sp=ARGV[4]; "
                        + "local ids=redis.call('ZRANGEBYSCORE', z, '-inf', now, 'LIMIT', 0, limit); local moved={}; "
                        + "for i=1,#ids do local id=ids[i]; "
                        + "local t=redis.call('HGET', id, 'topic') or ''; "
                        + "local pid=tonumber(redis.call('HGET', id, 'partitionId')) or 0; "
                        + "local payload=redis.call('HGET', id, 'payload') or ''; "
                        + "local retry=redis.call('HGET', id, 'retryCount') or '0'; local maxr=redis.call('HGET', id, 'maxRetries') or '0'; "
                        + "local k=redis.call('HGET', id, 'key') or ''; local hdr=redis.call('HGET', id, 'headers') or ''; local orig=redis.call('HGET', id, 'originalMessageId') or ''; "
                        + "if t=='' then redis.call('ZREM', z, id); redis.call('DEL', id); "
                        + "else local sk=sp..':'..t..':p:'..pid; local args={'payload',payload,'timestamp',ts,'retryCount',retry,'maxRetries',maxr,'topic',t,'partitionId',tostring(pid)}; "
                        + "if k~='' then table.insert(args,'key'); table.insert(args,k); end; if hdr~='' then table.insert(args,'headers'); table.insert(args,hdr); end; "
                        + "if orig~='' then table.insert(args,'originalMessageId'); table.insert(args,orig); end; "
                        + "redis.call('XADD', sk, '*', unpack(args)); redis.call('ZREM', z, id); redis.call('DEL', id); table.insert(moved, id); end; end; return moved;";
                // Use StringCodec and pass ARGV as strings to avoid non-string arg issues in Lua (tonumber())
                RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
                List<Object> moved = script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.MULTI, java.util.Collections.singletonList(bucketKey),
                        String.valueOf(System.currentTimeMillis()), String.valueOf(options.getRetryMoverBatch()), Instant.now().toString(), StreamKeys.streamPrefix());
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

    private void rebalanceAssignments() {
        if (!running.get() || closed.get()) return;
        subscriptions.forEach((topic, sub) -> {
            int pc = partitionRegistry.getPartitionCount(topic);
            for (int i = 0; i < pc; i++) {
                PartitionKey pk = new PartitionKey(topic, sub.consumerGroup, i);
                String leaseKey = StreamKeys.lease(topic, sub.consumerGroup, i);
                // If we already own and have a worker, continue
                if (workers.containsKey(pk)) {
                    continue;
                }
                // Try acquire lease and start worker
                boolean acquired = leaseManager.tryAcquire(leaseKey, consumerName, options.getLeaseTtlSeconds());
                if (acquired) {
                    PartitionWorker worker = new PartitionWorker(topic, sub.consumerGroup, i, sub.handler);
                    worker.batchOverride = sub.batchOverride;
                    worker.timeoutOverrideMs = sub.timeoutOverrideMs;
                    workers.put(pk, worker);
                    consumerPool.submit(() -> runPartitionWorker(worker));
                    log.info("Acquired partition {}-{} for group {}", topic, i, sub.consumerGroup);
                }
            }
        });
    }

    private void renewLeases() {
        if (!running.get() || closed.get()) return;
        workers.forEach((pk, w) -> {
            String leaseKey = StreamKeys.lease(pk.topic, pk.group, pk.partitionId);
            boolean ok = leaseManager.renewIfOwner(leaseKey, consumerName, options.getLeaseTtlSeconds());
            if (!ok) {
                // lost ownership, stop worker
                w.stop();
                workers.remove(pk);
                log.info("Lost lease for {}", pk);
            }
        });
    }

    private static class Subscription {
        final String topic;
        final String consumerGroup;
        final MessageHandler handler;
        Integer batchOverride; // nullable
        Long timeoutOverrideMs; // nullable

        Subscription(String topic, String consumerGroup, MessageHandler handler) {
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.handler = handler;
        }
    }

    private static class PartitionWorker {
        final String topic;
        final String group;
        final int partitionId;
        final MessageHandler handler;
        final AtomicBoolean running = new AtomicBoolean(true);
        Integer batchOverride; // nullable per-subscription override
        Long timeoutOverrideMs; // nullable per-subscription override

        PartitionWorker(String topic, String group, int partitionId, MessageHandler handler) {
            this.topic = topic;
            this.group = group;
            this.partitionId = partitionId;
            this.handler = handler;
        }

        void stop() { running.set(false); }
    }

    private static class PartitionKey {
        final String topic;
        final String group;
        final int partitionId;

        PartitionKey(String topic, String group, int partitionId) {
            this.topic = topic;
            this.group = group;
            this.partitionId = partitionId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PartitionKey)) return false;
            PartitionKey that = (PartitionKey) o;
            return partitionId == that.partitionId &&
                    Objects.equals(topic, that.topic) &&
                    Objects.equals(group, that.group);
        }

        @Override public int hashCode() {
            return Objects.hash(topic, group, partitionId);
        }

        @Override public String toString() {
            return topic + ":" + group + ":p" + partitionId;
        }
    }
}
