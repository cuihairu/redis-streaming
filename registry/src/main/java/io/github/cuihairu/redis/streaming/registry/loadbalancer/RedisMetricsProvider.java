package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches metrics JSON from instance hash and caches briefly in-memory.
 */
public class RedisMetricsProvider implements MetricsProvider {
    private final RedissonClient redisson;
    private final ServiceConsumerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private static class CacheEntry {
        final Map<String, Object> value;
        final long expireAt;
        CacheEntry(Map<String, Object> v, long e) { this.value = v; this.expireAt = e; }
    }
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public RedisMetricsProvider(RedissonClient redisson, ServiceConsumerConfig config) {
        this(redisson, config, 500L);
    }

    public RedisMetricsProvider(RedissonClient redisson, ServiceConsumerConfig config, long ttlMillis) {
        this.redisson = redisson;
        this.config = config;
        this.ttlMillis = ttlMillis;
    }

    @Override
    public Map<String, Object> getMetrics(String serviceName, String instanceId) {
        String key = serviceName + ":" + instanceId;
        long now = System.currentTimeMillis();
        CacheEntry ce = cache.get(key);
        if (ce != null && ce.expireAt > now) {
            return ce.value;
        }

        try {
            String instanceKey = config.getServiceInstanceKey(serviceName, instanceId);
            RMap<String, String> map = redisson.getMap(instanceKey, StringCodec.INSTANCE);
            String json = map.get("metrics");
            if (json == null || json.isEmpty()) {
                cache.put(key, new CacheEntry(Collections.emptyMap(), now + ttlMillis));
                return Collections.emptyMap();
            }
            Map<String, Object> res = mapper.readValue(json, new TypeReference<Map<String, Object>>(){});
            cache.put(key, new CacheEntry(res, now + ttlMillis));
            return res;
        } catch (Exception e) {
            cache.put(key, new CacheEntry(Collections.emptyMap(), now + ttlMillis));
            return Collections.emptyMap();
        }
    }
}

