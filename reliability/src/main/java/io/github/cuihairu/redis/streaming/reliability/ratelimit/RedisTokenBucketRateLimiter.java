package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Redis-backed token bucket limiter (per-key).
 *
 * Maintains a HASH per key with fields:
 *  - tokens: double (current tokens)
 *  - ts: last refill timestamp (ms)
 *
 * Lua ensures atomic refill and consume. Keys expire after an idle period derived from capacity/rate.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final double capacity;
    private final double ratePerMs; // tokens per ms
    private final long expireMs;    // key TTL in ms
    private final RScript stringScript;

    private static final String LUA =
            // KEYS[1] = hash key
            // ARGV[1] = capacity (double)
            // ARGV[2] = ratePerMs (double)
            // ARGV[3] = nowMillis (long)
            // ARGV[4] = expireMs (long)
            "local k=KEYS[1];" +
            "local cap=tonumber(ARGV[1]);" +
            "local rate=tonumber(ARGV[2]);" +
            "local now=tonumber(ARGV[3]);" +
            "local ttl=tonumber(ARGV[4]);" +
            // read current state
            "local tokens=tonumber(redis.call('HGET', k, 'tokens'));" +
            "local ts=tonumber(redis.call('HGET', k, 'ts'));" +
            "if tokens == nil then tokens = cap; ts = now; end;" +
            // refill
            "local delta = now - ts; if delta < 0 then delta = 0 end;" +
            "tokens = math.min(cap, tokens + delta * rate);" +
            "ts = now;" +
            // try consume
            "if tokens >= 1.0 then tokens = tokens - 1.0; " +
            "  redis.call('HSET', k, 'tokens', tostring(tokens), 'ts', tostring(ts));" +
            "  redis.call('PEXPIRE', k, ttl);" +
            "  return 1;" +
            "else " +
            "  redis.call('HSET', k, 'tokens', tostring(tokens), 'ts', tostring(ts));" +
            "  redis.call('PEXPIRE', k, ttl);" +
            "  return 0; end";

    public RedisTokenBucketRateLimiter(RedissonClient redisson, String keyPrefix, double capacity, double ratePerSecond) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "streaming:tb";
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be positive");
        this.keyPrefix = keyPrefix;
        this.capacity = capacity;
        this.ratePerMs = ratePerSecond / 1000.0d;
        // expire after roughly time to refill full bucket twice (safeguard against orphan keys)
        long msForFull = (long)Math.ceil((capacity / ratePerSecond) * 1000.0);
        this.expireMs = Math.max(2000L, msForFull * 2);
        this.stringScript = redisson.getScript(StringCodec.INSTANCE);
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        String k = keyPrefix + ":{" + key + "}:tb";
        List<Object> keys = Arrays.asList(k);
        Object[] args = new Object[]{
                String.valueOf(capacity),
                String.valueOf(ratePerMs),
                String.valueOf(nowMillis),
                String.valueOf(expireMs)
        };
        Boolean ok = stringScript.eval(RScript.Mode.READ_WRITE, LUA, RScript.ReturnType.BOOLEAN, keys, args);
        return ok != null && ok;
    }
}
