package io.github.cuihairu.redis.streaming.source.redis;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip integration test: entries appended with XADD (matching the field convention of
 * {@code sink.redis.RedisStreamSink}) are consumed by {@link RedisStreamSource} via XREADGROUP.
 */
@Tag("integration")
class RedisStreamSourceIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void readsBackEntriesAppendedViaXadd() throws Exception {
        RedissonClient client = createClient();
        String stream = "src-it-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RStream<String, String> rs = client.getStream(stream, StringCodec.INSTANCE);
            rs.add(StreamAddArgs.entries(Map.of("value", "first")));
            rs.add(StreamAddArgs.entry("value", "second"));

            RedisStreamSource<String> source = new RedisStreamSource<>(client, stream, "g1", "c1", String.class);
            List<String> out = new ArrayList<>();
            source.run(new StreamSource.SourceContext<>() {
                @Override
                public void collect(String element) {
                    out.add(element);
                }

                @Override
                public void collectWithTimestamp(String element, long timestamp) {
                    out.add(element);
                }

                @Override
                public Object getCheckpointLock() {
                    return new Object();
                }

                @Override
                public boolean isStopped() {
                    return false;
                }
            });

            assertEquals(List.of("first", "second"), out);

            // Re-running drains nothing new: entries were acknowledged by the first pass.
            List<String> again = new ArrayList<>();
            source.run(new ListContext(again));
            assertTrue(again.isEmpty(), "acknowledged entries must not be redelivered");
        } finally {
            client.getKeys().delete(stream);
            client.shutdown();
        }
    }

    private static final class ListContext implements StreamSource.SourceContext<String> {
        private final List<String> out;

        ListContext(List<String> out) {
            this.out = out;
        }

        @Override
        public void collect(String element) {
            out.add(element);
        }

        @Override
        public void collectWithTimestamp(String element, long timestamp) {
            out.add(element);
        }

        @Override
        public Object getCheckpointLock() {
            return new Object();
        }

        @Override
        public boolean isStopped() {
            return false;
        }
    }
}
