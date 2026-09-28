package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous dispatcher (relay) for the v2.5 outbox: reads committed records from the
 * outbox stream via a consumer group and delivers them to the target system until the
 * target confirms, then acks and trims the entry. See {@link RedisOutboxSink} for the
 * write side and docs/exactly-once.md 方案 C for the semantics.
 *
 * <p>Per entry resolution, in stream order:</p>
 * <ul>
 *   <li>{@code COMMITTED} epoch → deliver; ack + XDEL on success. On failure the entry
 *       stays pending and is retried after {@code retryIdleMs}.</li>
 *   <li>{@code ABORTED} epoch → ack + discard (never delivered).</li>
 *   <li>no marker yet (prepared but uncommitted, or unknown epoch) → leave pending and
 *       stop the round: strict head-of-line wait until the runtime commits the epoch
 *       (including via recovery), so an aborted epoch's successors can never overtake it
 *       into the target.</li>
 * </ul>
 *
 * <p>Delivery is at-least-once: a crash between delivery and ack replays the record on
 * the next round. Entries exceeding {@code maxAttempts} deliveries are moved to a DLQ
 * stream ({@code <outboxKey>:dlq}) with the original fields plus failure metadata.
 * End-to-end exactly-once therefore requires an idempotent target keyed on the record's
 * stable id.</p>
 *
 * @param <T> the payload type carried inside the staged {@link IdempotentRecord}
 */
@Slf4j
public final class RedisOutboxDispatcher<T> implements AutoCloseable {

    /** DLQ entry metadata fields added on top of the original outbox fields. */
    public static final String DLQ_FIELD_REASON = "dlqReason";
    public static final String DLQ_FIELD_ATTEMPTS = "failedAttempts";
    public static final String DLQ_FIELD_TIME = "dlqTime";

    /**
     * One delivery. Cross-system dedup must key on {@code id} — it is stable across
     * redelivery, while epoch/seq identify the record inside the WAL.
     */
    public record Delivery<T>(String id, String epoch, long seq, T value) {}

    /** Target-system callback; throwing keeps the entry pending for retry. */
    @FunctionalInterface
    public interface Listener<T> {
        void onDeliver(Delivery<T> delivery) throws Exception;
    }

    private final RedissonClient redissonClient;
    private final String outboxKey;
    private final String dlqKey;
    private final RStream<String, String> stream;
    private final RStream<String, String> dlqStream;
    private final RMap<String, String> epochs;
    private final ObjectMapper objectMapper;
    private final Class<T> valueType;
    private final Listener<T> listener;
    private final String group;
    private final String consumer;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryIdleMs;
    private final long pollIntervalMs;
    private volatile ScheduledExecutorService scheduler;

    public RedisOutboxDispatcher(RedissonClient redissonClient, String outboxKey, Class<T> valueType,
                                 Listener<T> listener) {
        this(redissonClient, outboxKey, valueType, listener,
                "outbox", "relay", 100, 5, 30_000L, 500L, outboxKey + ":dlq");
    }

    public RedisOutboxDispatcher(RedissonClient redissonClient, String outboxKey, Class<T> valueType,
                                 Listener<T> listener, String group, String consumer,
                                 int batchSize, int maxAttempts, long retryIdleMs, long pollIntervalMs,
                                 String dlqKey) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.outboxKey = Objects.requireNonNull(outboxKey, "outboxKey");
        this.dlqKey = Objects.requireNonNull(dlqKey, "dlqKey");
        this.stream = redissonClient.getStream(outboxKey, StringCodec.INSTANCE);
        this.dlqStream = redissonClient.getStream(dlqKey, StringCodec.INSTANCE);
        this.epochs = redissonClient.getMap(outboxKey + ":epochs", StringCodec.INSTANCE);
        this.objectMapper = new ObjectMapper();
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.group = Objects.requireNonNull(group, "group");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryIdleMs = retryIdleMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    public String getOutboxKey() {
        return outboxKey;
    }

    public String getDlqKey() {
        return dlqKey;
    }

    /** Creates the consumer group (id 0-0, whole history) and starts the periodic loop. */
    public void start() {
        ensureGroup();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-dispatcher-" + outboxKey);
            t.setDaemon(true);
            return t;
        });
        scheduler = executor;
        executor.scheduleWithFixedDelay(this::runSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Outbox dispatcher started for {} (group={}, consumer={}, interval={}ms)",
                outboxKey, group, consumer, pollIntervalMs);
    }

    public void stop() {
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
            log.info("Outbox dispatcher stopped for {}", outboxKey);
        }
    }

    @Override
    public void close() {
        stop();
    }

    void ensureGroup() {
        try {
            stream.createGroup(StreamCreateGroupArgs.name(group).id(new StreamMessageId(0, 0)).makeStream());
        } catch (Exception e) {
            // BUSYGROUP on restart, or a Redis quirk — the group existing is the goal
            log.debug("Outbox group creation skipped for {}/{}: {}", outboxKey, group, e.getMessage());
        }
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Outbox dispatch round failed for {}", outboxKey, e);
        }
    }

    /**
     * One delivery round: never-delivered entries first, then retriable pending entries.
     * Package-visible so tests can drive rounds deterministically.
     */
    void runOnce() {
        drainNew();
        retryPending();
    }

    private void drainNew() {
        Map<StreamMessageId, Map<String, String>> batch;
        try {
            batch = stream.readGroup(group, consumer, StreamReadGroupArgs.neverDelivered().count(batchSize));
        } catch (Exception e) {
            log.warn("Outbox {} readGroup failed; round skipped", outboxKey, e);
            return;
        }
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : batch.entrySet()) {
            if (!processEntry(entry.getKey(), entry.getValue())) {
                // failed delivery or head-of-line uncommitted epoch: stop the round,
                // everything (including this entry) stays pending for a later round
                break;
            }
        }
    }

    private void retryPending() {
        List<PendingEntry> pending;
        try {
            pending = stream.listPending(StreamPendingRangeArgs.groupName(group)
                    .startId(StreamMessageId.MIN)
                    .endId(StreamMessageId.MAX)
                    .count(batchSize)
                    .consumerName(consumer));
        } catch (Exception e) {
            log.warn("Outbox {} listPending failed; retry pass skipped", outboxKey, e);
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }

        Map<StreamMessageId, Long> toDlq = new HashMap<>();
        List<StreamMessageId> toRetry = new ArrayList<>();
        for (PendingEntry pe : pending) {
            if (pe.getDeliveryCount() > maxAttempts) {
                toDlq.put(pe.getId(), pe.getDeliveryCount());
            } else if (pe.getIdleTime() >= retryIdleMs) {
                toRetry.add(pe.getId());
            }
        }
        if (!toDlq.isEmpty()) {
            moveToDlq(toDlq);
        }
        if (!toRetry.isEmpty()) {
            Map<StreamMessageId, Map<String, String>> claimed;
            try {
                claimed = stream.claim(group, consumer, retryIdleMs, TimeUnit.MILLISECONDS,
                        toRetry.toArray(new StreamMessageId[0]));
            } catch (Exception e) {
                log.warn("Outbox {} claim of {} pending entries failed", outboxKey, toRetry.size(), e);
                return;
            }
            for (Map.Entry<StreamMessageId, Map<String, String>> entry : claimed.entrySet()) {
                if (!processEntry(entry.getKey(), entry.getValue())) {
                    break;
                }
            }
        }
    }

    /**
     * Resolves one entry against the epoch markers.
     *
     * @return true if the entry was fully handled (delivered, discarded or invalid) and
     *         the round may continue; false to stop the round and keep the entry pending.
     */
    private boolean processEntry(StreamMessageId id, Map<String, String> fields) {
        String epoch = fields == null ? null : fields.get(RedisOutboxSink.FIELD_EPOCH);
        if (epoch == null) {
            log.warn("Outbox entry {} carries no epoch field; discarding", id);
            ackAndRemove(id);
            return true;
        }

        String status = epochs.get(epoch);
        if (RedisOutboxSink.STATUS_ABORTED.equals(status)) {
            ackAndRemove(id);
            return true;
        }
        if (!RedisOutboxSink.STATUS_COMMITTED.equals(status)) {
            log.debug("Outbox entry {} waits on uncommitted epoch {}", id, epoch);
            return false;
        }

        try {
            listener.onDeliver(toDelivery(fields));
        } catch (Exception e) {
            log.warn("Outbox delivery failed for entry {} (epoch {}); kept pending for retry", id, epoch, e);
            return false;
        }
        ackAndRemove(id);
        return true;
    }

    private Delivery<T> toDelivery(Map<String, String> fields) throws Exception {
        String epoch = fields.get(RedisOutboxSink.FIELD_EPOCH);
        long seq = Long.parseLong(fields.getOrDefault(RedisOutboxSink.FIELD_SEQ, "0"));
        String payload = fields.getOrDefault(RedisOutboxSink.FIELD_PAYLOAD, "{}");
        JsonNode node = objectMapper.readTree(payload);
        String recordId = fields.get(RedisOutboxSink.FIELD_ID) != null
                ? fields.get(RedisOutboxSink.FIELD_ID)
                : idText(node);
        JsonNode valueNode = node.get("value");
        T value = valueNode == null || valueNode.isNull() ? null : objectMapper.treeToValue(valueNode, valueType);
        return new Delivery<>(recordId, epoch, seq, value);
    }

    private static String idText(JsonNode node) {
        JsonNode idNode = node.path("id");
        return idNode.isMissingNode() || idNode.isNull() ? null : idNode.asText();
    }

    private void moveToDlq(Map<StreamMessageId, Long> idsWithAttempts) {
        try {
            Map<StreamMessageId, Map<String, String>> bodies = stream.claim(group, consumer, 0,
                    TimeUnit.MILLISECONDS, idsWithAttempts.keySet().toArray(new StreamMessageId[0]));
            for (Map.Entry<StreamMessageId, Long> e : idsWithAttempts.entrySet()) {
                Map<String, String> fields = new LinkedHashMap<>(
                        bodies.getOrDefault(e.getKey(), Map.of()));
                fields.put(DLQ_FIELD_ATTEMPTS, String.valueOf(e.getValue()));
                fields.put(DLQ_FIELD_REASON, "max delivery attempts exceeded");
                fields.put(DLQ_FIELD_TIME, String.valueOf(System.currentTimeMillis()));
                dlqStream.add(StreamAddArgs.entries(fields));
                ackAndRemove(e.getKey());
                log.warn("Outbox entry {} moved to DLQ {} after {} attempts",
                        e.getKey(), dlqKey, e.getValue());
            }
        } catch (Exception ex) {
            log.warn("Failed to move outbox entries {} to DLQ {}", idsWithAttempts.keySet(), dlqKey, ex);
        }
    }

    /** Acknowledges the entry in the consumer group and trims it from the stream. */
    private void ackAndRemove(StreamMessageId id) {
        try {
            stream.ack(group, id);
        } catch (Exception e) {
            log.warn("Outbox {} ack failed for {}; it may redeliver", outboxKey, id, e);
        }
        try {
            stream.remove(id);
        } catch (Exception e) {
            log.warn("Outbox {} trim failed for {}; storage will grow", outboxKey, id, e);
        }
    }
}
