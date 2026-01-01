package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redis-only sink that buffers side effects and flushes them on checkpoint completion.
 *
 * <p>This pattern is useful with {@code deferAckUntilCheckpoint=true}: messages are kept pending
 * until checkpoint completion, and sink side effects become visible only after the checkpoint
 * succeeds.</p>
 *
 * <p>Writes are idempotent (per record id) via {@link RedisIdempotentListSink}.</p>
 */
public final class RedisCheckpointedIdempotentListSink<T> implements CheckpointAwareSink<IdempotentRecord<T>> {

    private static final long serialVersionUID = 1L;

    private final RedisIdempotentListSink<T> delegate;
    private final ConcurrentLinkedQueue<IdempotentRecord<T>> buffer = new ConcurrentLinkedQueue<>();

    public RedisCheckpointedIdempotentListSink(RedissonClient redissonClient, String dedupSetKey, String listKey) {
        this(redissonClient, dedupSetKey, listKey, null);
    }

    public RedisCheckpointedIdempotentListSink(RedissonClient redissonClient,
                                               String dedupSetKey,
                                               String listKey,
                                               Duration dedupTtl) {
        Objects.requireNonNull(redissonClient, "redissonClient");
        this.delegate = new RedisIdempotentListSink<>(redissonClient, dedupSetKey, listKey, null, dedupTtl);
    }

    @Override
    public void invoke(IdempotentRecord<T> value) {
        if (value == null) {
            return;
        }
        buffer.add(value);
    }

    @Override
    public void onCheckpointComplete(long checkpointId) throws Exception {
        List<IdempotentRecord<T>> batch = drainAll();
        for (IdempotentRecord<T> r : batch) {
            delegate.invoke(r);
        }
    }

    @Override
    public void onCheckpointAbort(long checkpointId, Throwable cause) {
        buffer.clear();
    }

    @Override
    public void onCheckpointRestore(long checkpointId) {
        buffer.clear();
    }

    private List<IdempotentRecord<T>> drainAll() {
        List<IdempotentRecord<T>> out = new ArrayList<>();
        while (true) {
            IdempotentRecord<T> v = buffer.poll();
            if (v == null) {
                return out;
            }
            out.add(v);
        }
    }
}

