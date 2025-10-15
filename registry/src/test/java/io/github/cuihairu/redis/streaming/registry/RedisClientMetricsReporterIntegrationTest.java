package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RedisClientMetricsReporterIntegrationTest {

    @Test
    void testReporterWritesMetricsJson() throws Exception {
        RedissonClient client = createClient();
        try {
            ServiceConsumerConfig cfg = new ServiceConsumerConfig();
            RedisClientMetricsReporter reporter = new RedisClientMetricsReporter(client, cfg, 1.0 /*fast EMA*/);

            String svc = "svc-rpt";
            String iid = "i1";
            String key = cfg.getServiceInstanceKey(svc, iid);

            // Initially empty metrics
            reporter.incrementInflight(svc, iid);
            reporter.recordLatency(svc, iid, 42);
            reporter.recordOutcome(svc, iid, false);
            reporter.decrementInflight(svc, iid);

            RMap<String, String> map = client.getMap(key, StringCodec.INSTANCE);
            String json = map.get("metrics");
            assertNotNull(json);
            assertTrue(json.contains("clientInflight"));
            assertTrue(json.contains("clientLatencyMs"));
            assertTrue(json.contains("clientErrorRate"));
        } finally {
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        return Redisson.create(config);
    }
}

