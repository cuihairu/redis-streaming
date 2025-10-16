package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Redis-backed sliding window rate limiter using a ZSET per key.
 *
 * Algorithm (atomic via Lua):
 * - ZREMRANGEBYSCORE key 0 (now-window)
 * - ZCARD key -> count
 * - if {@code count < limit} then ZADD key now member; PEXPIRE key window; set TTL on seq key; return 1 else 0
 */
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final long windowMs;
    private final int limit;

    private static final String LUA =
            "local key=KEYS[1];" +
            "local seqKey=KEYS[2];" +
            "local window=tonumber(ARGV[1]);" +
            "local limit=tonumber(ARGV[2]);" +
            "local now=tonumber(ARGV[3]);" +
            // evict outdated
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window);" +
            // current count
            "local count=redis.call('ZCARD', key);" +
            "if count < limit then " +
            // generate unique member id using per-key seq
            "  local seq=redis.call('INCRBY', seqKey, 1);" +
            "  local member=tostring(now)..':'..tostring(seq);" +
            "  redis.call('ZADD', key, now, member);" +
            "  redis.call('PEXPIRE', key, window);" +
            "  redis.call('PEXPIRE', seqKey, window);" +
            "  return 1;" +
            "else return 0; end";

    public RedisSlidingWindowRateLimiter(RedissonClient redisson, String keyPrefix, long windowMs, int limit) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "streaming:rl";
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.keyPrefix = keyPrefix;
        this.windowMs = windowMs;
        this.limit = limit;
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        String k = formatKey(key);
        String seq = k + ":seq";
        List<Object> keys = Arrays.asList(k, seq);
        List<Object> args = Arrays.asList(windowMs, limit, nowMillis);
        Boolean ok = redisson.getScript().eval(RScript.Mode.READ_WRITE, LUA, RScript.ReturnType.BOOLEAN, keys, args.toArray());
        return ok != null && ok;
    }

    private String formatKey(String key) {
        // use Redis hash tag to keep same-slot keys when in cluster
        return keyPrefix + ":{" + key + "}";
    }
}
