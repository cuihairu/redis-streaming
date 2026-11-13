package io.github.cuihairu.redis.streaming.mq.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.api.stream.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Per-record pipeline of {@link RedisMessageConsumer}: parse, dispatch, ack, dead-letter,
 * retry re-enqueue and in-flight accounting. Shares the consumer's state in-place (same
 * package) so the live-read and pending-claim entry paths stay in lockstep.
 */
@Slf4j
final class RedisMessageDispatchProcessor {

    private final RedisMessageConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    RedisMessageDispatchProcessor(RedisMessageConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * Single dispatch for a handled record, shared by live consumption and pending claim.
     * The former consumer.broker/non-consumer.broker twins differed only by the optional stream handle.
     *
     * @param streamOrNull live stream handle when reading directly from Redis Streams;
     *                     {@code null} for consumer.broker-based reads
     */
    void dispatchResult(String topic, String consumerGroup, String messageId,
                                int partitionId, Message message, MessageHandleResult result,
                                Map<String, Object> messageData, RStream<String, Object> streamOrNull) {
        switch (result) {
            case SUCCESS:
                // Acknowledge the message (unless deferred by runtime)
                boolean deferAck = false;
                try {
                    if (message != null && message.getHeaders() != null) {
                        String v = message.getHeaders().get(io.github.cuihairu.redis.streaming.mq.MqHeaders.DEFER_ACK);
                        deferAck = "true".equalsIgnoreCase(v);
                    }
                } catch (Exception ignore) {
                }
                if (!deferAck) {
                    ackViaBackend(topic, consumerGroup, partitionId, streamOrNull, messageId, messageData);
                    log.debug("Message {} acknowledged", messageId);
                    MqMetrics.get().incAcked(consumer.keys.getTenant(), message.getTopic(), partitionId);
                } else {
                    log.debug("Message {} ack deferred by header {}", messageId, io.github.cuihairu.redis.streaming.mq.MqHeaders.DEFER_ACK);
                }
                break;

            case RETRY:
                requeueOrDeadLetter(streamOrNull, consumerGroup, messageId, partitionId, message, messageData);
                break;

            case FAIL:
            case DEAD_LETTER:
                if (sendToDeadLetterQueue(message, partitionId)) {
                    MqMetrics.get().incDeadLetter(consumer.keys.getTenant(), message.getTopic(), partitionId);
                    ackViaBackend(topic, consumerGroup, partitionId, streamOrNull, messageId, messageData);
                } else {
                    // DLQ write failed: leave the original un-acked in the PEL so it is not lost
                    log.error("DLQ write failed for message {}; left un-acked for redelivery", messageId);
                }
                break;
        }
    }

    /**
     * Parses, dispatches and accounts for a single stream entry or consumer.broker record. Replaces the
     * formerly duplicated consumer.broker/non-consumer.broker per-record loops in {@link RedisMessageConsumer#runPartitionWorker}.
     *
     * @param streamOrNull       live stream handle for direct Redis Stream reads; {@code null}
     *                           when records came from the consumer.broker
     * @param precheckPayloadRef consumer.broker reads additionally verify the payload hash reference
     *                           before parsing so lost payloads reach the DLQ even without a
     *                           parse failure
     */
    void processIncomingRecord(String topic, String consumerGroup, int partitionId,
                                       String messageId, Map<String, Object> data,
                                       RStream<String, Object> streamOrNull,
                                       MessageHandler handler, boolean precheckPayloadRef) {
        if (precheckPayloadRef) {
            // Pre-check hash-ref existence to route missing payloads to DLQ even if parse doesn't throw
            try {
                String ref = consumer.payloadLifecycleManager.extractPayloadHashRef(data);
                if (ref != null) {
                    org.redisson.api.RBucket<String> bucket = consumer.redissonClient.getBucket(ref, org.redisson.client.codec.StringCodec.INSTANCE);
                    boolean okRef = false;
                    try {
                        okRef = bucket.isExists() && bucket.get() != null;
                    } catch (Exception ignore) {
                    }
                    if (!okRef) {
                        handleMissingPayload(topic, consumerGroup, partitionId, messageId, data);
                        return;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        Message message;
        try {
            message = StreamEntryCodec.parsePartitionEntry(topic, messageId, data, consumer.payloadLifecycleManager);
        } catch (RuntimeException ex) {
            // Any parse failure (including malformed timestamp, corrupt payload, etc.)
            // is treated as poison — send to DLQ and ACK original to avoid infinite
            // re-claim loops (MQ-06). Payload-missing already handled by the same path.
            handleMissingPayload(topic, consumerGroup, partitionId, messageId, data, streamOrNull);
            return;
        }
        // include partitionId if present (support both numeric and string forms)
        Object pid = data.get("partitionId");
        int pidInt = partitionId;
        if (pid instanceof Number) {
            pidInt = ((Number) pid).intValue();
        } else if (pid != null) {
            try {
                pidInt = Integer.parseInt(pid.toString());
            } catch (Exception ignore) {
            }
        }
        message.getHeaders().put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID, Integer.toString(pidInt));
        long start = System.nanoTime();
        boolean permitAcquired = acquireInFlightPermit();
        long nowInFlight = consumer.inFlight.incrementAndGet();
        try {
            MqMetrics.get().setInFlight(consumer.consumerName, nowInFlight, consumer.options.getMaxInFlight());
        } catch (Throwable ignore) {
        }
        try {
            MessageHandleResult result = handler.handle(message);
            dispatchResult(topic, consumerGroup, messageId, partitionId, message, result, data, streamOrNull);
            MqMetrics.get().recordHandleLatency(consumer.keys.getTenant(), topic, pidInt, (System.nanoTime() - start) / 1_000_000);
            MqMetrics.get().incConsumed(consumer.keys.getTenant(), topic, pidInt);
        } catch (Exception e) {
            log.error("Error handling message {} from {}:{}", messageId, topic, partitionId, e);
            requeueOrDeadLetter(streamOrNull, consumerGroup, messageId, partitionId, message, data);
            MqMetrics.get().recordHandleLatency(consumer.keys.getTenant(), topic, pidInt, (System.nanoTime() - start) / 1_000_000);
            MqMetrics.get().incConsumed(consumer.keys.getTenant(), topic, pidInt);
        } finally {
            long after = consumer.inFlight.decrementAndGet();
            try {
                MqMetrics.get().setInFlight(consumer.consumerName, after, consumer.options.getMaxInFlight());
            } catch (Throwable ignore) {
            }
            if (permitAcquired) {
                releaseInFlightPermit();
            }
        }
    }

    // Detect payload-missing exception patterns from payload loaders
    private boolean isPayloadMissing(Throwable ex) {
        if (ex == null) return false;
        String msg = ex.getMessage();
        if (msg != null) {
            // Support both lifecycle and direct-hash loader error messages
            if (msg.contains("Payload not found") || msg.contains("Failed to load payload")
                    || msg.contains("Payload not found in hash") || msg.contains("Failed to load payload from hash")) {
                return true;
            }
        }
        Throwable c = ex.getCause();
        return c != null && isPayloadMissing(c);
    }

    // When payload key has expired/missing, DLQ and ACK the original to avoid poison pending
    private void handleMissingPayload(String topic, String group, int partitionId,
                                      String messageId, Map<String, Object> messageData) {
        handleMissingPayload(topic, group, partitionId, messageId, messageData, null);
    }

    void handleMissingPayload(String topic, String group, int partitionId,
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
            String ref = consumer.payloadLifecycleManager.extractPayloadHashRef(messageData);
            headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PAYLOAD_MISSING, "true");
            if (ref != null) headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PAYLOAD_MISSING_REF, ref);
            m.setHeaders(headers);

            // send to DLQ then ACK original (only ack when the DLQ write actually landed)
            if (sendToDeadLetterQueue(m, partitionId)) {
                MqMetrics.get().incDeadLetter(consumer.keys.getTenant(), topic, partitionId);
                try { MqMetrics.get().incPayloadMissing(topic, partitionId); } catch (Throwable ignore) {}
                ackViaBackend(topic, group, partitionId, streamOrNull, messageId, messageData);
            } else {
                log.error("DLQ write failed for missing-payload message {}; left un-acked", messageId);
            }
        } catch (Exception e) {
            log.error("Failed to handle missing payload for {}:{} id={}", topic, partitionId, messageId, e);
        }
    }

    private boolean sendToDeadLetterQueue(Message message, int partitionId) {
        try {
            io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterRecord record = new io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterRecord();
            record.originalTopic = message.getTopic();
            record.originalPartition = partitionId;
            record.payload = message.getPayload();
            record.retryCount = message.getRetryCount();
            record.maxRetries = message.getMaxRetries();
            record.headers = message.getHeaders() != null ? new java.util.HashMap<>(message.getHeaders()) : new java.util.HashMap<>();
            record.originalMessageId = message.getId();
            record.timestamp = message.getTimestamp() != null ? message.getTimestamp() : Instant.now();
            if (message.getKey() != null) record.headers.put("key", message.getKey());
            consumer.deadLetterService.send(record);
            log.warn("Message sent to dead letter queue via DeadLetterService: topic={}", message.getTopic());
            return true;
        } catch (Exception e) {
            log.error("Failed to send message to dead letter queue", e);
            return false;
        }
    }

    // createMessageFromData migrated to StreamEntryCodec

    void requeueOrDeadLetter(RStream<String, Object> stream,
                                     String consumerGroup,
                                     String messageId,
                                     int partitionId,
                                     Message message,
                                     Map<String, Object> messageData) {
        try {
            // Effective cap: the per-message maxRetries (codec default 3) can only be tightened
            // by the configured retry policy — retryMaxAttempts was previously dead config
            int effectiveMax = Math.min(message.getMaxRetries(), consumer.retryPolicy.getMaxAttempts());
            // If current retry count already at/over limit, send to DLQ immediately
            if (message.getRetryCount() >= effectiveMax) {
                if (sendToDeadLetterQueue(message, partitionId)) {
                    MqMetrics.get().incDeadLetter(consumer.keys.getTenant(), message.getTopic(), partitionId);
                    ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
                } else {
                    log.error("DLQ write failed for exhausted message {}; left un-acked for redelivery", messageId);
                }
                return;
            }
            // Preserve original message id across retries (stream entry id changes on retry re-enqueue).
            try {
                java.util.Map<String, String> headers = message.getHeaders();
                if (headers == null) {
                    headers = new java.util.HashMap<>();
                    message.setHeaders(headers);
                } else if (!(headers instanceof java.util.HashMap)) {
                    headers = new java.util.HashMap<>(headers);
                    message.setHeaders(headers);
                }
                String orig = headers.get(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID);
                if (orig == null || orig.isBlank()) {
                    headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID, messageId);
                }
            } catch (Exception ignore) {
            }
            // Re-enqueue first (directly for tiny backoffs, otherwise via the retry bucket),
            // then ACK the original. ACK-before-enqueue lost messages when the enqueue failed.
            int nextRetry = message.getRetryCount() + 1;
            long delayMs = consumer.retryPolicy.nextBackoffMs(nextRetry);
            long dueAt = System.currentTimeMillis() + delayMs;

            // If next retry would exceed max retries, dead-letter instead of rescheduling.
            if (nextRetry > effectiveMax) {
                if (sendToDeadLetterQueue(message, partitionId)) {
                    MqMetrics.get().incDeadLetter(consumer.keys.getTenant(), message.getTopic(), partitionId);
                    ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
                } else {
                    log.error("DLQ write failed for exhausted message {}; left un-acked for redelivery", messageId);
                }
                return;
            }

            String topic = message.getTopic();
            if (delayMs <= 50) {
                // Fast path: re-enqueue directly to stream for tiny backoffs to avoid relying on the mover
                boolean requeued = false;
                try {
                    Map<String, Object> data = new HashMap<>();
                    if (message.getPayload() != null) {
                        // Strings pass through verbatim; re-encoding them would corrupt the payload
                        // across retries (a literal "\"hello\"" after the second attempt).
                        data.put("payload", (message.getPayload() instanceof String)
                                ? message.getPayload() : objectToJson(message.getPayload()));
                    }
                    data.put("timestamp", Instant.now().toString());
                    data.put("retryCount", nextRetry);
                    data.put("maxRetries", message.getMaxRetries());
                    data.put("topic", topic);
                    data.put("partitionId", partitionId);
                    if (message.getKey() != null) data.put("key", message.getKey());
                    if (message.getHeaders() != null && !message.getHeaders().isEmpty()) data.put("headers", objectToJson(message.getHeaders()));
                    data.values().removeIf(java.util.Objects::isNull);
                    RStream<String, Object> target = (stream != null)
                            ? stream
                            : consumer.redissonClient.getStream(consumer.keys.partitionStreamKey(topic, partitionId), org.redisson.client.codec.StringCodec.INSTANCE);
                    target.add(StreamAddArgs.entries(data));
                    requeued = true;
                } catch (Exception ex) {
                    log.error("Failed to directly re-enqueue message {} for retry", messageId, ex);
                }
                if (requeued) {
                    MqMetrics.get().incRetried(consumer.keys.getTenant(), topic, partitionId);
                    ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
                    log.debug("Message {} re-enqueued directly for retry ({} ms)", messageId, delayMs);
                }
                return;
            }

            // Default path: schedule into retry bucket with backoff (Hash + ZSET id)
            boolean scheduled = false;
            try {
                String itemId = java.util.UUID.randomUUID().toString();
                String itemKey = consumer.keys.retryItemKey(topic, itemId);
                // Use StringCodec to ensure plain string fields readable by Lua (HGET) and avoid binary values
                org.redisson.api.RMap<String, String> item = consumer.redissonClient.getMap(itemKey, org.redisson.client.codec.StringCodec.INSTANCE);
                // Store as strings for Lua simplicity; payload/headers JSON-encoded
                item.put("topic", topic);
                item.put("partitionId", Integer.toString(partitionId));
                if (message.getPayload() != null) {
                    // Strings pass through verbatim; only non-String payloads are JSON-encoded.
                    item.put("payload", (message.getPayload() instanceof String)
                            ? (String) message.getPayload() : objectToJson(message.getPayload()));
                }
                item.put("key", message.getKey() != null ? message.getKey() : "");
                item.put("headers", objectToJson(message.getHeaders() != null ? message.getHeaders() : java.util.Collections.emptyMap()));
                item.put("retryCount", Integer.toString(nextRetry));
                item.put("maxRetries", Integer.toString(message.getMaxRetries()));
                String orig = "";
                try {
                    java.util.Map<String, String> headers = message.getHeaders();
                    if (headers != null) {
                        String v = headers.get(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID);
                        if (v != null) orig = v;
                    }
                } catch (Exception ignore) {
                }
                if (orig == null || orig.isBlank()) {
                    orig = message.getId() != null ? message.getId() : "";
                }
                item.put("originalMessageId", orig);

                String bucketKey = consumer.keys.retryBucketKey(topic);
                // Use StringCodec so ZSET members are plain strings (consumer.keys), matching hash consumer.keys for Lua mover
                org.redisson.api.RScoredSortedSet<String> bucket = consumer.redissonClient.getScoredSortedSet(bucketKey, org.redisson.client.codec.StringCodec.INSTANCE);
                bucket.add(dueAt, itemKey);
                scheduled = true;

                // Opportunistically trigger a near-term retry mover run to reduce latency/flakiness
                long jitterMs = Math.min(100L, Math.max(10L, delayMs));
                try {
                    consumer.schedulerPool.schedule(consumer::moveDueRetries, jitterMs, TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {}
            } catch (Exception ex) {
                log.error("Failed to schedule retry for message {} (topic {})", messageId, topic, ex);
            }
            if (scheduled) {
                MqMetrics.get().incRetried(consumer.keys.getTenant(), topic, partitionId);
                ackViaBackend(message.getTopic(), consumerGroup, partitionId, stream, messageId, messageData);
                log.debug("Message {} scheduled for retry at {} ({} ms)", messageId, dueAt, delayMs);
            }
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
            if (consumer.broker != null) {
                consumer.broker.ack(topic, consumerGroup, partitionId, messageId);
            } else if (streamOrNull != null) {
                streamOrNull.ack(consumerGroup, parseStreamId(messageId));
            } else {
                // fallback get stream just for ack
                String streamKey = consumer.keys.partitionStreamKey(topic, partitionId);
                consumer.redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE)
                        .ack(consumerGroup, parseStreamId(messageId));
            }
        } catch (Exception e) {
            // Do not swallow: ack failure means the message will remain pending and be reprocessed.
            log.warn("Failed to ack message {} topic={} group={} partition={}",
                    messageId, topic, consumerGroup, partitionId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException("Ack failed", e);
        }

        // Clean up large payload hash if exists (best-effort)
        if (messageData != null) {
            try {
                consumer.payloadLifecycleManager.deletePayloadHashFromStreamData(messageData);
            } catch (Exception e) {
                log.debug("Failed to cleanup payload hash for message {} topic={}", messageId, topic, e);
            }
        }

        // Update commit frontier (best-effort): keep max acknowledged id per group/partition.
        // MQ-11: the frontier is a max() reduced across concurrent ack workers, so the
        // compare-and-set runs inside Redis as one atomic Lua script — the old Java
        // read-then-put let two workers read the same prev and land the smaller id last,
        // regressing the frontier.
        // Audit fix (MQ-11): the frontier is also the retention trim boundary — it must
        // never pass an id still pending in the PEL. An earlier ack failure used to leave
        // its id pending while this message's successful ack advanced the frontier past
        // it, letting the retention housekeeper XTRIM the still-unacked message. Clamp
        // the CAS to the PEL minimum; an unreadable PEL skips the advance — the frontier
        // staying behind is the safe direction.
        try {
            org.redisson.api.RStream<String, Object> pelStream = streamOrNull;
            if (pelStream == null) {
                pelStream = consumer.redissonClient.getStream(
                        consumer.keys.partitionStreamKey(topic, partitionId),
                        org.redisson.client.codec.StringCodec.INSTANCE);
            }
            String pendingMin;
            try {
                pendingMin = oldestPendingId(pelStream, consumerGroup);
            } catch (Exception pe) {
                if (isNoGroupError(pe)) {
                    // a group without a PEL has nothing pending — the advance is safe
                    pendingMin = null;
                } else {
                    throw pe;
                }
            }
            if (pendingMin != null && compareStreamId(messageId, pendingMin) >= 0) {
                log.debug("Commit frontier held at the pending minimum (topic={}, group={}, partition={}, id={}, pendingMin={})",
                        topic, consumerGroup, partitionId, messageId, pendingMin);
                return;
            }
            String frontierKey = consumer.keys.commitFrontierKey(topic, partitionId);
            // The frontier hash is plain text ("ms-seq" under the group field, StringCodec):
            // a binary client codec would store ids the Lua comparison can never parse
            org.redisson.api.RScript script =
                    consumer.redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
            script.eval(org.redisson.api.RScript.Mode.READ_WRITE, COMMIT_FRONTIER_LUA,
                    org.redisson.api.RScript.ReturnType.LONG,
                    java.util.Collections.singletonList(frontierKey), consumerGroup, messageId);
        } catch (Exception e) {
            log.debug("Failed to update commit frontier for topic={} group={} partition={}",
                    topic, consumerGroup, partitionId, e);
        }
    }

    /**
     * The minimum id still pending in the group's PEL, or null when the PEL is empty.
     * Redis delivers in ascending id order, so every entry below the pending minimum
     * has been delivered and is not pending — the frontier may safely advance up to,
     * but never past, it. Throws when Redis cannot be consulted; the caller treats the
     * query failure as "unknown, do not advance".
     */
    private static String oldestPendingId(RStream<String, Object> stream, String group) {
        // the XPENDING summary form carries the lowest pending id in one command
        org.redisson.api.stream.PendingResult pending = stream.getPendingInfo(group);
        if (pending == null || pending.getTotal() <= 0) {
            return null;
        }
        org.redisson.api.stream.StreamMessageId lowest = pending.getLowestId();
        if (lowest == null || lowest.getId0() < 0) {
            return null;
        }
        return lowest.getId0() + "-" + Math.max(lowest.getId1(), 0);
    }

    /** True when Redis rejected the command with NOGROUP — the group has no PEL. */
    private static boolean isNoGroupError(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String m = t.getMessage();
            if (m != null && m.contains("NOGROUP")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** Ascending compare of plain text "ms-seq" ids (non-stream-id values fall back to text order). */
    private static int compareStreamId(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        try {
            String[] ap = a.split("-", 2);
            String[] bp = b.split("-", 2);
            int c = Long.compare(Long.parseLong(ap[0]), Long.parseLong(bp[0]));
            if (c != 0) return c;
            long ase = ap.length > 1 ? Long.parseLong(ap[1]) : 0L;
            long bse = bp.length > 1 ? Long.parseLong(bp[1]) : 0L;
            return Long.compare(ase, bse);
        } catch (Exception ignore) {
            return a.compareTo(b);
        }
    }

    /**
     * MQ-11: atomic frontier compare-and-set — HSET only when the acked stream id is
     * newer than the stored one. Values are plain text "ms-seq"; quotes are stripped
     * before matching so values written by a quoting codec stay comparable. A new id
     * that is not a stream id is never written; a stored value that is not a stream id
     * is overwritten (self-healing, e.g. hashes left by a previous binary codec).
     * Returns 1 when the frontier advanced.
     */
    private static final String COMMIT_FRONTIER_LUA = String.join("\n",
            "local prev = redis.call('HGET', KEYS[1], ARGV[1])",
            "local nv = string.gsub(ARGV[2], '\"', '')",
            "local nm, ns = string.match(nv, '^(%d+)-(%d+)$')",
            "if nm == nil then return 0 end",
            "if prev == false then",
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "  return 1",
            "end",
            "local pv = string.gsub(prev, '\"', '')",
            "local pm, ps = string.match(pv, '^(%d+)-(%d+)$')",
            "if pm == nil then",
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "  return 1",
            "end",
            "if tonumber(nm) > tonumber(pm) or (tonumber(nm) == tonumber(pm) and tonumber(ns) > tonumber(ps)) then",
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "  return 1",
            "end",
            "return 0");

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

    /**
     * @return whether a permit was actually acquired — {@code false} when the consumer
     * stopped/consumer.closed while waiting (MQ-08: the caller must not release then, or the
     * unpaired release permanently inflates the permits beyond maxInFlight)
     */
    boolean acquireInFlightPermit() {
        if (consumer.inFlightLimiter == null) {
            return true;
        }
        long startNs = System.nanoTime();
        boolean interrupted = false;
        while (consumer.running.get() && !consumer.closed.get()) {
            try {
                consumer.inFlightLimiter.acquire();
                long waitMs = (System.nanoTime() - startNs) / 1_000_000;
                if (waitMs > 0) {
                    try {
                        MqMetrics.get().recordBackpressureWait(consumer.consumerName, waitMs);
                    } catch (Throwable ignore) {
                    }
                }
                return true;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    void releaseInFlightPermit() {
        if (consumer.inFlightLimiter == null) {
            return;
        }
        try {
            consumer.inFlightLimiter.release();
        } catch (Exception ignore) {
        }
    }

    private <T> T jsonToObject(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failure", e);
        }
    }
}
