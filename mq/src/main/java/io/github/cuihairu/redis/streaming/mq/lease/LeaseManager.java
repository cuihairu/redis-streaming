package io.github.cuihairu.redis.streaming.mq.lease;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * Simple lease manager using Redis keys with TTL to coordinate partition ownership.
 */
@Slf4j
@RequiredArgsConstructor
public class LeaseManager {

    private final RedissonClient redissonClient;

    public boolean tryAcquire(String leaseKey, String ownerId, long ttlSeconds) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey);
            // Use non-deprecated API: setIfAbsent(value) + expire(Duration)
            boolean ok = bucket.setIfAbsent(ownerId);
            if (ok) {
                boolean exp = bucket.expire(java.time.Duration.ofSeconds(ttlSeconds));
                if (!exp) {
                    // Best-effort cleanup on failure to set TTL
                    bucket.delete();
                    return false;
                }
            }
            return ok;
        } catch (Exception e) {
            log.error("Failed to acquire lease {}", leaseKey, e);
            return false;
        }
    }

    public boolean renewIfOwner(String leaseKey, String ownerId, long ttlSeconds) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey);
            String cur = bucket.get();
            if (ownerId.equals(cur)) {
                return bucket.expire(java.time.Duration.ofSeconds(ttlSeconds));
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to renew lease {}", leaseKey, e);
            return false;
        }
    }

    public boolean isOwner(String leaseKey, String ownerId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey);
            String cur = bucket.get();
            return ownerId.equals(cur);
        } catch (Exception e) {
            log.error("Failed to read lease {}", leaseKey, e);
            return false;
        }
    }

    public void releaseIfOwner(String leaseKey, String ownerId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(leaseKey);
            String cur = bucket.get();
            if (ownerId.equals(cur)) {
                bucket.delete();
            }
        } catch (Exception e) {
            log.error("Failed to release lease {}", leaseKey, e);
        }
    }
}
