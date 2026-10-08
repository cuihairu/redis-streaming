package io.github.cuihairu.redis.streaming.runtime.operators;

import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stream-stream join operator running inside a real pipeline on both execution engines:
 * in-memory (eager, deterministic) and the Redis runtime over an MQ topic carrying
 * tagged {@link Envelope}s from both sides.
 */
class StreamJoinOperatorIntegrationTest {

    private static Envelope<String, String, String> parseEnvelope(String raw) {
        // "L|key|ts|payload" or "R|key|ts|payload"
        String[] parts = raw.split("\\|", 4);
        long ts = Long.parseLong(parts[2]);
        return "L".equals(parts[0])
                ? Envelope.forLeft(parts[1], ts, parts[3])
                : Envelope.forRight(parts[1], ts, parts[3]);
    }

    private static JoinConfig<String, String, String> joinConfig() {
        return JoinConfig.<String, String, String>innerJoin(
                l -> l.split(":")[0], r -> r.split(":")[0], JoinWindow.ofSize(Duration.ofSeconds(30)));
    }

    private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    @Test
    void inMemoryEngineJoinsMergedEnvelopeStream() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        List<String> out = new ArrayList<>();

        // both sides merged into one stream, in mixed arrival order
        env.fromElements(
                        parseEnvelope("L|k1|" + System.currentTimeMillis() + "|k1:left"),
                        parseEnvelope("R|k2|" + System.currentTimeMillis() + "|k2:right"),
                        parseEnvelope("R|k1|" + System.currentTimeMillis() + "|k1:right"),
                        parseEnvelope("L|k2|" + System.currentTimeMillis() + "|k2:left"))
                .keyBy(Envelope::getJoinKey)
                .process(StreamJoinOperator.asKeyedProcessFunction(joinConfig(), (l, r) -> l + "+" + r))
                .addSink(out::add);

        assertEquals(List.of("k1:left+k1:right", "k2:left+k2:right"), out);
    }

    @Test
    @Tag("integration")
    void redisEngineJoinsEnvelopesOverMqTopic() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return; // gated on a reachable Redis, like the other runtime integration legs
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(cfg);
        String topic = "it-join-op-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> out = new CopyOnWriteArrayList<>();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    RedisRuntimeConfig.builder().jobName(topic).build());
            env.fromMqTopic(topic, "join-group")
                    .map(m -> parseEnvelope(String.valueOf(m.getPayload())))
                    .keyBy(Envelope::getJoinKey)
                    .process(StreamJoinOperator.asKeyedProcessFunction(joinConfig(), (l, r) -> l + "+" + r))
                    .addSink(out::add);
            job = env.executeAsync();

            long now = System.currentTimeMillis();
            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            producer.send(topic, "k1", "L|k1|" + now + "|k1:left").get(10, TimeUnit.SECONDS);
            producer.send(topic, "k2", "L|k2|" + now + "|k2:left").get(10, TimeUnit.SECONDS);
            producer.send(topic, "k1", "R|k1|" + now + "|k1:right").get(10, TimeUnit.SECONDS);
            producer.send(topic, "k2", "R|k2|" + now + "|k2:right").get(10, TimeUnit.SECONDS);
            producer.close();

            assertTrue(await(() ->
                            out.containsAll(List.of("k1:left+k1:right", "k2:left+k2:right")),
                    30_000),
                    "expected both join pairs, got " + out);
        } finally {
            if (job != null) {
                job.close();
            }
            try {
                redis.getKeys().deleteByPattern("*" + topic + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    private static boolean reachable(String url) {
        try (java.net.Socket s = new java.net.Socket()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("://([^/:]+):(\\d+)").matcher(url);
            if (!m.find()) {
                return false;
            }
            s.connect(new java.net.InetSocketAddress(m.group(1), Integer.parseInt(m.group(2))), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
