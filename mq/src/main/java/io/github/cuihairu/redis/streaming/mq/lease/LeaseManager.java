package io.github.cuihairu.redis.streaming.mq.lease;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Simple lease manager using Redis keys with TTL to coordinate partition ownership.
 *
 * <p>All compare-and-act operations run atomically inside Redis (SET NX EX for acquisition,
 * Lua compare-and-act for renewal/release), so a crash between value check and expiry update can
 * never wedge a lease forever nor delete a successor's fresh lease.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class LeaseManager {

    private static final String RENEW_IF_OWNER_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                    "else return 0 end";

    private static final String RELEASE_IF_OWNER_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    private final RedissonClient redissonClient;

    public boolean tryAcquire(String leaseKey, String ownerId, long ttlSeconds) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey, StringCodec.INSTANCE);
            // Atomic SET NX EX: never leaves a TTL-less lease key behind after a crash
            // between value write and expiry (which made the key stick forever).
            return bucket.setIfAbsent(ownerId, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.error("Failed to acquire lease {}", leaseKey, e);
            return false;
        }
    }

    public boolean renewIfOwner(String leaseKey, String ownerId, long ttlSeconds) {
        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Long renewed = script.eval(RScript.Mode.READ_WRITE, RENEW_IF_OWNER_SCRIPT,
                    RScript.ReturnType.LONG, List.of(leaseKey),
                    ownerId, TimeUnit.SECONDS.toMillis(ttlSeconds));
            return renewed != null && renewed == 1L;
        } catch (Exception e) {
            log.error("Failed to renew lease {}", leaseKey, e);
            return false;
        }
    }

    public boolean isOwner(String leaseKey, String ownerId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey, StringCodec.INSTANCE);
            String cur = bucket.get();
            return ownerId.equals(cur);
        } catch (Exception e) {
            log.error("Failed to read lease {}", leaseKey, e);
            return false;
        }
    }

    public void releaseIfOwner(String leaseKey, String ownerId) {
        try {
            // Compare-and-delete in one script: a GET-then-DELETE race could otherwise delete
            // a successor's freshly acquired lease after this owner's key expired in between.
            redissonClient.getScript(StringCodec.INSTANCE)
                    .eval(RScript.Mode.READ_WRITE, RELEASE_IF_OWNER_SCRIPT,
                            RScript.ReturnType.LONG, List.of(leaseKey), ownerId);
        } catch (Exception e) {
            log.error("Failed to release lease {}", leaseKey, e);
        }
    }
}
