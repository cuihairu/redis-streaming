package io.github.cuihairu.redis.streaming.registry.client.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes client-side metrics into instance hash 'metrics' JSON.
 * Keys: clientInflight, clientLatencyMs, clientErrorRate
 *
 * <p>The 'metrics' field is a single JSON document changed by read-modify-write, so
 * mutations serialize on a per-instance lock; without it concurrent updates lost
 * writes and {@code clientInflight} could stick above zero (B-42). The lock is local
 * to this reporter: an instance hash is owned by one process, which is where the
 * real race (request threads updating concurrently) lives.
 */
@Slf4j
public class RedisClientMetricsReporter {

    /**
     * Server-side read-merge-write of the instance 'metrics' JSON document. The provider's
     * heartbeat writes server metrics (cpu/memory) into the same field from another process,
     * so a plain {@code HPUT}-style replace would erase keys the reporter never saw (and the
     * heartbeat merge would erase client keys in return). Overlaying the computed document
     * onto the current server-side value atomically keeps concurrent server keys intact.
     */
    private static final String MERGE_METRICS_SCRIPT = """
            local merged = {}
            local existing = redis.call('HGET', KEYS[1], ARGV[1])
            if existing and existing ~= '' then
                local ok, decoded = pcall(cjson.decode, existing)
                if ok and type(decoded) == 'table' then merged = decoded end
            end
            local ok_in, incoming = pcall(cjson.decode, ARGV[2])
            if ok_in and type(incoming) == 'table' then
                for k, v in pairs(incoming) do merged[k] = v end
            end
            redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(merged))
            return 1
            """;

    private final RedissonClient redisson;
    private final ServiceConsumerConfig cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    // error-rate EMA
    private final double alpha;

    /** Serializes the read-modify-write cycle per instance key (B-42). */
    private final ConcurrentHashMap<String, ReentrantLock> mutationLocks = new ConcurrentHashMap<>();

    public RedisClientMetricsReporter(RedissonClient redisson, ServiceConsumerConfig cfg) {
        this(redisson, cfg, 0.2);
    }

    public RedisClientMetricsReporter(RedissonClient redisson, ServiceConsumerConfig cfg, double emaAlpha) {
        this.redisson = redisson;
        this.cfg = cfg;
        this.alpha = Math.max(0.0, Math.min(1.0, emaAlpha));
    }

    public void incrementInflight(String service, String instanceId) { updateInflight(service, instanceId, 1); }
    public void decrementInflight(String service, String instanceId) { updateInflight(service, instanceId, -1); }

    public void recordLatency(String service, String instanceId, long latencyMs) {
        mutateMetrics(service, instanceId, m -> m.put("clientLatencyMs", latencyMs));
    }

    public void recordOutcome(String service, String instanceId, boolean success) {
        mutateMetrics(service, instanceId, m -> {
            double prev = toDouble(m.get("clientErrorRate"), 0.0);
            double target = success ? 0.0 : 100.0;
            double ema = alpha * target + (1 - alpha) * prev;
            m.put("clientErrorRate", ema);
        });
    }

    private void updateInflight(String service, String instanceId, int delta) {
        mutateMetrics(service, instanceId, m -> {
            long cur = toLong(m.get("clientInflight"), 0);
            long next = Math.max(0, cur + delta);
            m.put("clientInflight", next);
        });
    }

    private void mutateMetrics(String service, String instanceId, java.util.function.Consumer<Map<String, Object>> fn) {
        try {
            String key = cfg.getServiceInstanceKey(service, instanceId);
            ReentrantLock lock = mutationLocks.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            try {
                RMap<String, String> map = redisson.getMap(key, StringCodec.INSTANCE);
                String json = map.get("metrics");
                Map<String, Object> metrics;
                if (json != null && !json.isEmpty()) {
                    metrics = mapper.readValue(json, new TypeReference<Map<String, Object>>(){});
                } else {
                    metrics = new HashMap<>();
                }
                fn.accept(metrics);
                // merge server-side (instead of replacing the whole document) so the
                // provider heartbeat's cpu/memory keys survive the client write window
                redisson.getScript(StringCodec.INSTANCE).eval(
                        org.redisson.api.RScript.Mode.READ_WRITE,
                        MERGE_METRICS_SCRIPT,
                        org.redisson.api.RScript.ReturnType.LONG,
                        java.util.Collections.singletonList(key),
                        "metrics", mapper.writeValueAsString(metrics));
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            log.debug("Failed to update client metrics for service '{}' instance '{}'", service, instanceId, e);
        }
    }

    private static long toLong(Object v, long def) {
        try { return v == null ? def : Long.parseLong(String.valueOf(v)); } catch (Exception e) { return def; }
    }
    private static double toDouble(Object v, double def) {
        try { return v == null ? def : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}

