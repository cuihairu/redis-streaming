package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis Hash Sink for writing key-value data to Redis Hash.
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
@Slf4j
public class RedisHashSink<K, V> {

    private final RedissonClient redissonClient;
    private final String hashName;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * Create a Redis Hash sink without TTL.
     *
     * @param redissonClient the Redisson client
     * @param hashName       the Redis Hash name
     */
    public RedisHashSink(RedissonClient redissonClient, String hashName) {
        this(redissonClient, hashName, new ObjectMapper(), null);
    }

    /**
     * Create a Redis Hash sink with TTL.
     *
     * @param redissonClient the Redisson client
     * @param hashName       the Redis Hash name
     * @param objectMapper   the JSON object mapper
     * @param ttl            time-to-live for the hash (null for no expiration)
     */
    public RedisHashSink(
            RedissonClient redissonClient,
            String hashName,
            ObjectMapper objectMapper,
            Duration ttl) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(hashName, "Hash name cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");

        this.redissonClient = redissonClient;
        this.hashName = hashName;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * Write a key-value pair to the hash.
     *
     * @param key   the key
     * @param value the value
     */
    public void write(K key, V value) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");

        try {
            RMap<String, String> map = redissonClient.getMap(hashName);

            String keyStr = convertToString(key);
            String valueStr = convertToString(value);

            map.put(keyStr, valueStr);

            if (ttl != null) {
                map.expire(ttl);
            }

            log.debug("Written to Redis Hash {}: {} = {}", hashName, keyStr, valueStr);

        } catch (Exception e) {
            log.error("Failed to write to Redis Hash: {}", hashName, e);
            throw new RuntimeException("Failed to write to Redis Hash", e);
        }
    }

    /**
     * Write a key-value pair asynchronously.
     *
     * @param key   the key
     * @param value the value
     * @return a CompletableFuture
     */
    public CompletableFuture<Void> writeAsync(K key, V value) {
        return CompletableFuture.runAsync(() -> write(key, value));
    }

    /**
     * Write multiple key-value pairs in batch.
     *
     * @param entries the entries to write
     */
    public void writeBatch(Map<K, V> entries) {
        Objects.requireNonNull(entries, "Entries cannot be null");

        try {
            RMap<String, String> map = redissonClient.getMap(hashName);

            Map<String, String> convertedEntries = new java.util.HashMap<>();
            for (Map.Entry<K, V> entry : entries.entrySet()) {
                String keyStr = convertToString(entry.getKey());
                String valueStr = convertToString(entry.getValue());
                convertedEntries.put(keyStr, valueStr);
            }

            map.putAll(convertedEntries);

            if (ttl != null) {
                map.expire(ttl);
            }

            log.debug("Written batch to Redis Hash {}: {} entries", hashName, entries.size());

        } catch (Exception e) {
            log.error("Failed to write batch to Redis Hash", e);
            throw new RuntimeException("Failed to write batch to Redis Hash", e);
        }
    }

    /**
     * Write with field-level TTL (requires Redis 7.4+).
     * Note: This feature is only available in Redis 7.4+ with HPEXPIRE command.
     * For older versions, this method falls back to hash-level TTL.
     *
     * @param key       the key
     * @param value     the value
     * @param fieldTtl  time-to-live for this specific field
     */
    public void writeWithFieldTTL(K key, V value, Duration fieldTtl) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        Objects.requireNonNull(fieldTtl, "Field TTL cannot be null");

        // Write the value
        write(key, value);

        // Note: Field-level TTL requires Redis 7.4+ with HPEXPIRE command
        // This is not yet supported in Redisson 3.52.0
        // For now, we use hash-level TTL
        log.warn("Field-level TTL is not supported in current Redisson version. Using hash-level TTL instead.");
        if (ttl != null) {
            RMap<String, String> map = redissonClient.getMap(hashName);
            map.expire(fieldTtl);
        }
    }

    /**
     * Delete a key from the hash.
     *
     * @param key the key to delete
     * @return the previous value, or null if not found
     */
    public V delete(K key) {
        try {
            RMap<String, String> map = redissonClient.getMap(hashName);
            String keyStr = convertToString(key);
            String removed = map.remove(keyStr);

            if (removed == null) {
                return null;
            }

            try {
                return objectMapper.readValue(removed, (Class<V>) Object.class);
            } catch (Exception e) {
                return (V) removed;
            }
        } catch (Exception e) {
            log.error("Failed to delete from Redis Hash", e);
            throw new RuntimeException("Failed to delete from Redis Hash", e);
        }
    }

    /**
     * Get the hash size.
     *
     * @return the number of fields in the hash
     */
    public int getHashSize() {
        RMap<String, String> map = redissonClient.getMap(hashName);
        return map.size();
    }

    /**
     * Clear all entries from the hash.
     */
    public void clear() {
        RMap<String, String> map = redissonClient.getMap(hashName);
        map.clear();
        log.info("Cleared Redis Hash: {}", hashName);
    }

    /**
     * Delete the hash.
     */
    public void deleteHash() {
        RMap<String, String> map = redissonClient.getMap(hashName);
        map.delete();
        log.info("Deleted Redis Hash: {}", hashName);
    }

    private String convertToString(Object obj) throws Exception {
        if (obj instanceof String) {
            return (String) obj;
        }
        return objectMapper.writeValueAsString(obj);
    }

    public String getHashName() {
        return hashName;
    }
}
