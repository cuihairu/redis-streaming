package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v2.5 Outbox/WAL sink: side effects are staged into a durable Redis Stream (the outbox)
 * and become deliverable only after the owning checkpoint commits — the compromise route
 * to cross-system exactly-once (docs/exactly-once.md, 方案 C). The external delivery
 * itself is performed asynchronously by {@link RedisOutboxDispatcher}, so the pipeline's
 * checkpoint only has to guarantee that outbox records are neither lost nor reordered.
 *
 * <p>The sink implements {@link TwoPhaseCommitSink} and is therefore driven automatically
 * by the runtime's checkpoint flow (detect via {@code instanceof}, buffer per transaction
 * epoch, flush at {@code preCommit}, publish at {@code commit}) without any env wiring:</p>
 *
 * <ol>
 *   <li>{@link #invoke(IdempotentRecord, OutboxTxn)} — buffers the record in memory,
 *       nothing touches Redis yet.</li>
 *   <li>{@link #preCommit(OutboxTxn)} — flushes the epoch's records into the outbox
 *       stream (durable, but invisible to dispatchers because the epoch has no commit
 *       marker yet).</li>
 *   <li>{@link #commit(OutboxTxn)} / {@link #recoverAndCommit(OutboxTxn)} — writes the
 *       epoch marker {@code COMMITTED} into {@code <outboxKey>:epochs}; one atomic HSET
 *       flips the whole epoch visible.</li>
 *   <li>{@link #recoverAndAbort(OutboxTxn)} (and the inherited abort) — writes
 *       {@code ABORTED}; the dispatcher discards such entries instead of letting them
 *       block the stream head forever.</li>
 * </ol>
 *
 * <p>Semantics: delivery into the target system is <em>at-least-once</em> (a dispatcher
 * crash between delivery and ack replays the record). End-to-end exactly-once is reached
 * by making the target idempotent per the record's stable id (see {@link IdempotentRecord}
 * and the v1 route). Ordering within an epoch is preserved by per-record sequence
 * numbers; under retries, cross-record ordering is best-effort.</p>
 *
 * @param <T> the payload type carried inside {@link IdempotentRecord}
 */
@Slf4j
public final class RedisOutboxSink<T> implements TwoPhaseCommitSink<IdempotentRecord<T>, RedisOutboxSink.OutboxTxn> {

    private static final long serialVersionUID = 1L;

    /** Epoch marker values stored in the {@code <outboxKey>:epochs} hash. */
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_ABORTED = "ABORTED";

    /** Outbox stream entry field names. */
    public static final String FIELD_EPOCH = "epoch";
    public static final String FIELD_SEQ = "seq";
    public static final String FIELD_ID = "id";
    public static final String FIELD_PAYLOAD = "payload";

    /**
     * The serializable transaction handle persisted into the checkpoint snapshot. Recovery
     * only needs the epoch: markers are idempotent HSETs, so a replayed
     * {@code recoverAndCommit} after a crash between checkpoint store and commit is safe.
     */
    public record OutboxTxn(String epoch) implements Serializable {
        public OutboxTxn {
            Objects.requireNonNull(epoch, "epoch");
        }
    }

    private final RedissonClient redissonClient;
    private final String outboxKey;
    private final String epochsKey;
    private final ObjectMapper objectMapper;
    private final Map<String, List<IdempotentRecord<T>>> buffers = new ConcurrentHashMap<>();

    public RedisOutboxSink(RedissonClient redissonClient, String outboxKey) {
        this(redissonClient, outboxKey, new ObjectMapper());
    }

    public RedisOutboxSink(RedissonClient redissonClient, String outboxKey, ObjectMapper objectMapper) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.outboxKey = Objects.requireNonNull(outboxKey, "outboxKey");
        this.epochsKey = outboxKey + ":epochs";
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String getOutboxKey() {
        return outboxKey;
    }

    public String getEpochsKey() {
        return epochsKey;
    }

    @Override
    public OutboxTxn beginTxn() {
        return new OutboxTxn(UUID.randomUUID().toString());
    }

    @Override
    public void invoke(IdempotentRecord<T> value, OutboxTxn txn) {
        Objects.requireNonNull(txn, "txn");
        if (value == null) {
            return;
        }
        buffers.computeIfAbsent(txn.epoch(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(value);
    }

    /**
     * Flushes the epoch's buffered records into the outbox stream with their per-epoch
     * sequence numbers. If an append fails midway the exception propagates (the runtime
     * then aborts the epoch): the partially flushed entries end up under an {@code ABORTED}
     * marker and the dispatcher discards them, so a half-prepared epoch never delivers.
     */
    @Override
    public void preCommit(OutboxTxn txn) throws Exception {
        Objects.requireNonNull(txn, "txn");
        List<IdempotentRecord<T>> buffer = buffers.get(txn.epoch());
        if (buffer == null || buffer.isEmpty()) {
            return;
        }
        List<IdempotentRecord<T>> snapshot;
        synchronized (buffer) {
            snapshot = new ArrayList<>(buffer);
        }
        RStream<String, String> stream = redissonClient.getStream(outboxKey, StringCodec.INSTANCE);
        for (int i = 0; i < snapshot.size(); i++) {
            IdempotentRecord<T> record = snapshot.get(i);
            stream.add(StreamAddArgs.entries(Map.of(
                    FIELD_EPOCH, txn.epoch(),
                    FIELD_SEQ, String.valueOf(i),
                    FIELD_ID, record.id(),
                    FIELD_PAYLOAD, objectMapper.writeValueAsString(record))));
        }
        log.info("Outbox {} prepared epoch {} with {} record(s)", outboxKey, txn.epoch(), snapshot.size());
    }

    @Override
    public void commit(OutboxTxn txn) {
        markEpoch(txn, STATUS_COMMITTED);
    }

    @Override
    public OutboxTxn recoverAndCommit(OutboxTxn txn) {
        markEpoch(txn, STATUS_COMMITTED);
        return txn;
    }

    @Override
    public OutboxTxn recoverAndAbort(OutboxTxn txn) {
        markEpoch(txn, STATUS_ABORTED);
        return txn;
    }

    private void markEpoch(OutboxTxn txn, String status) {
        Objects.requireNonNull(txn, "txn");
        RMap<String, String> epochs = redissonClient.getMap(epochsKey, StringCodec.INSTANCE);
        epochs.put(txn.epoch(), status);
        buffers.remove(txn.epoch());
        log.info("Outbox {} epoch {} marked {}", outboxKey, txn.epoch(), status);
    }
}
