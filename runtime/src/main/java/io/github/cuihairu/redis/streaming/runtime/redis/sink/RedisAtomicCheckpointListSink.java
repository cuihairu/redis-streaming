package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Redis-only exactly-once sink (checkpoint aligned) that commits side effects and ACKs atomically.
 *
 * <p>On {@link #invoke(RedisExactlyOnceRecord)} it buffers records. On checkpoint completion it
 * runs a single Lua script per record to atomically:
 * <ul>
 *   <li>Deduplicate by {@code idempotencyKey}</li>
 *   <li>Append payload to a Redis list (RPUSH)</li>
 *   <li>ACK the Redis Streams message (XACK)</li>
 *   <li>Advance commit frontier (HSET max)</li>
 * </ul>
 *
 * <p>Cluster requirement: all {@code KEYS} passed to Lua must be in the same hash slot. Ensure
 * MQ key prefixes and sink keys share the same hash tag.</p>
 */
public final class RedisAtomicCheckpointListSink<T> implements CheckpointAwareSink<RedisExactlyOnceRecord<T>> {

    private static final long serialVersionUID = 1L;

    private static final String LUA =
            "local function parse_id(id) \n" +
            "  local dash = string.find(id, '-') \n" +
            "  if dash then \n" +
            "    local ms = tonumber(string.sub(id, 1, dash-1)) or 0 \n" +
            "    local seq = tonumber(string.sub(id, dash+1)) or 0 \n" +
            "    return ms, seq \n" +
            "  end \n" +
            "  return tonumber(id) or 0, 0 \n" +
            "end \n" +
            "local function is_greater(a, b) \n" +
            "  local ams, aseq = parse_id(a) \n" +
            "  local bms, bseq = parse_id(b) \n" +
            "  if ams > bms then return true end \n" +
            "  if ams < bms then return false end \n" +
            "  return aseq > bseq \n" +
            "end \n" +
            "local group = ARGV[1] \n" +
            "local ttl = tonumber(ARGV[2]) \n" +
            "local maxid = nil \n" +
            "local committed = 0 \n" +
            "if ttl ~= nil and ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end \n" +
            "local i = 3 \n" +
            "while i <= #ARGV do \n" +
            "  local idkey = ARGV[i] \n" +
            "  local payload = ARGV[i+1] \n" +
            "  local msgid = ARGV[i+2] \n" +
            "  local seen = redis.call('SISMEMBER', KEYS[1], idkey) \n" +
            "  if seen == 0 then \n" +
            "    redis.call('SADD', KEYS[1], idkey) \n" +
            "    redis.call('RPUSH', KEYS[2], payload) \n" +
            "    committed = committed + 1 \n" +
            "  end \n" +
            "  redis.call('XACK', KEYS[3], group, msgid) \n" +
            "  if (not maxid) or is_greater(msgid, maxid) then maxid = msgid end \n" +
            "  i = i + 3 \n" +
            "end \n" +
            "if maxid then \n" +
            "  local prev = redis.call('HGET', KEYS[4], group) \n" +
            "  if (not prev) or is_greater(maxid, prev) then redis.call('HSET', KEYS[4], group, maxid) end \n" +
            "end \n" +
            "return committed";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String dedupSetKey;
    private final String listKey;
    private final Duration dedupTtl;

    private final ConcurrentLinkedQueue<RedisExactlyOnceRecord<T>> buffer = new ConcurrentLinkedQueue<>();

    public RedisAtomicCheckpointListSink(RedissonClient redissonClient, String dedupSetKey, String listKey) {
        this(redissonClient, dedupSetKey, listKey, null, null);
    }

    public RedisAtomicCheckpointListSink(RedissonClient redissonClient,
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
    public void invoke(RedisExactlyOnceRecord<T> record) {
        if (record == null) {
            return;
        }
        buffer.add(record);
    }

    @Override
    public void onCheckpointComplete(long checkpointId) throws Exception {
        List<RedisExactlyOnceRecord<T>> batch = drainAll();
        if (batch.isEmpty()) {
            return;
        }
        long ttlSeconds = dedupTtl == null ? 0L : Math.max(0L, dedupTtl.toSeconds());
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);

        java.util.Map<String, java.util.List<RedisExactlyOnceRecord<T>>> groups = new java.util.HashMap<>();
        for (RedisExactlyOnceRecord<T> r : batch) {
            String k = r.topic() + "|" + r.consumerGroup() + "|" + r.partitionId();
            groups.computeIfAbsent(k, kk -> new java.util.ArrayList<>()).add(r);
        }

        for (java.util.List<RedisExactlyOnceRecord<T>> rs : groups.values()) {
            if (rs.isEmpty()) continue;
            RedisExactlyOnceRecord<T> first = rs.get(0);
            String streamKey = StreamKeys.partitionStream(first.topic(), first.partitionId());
            String frontierKey = StreamKeys.commitFrontier(first.topic(), first.partitionId());

            java.util.List<Object> args = new java.util.ArrayList<>(2 + rs.size() * 3);
            args.add(first.consumerGroup());
            args.add(String.valueOf(ttlSeconds));
            for (RedisExactlyOnceRecord<T> r : rs) {
                args.add(r.idempotencyKey());
                args.add(encode(r.value()));
                args.add(r.messageId());
            }
            script.eval(RScript.Mode.READ_WRITE, LUA, RScript.ReturnType.INTEGER,
                    List.of(dedupSetKey, listKey, streamKey, frontierKey), args.toArray());
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

    private List<RedisExactlyOnceRecord<T>> drainAll() {
        List<RedisExactlyOnceRecord<T>> out = new ArrayList<>();
        while (true) {
            RedisExactlyOnceRecord<T> v = buffer.poll();
            if (v == null) {
                return out;
            }
            out.add(v);
        }
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
