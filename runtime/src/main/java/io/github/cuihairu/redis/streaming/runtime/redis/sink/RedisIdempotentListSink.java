package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Redis idempotent sink that appends to a Redis list at most once per idempotency key.
 *
 * <p>This sink uses a Redis Lua script to atomically:
 * <ol>
 *   <li>Check whether {@code id} has been seen (SISMEMBER)</li>
 *   <li>If not seen: record {@code id} (SADD + optional EXPIRE)</li>
 *   <li>Append payload to list (RPUSH)</li>
 * </ol>
 *
 * <p>Note: on Redis Cluster, {@code dedupSetKey} and {@code listKey} must be in the same hash slot
 * (use hash tags like {@code key:{job}:...}).</p>
 */
public final class RedisIdempotentListSink<T> implements StreamSink<IdempotentRecord<T>> {

    private static final long serialVersionUID = 1L;

    private static final String LUA =
            "local seen = redis.call('SISMEMBER', KEYS[1], ARGV[1]) \n" +
            "if seen == 1 then return 0 end \n" +
            "redis.call('SADD', KEYS[1], ARGV[1]) \n" +
            "local ttl = tonumber(ARGV[3]) \n" +
            "if ttl ~= nil and ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end \n" +
            "redis.call('RPUSH', KEYS[2], ARGV[2]) \n" +
            "return 1";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String dedupSetKey;
    private final String listKey;
    private final Duration dedupTtl;

    public RedisIdempotentListSink(RedissonClient redissonClient, String dedupSetKey, String listKey) {
        this(redissonClient, dedupSetKey, listKey, null, null);
    }

    public RedisIdempotentListSink(RedissonClient redissonClient,
                                   String dedupSetKey,
                                   String listKey,
                                   ObjectMapper objectMapper,
                                   Duration dedupTtl) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.dedupSetKey = Objects.requireNonNull(dedupSetKey, "dedupSetKey");
        this.listKey = Objects.requireNonNull(listKey, "listKey");
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.dedupTtl = dedupTtl;
    }

    @Override
    public void invoke(IdempotentRecord<T> record) throws Exception {
        if (record == null) {
            return;
        }
        String id = record.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("record.id must not be blank");
        }
        String payload = encode(record.value());
        long ttlSeconds = dedupTtl == null ? 0L : Math.max(0L, dedupTtl.toSeconds());

        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        Object r = script.eval(RScript.Mode.READ_WRITE, LUA, RScript.ReturnType.INTEGER,
                List.of(dedupSetKey, listKey), id, payload, String.valueOf(ttlSeconds));
        // r is 0/1; ignore result (idempotency handled by Lua).
    }

    private String encode(Object v) throws Exception {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return s;
        }
        return objectMapper.writeValueAsString(v);
    }
}

