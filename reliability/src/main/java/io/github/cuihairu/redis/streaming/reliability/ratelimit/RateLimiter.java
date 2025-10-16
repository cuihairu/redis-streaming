package io.github.cuihairu.redis.streaming.reliability.ratelimit;

/**
 * Rate limiter interface. Implementations determine whether a request identified by a key
 * should be allowed under the configured policy.
 */
public interface RateLimiter {

    /**
     * Attempt to allow one request for the given key at current time.
     * @param key logical bucket key (e.g., userId, IP)
     * @return true if allowed; false if rate limited
     */
    default boolean allow(String key) {
        return allowAt(key, System.currentTimeMillis());
    }

    /**
     * Attempt to allow one request at the specified timestamp (milliseconds since epoch).
     * Primarily intended for testing or virtual time control.
     * @param key logical bucket key (e.g., userId, IP)
     * @param nowMillis current time in milliseconds
     * @return true if allowed; false if rate limited
     */
    boolean allowAt(String key, long nowMillis);
}

