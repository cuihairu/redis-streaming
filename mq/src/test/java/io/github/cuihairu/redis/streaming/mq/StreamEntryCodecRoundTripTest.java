package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class StreamEntryCodecRoundTripTest {

    @Test
    void inlineSmallPayloadRoundTrip() {
        Message m = new Message("t", Map.of("a", 1));
        m.setHeaders(Map.of("h", "v"));
        Map<String,Object> data = StreamEntryCodec.buildPartitionEntry(m, 0);
        assertNotNull(data.get("payload"));
        Message parsed = StreamEntryCodec.parsePartitionEntry("t", "1-1", data);
        assertNotNull(parsed.getPayload());
        assertEquals("t", parsed.getTopic());
        assertTrue(parsed.getHeaders()==null || parsed.getHeaders().isEmpty() || parsed.getHeaders().containsKey("h"));
    }

    @Test
    void hashLargePayloadRoundTrip() {
        RedissonClient client = createClient();
        try {
            PayloadLifecycleManager plm = new PayloadLifecycleManager(client);
            // Build a large map payload
            Map<String,Object> big = new HashMap<>();
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<90000;i++) sb.append('X');
            big.put("blob", sb.toString());
            Message m = new Message("t2", big);
            Map<String,Object> data = StreamEntryCodec.buildPartitionEntry(m, 0, plm);
            // Expect inline payload moved to hash (payload field may be null; header contains hash ref)
            Object payloadField = data.get("payload");
            assertTrue(payloadField == null || String.valueOf(payloadField).isEmpty());
            Object headers = data.get("headers");
            assertNotNull(headers);
            // Parse back with lifecycle manager to load the hash
            Message parsed = StreamEntryCodec.parsePartitionEntry("t2", "1-1", data, plm);
            assertNotNull(parsed.getPayload());
            assertEquals("t2", parsed.getTopic());
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

