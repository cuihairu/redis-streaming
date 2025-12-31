package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RedisRuntimeIntegrationTest {

    @Test
    void consumesFromMqAndInvokesSink() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        DataStream<String> stream = env.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload());

        stream.addSink(v -> {
            out.add(v);
            latch.countDown();
        });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(3, out.size());
            assertTrue(out.containsAll(List.of("a", "b", "c")));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void keyedProcessUsesRedisKeyedValueState() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        DataStream<String> base = env.fromMqTopic(topic, group).map(m -> (String) m.getPayload());
        KeyedStream<String, String> keyed = base.keyBy(v -> v);

        StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
        ValueState<Integer> cnt = keyed.getState(desc);

        keyed.<String>process((key, value, ctx, out) -> {
            Integer c = cnt.value();
            if (c == null) c = 0;
            c = c + 1;
            cnt.update(c);
            out.collect(key + ":" + c);
        }).addSink(v -> {
            seen.add(v);
            latch.countDown();
        });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "b", "b").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(3, seen.size());

            // Order is not guaranteed; verify counts per key.
            List<String> a = new ArrayList<>();
            List<String> b = new ArrayList<>();
            for (String s : seen) {
                if (s.startsWith("a:")) a.add(s);
                if (s.startsWith("b:")) b.add(s);
            }
            assertEquals(2, a.size());
            assertEquals(1, b.size());
            assertTrue(a.contains("a:1"));
            assertTrue(a.contains("a:2"));
            assertTrue(b.contains("b:1"));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    private static RedissonClient createClient() {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(url);
        return Redisson.create(cfg);
    }
}

