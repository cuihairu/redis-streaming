package io.github.cuihairu.redis.streaming.runtime.operators;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.Pattern;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import io.github.cuihairu.redis.streaming.cep.operator.PatternSequenceProcessFunction;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CEP sequence-matching operator running inside a real pipeline on both execution
 * engines: in-memory (eager, deterministic) and the Redis runtime over an MQ topic.
 * Events carry a user key, a type and an event timestamp: "key|type|ts".
 */
class PatternSequenceProcessFunctionIntegrationTest {

    /** Pipeline event type: matched on {@link #type}, keyed on {@link #user}. */
    record Event(String user, String type, long ts) {
    }

    private static PatternSequence<Event> loginThenPurchase() {
        Pattern<Event> login = Pattern.of(e -> e.type().equals("login"));
        Pattern<Event> purchase = Pattern.of(e -> e.type().equals("purchase"));
        return PatternSequence.<Event>begin("login", login).next("purchase", purchase);
    }

    private static Event parse(String raw) {
        String[] parts = raw.split("\\|");
        return new Event(parts[0], parts[1], Long.parseLong(parts[2]));
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

    private static List<String> render(List<EventSequence<Event>> sequences) {
        return sequences.stream()
                .map(seq -> seq.getEventsCopy().get(0).user() + ":"
                        + seq.getEventsCopy().get(0).type() + "->"
                        + seq.getEventsCopy().get(seq.size() - 1).type())
                .toList();
    }

    @Test
    void inMemoryEngineMatchesSequencesPerKey() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        List<EventSequence<Event>> out = new ArrayList<>();

        env.fromElements(
                        parse("u1|login|1000"),
                        parse("u2|login|1100"),
                        parse("u1|purchase|1200"), // strict next(): consecutive matching events for u1
                        parse("u2|purchase|1300"))
                .keyBy(Event::user)
                .process(new PatternSequenceProcessFunction<>(loginThenPurchase(), Event::ts))
                .addSink(out::add);

        List<String> rendered = render(out);
        assertEquals(List.of("u1:login->purchase", "u2:login->purchase"), rendered);
    }

    @Test
    @Tag("integration")
    void redisEngineMatchesSequencesOverMqTopic() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return; // gated on a reachable Redis, like the other runtime integration legs
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(cfg);
        String topic = "it-cep-op-" + UUID.randomUUID().toString().substring(0, 8);
        List<EventSequence<Event>> out = new CopyOnWriteArrayList<>();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    RedisRuntimeConfig.builder().jobName(topic).build());
            KeyedProcessFunction<String, Event, EventSequence<Event>> cep =
                    new PatternSequenceProcessFunction<>(loginThenPurchase(), Event::ts);
            env.fromMqTopic(topic, "cep-group")
                    .map(m -> parse(String.valueOf(m.getPayload())))
                    .keyBy(Event::user)
                    .process(cep)
                    .addSink(out::add);
            job = env.executeAsync();

            long now = System.currentTimeMillis();
            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            producer.send(topic, "u1", "u1|login|" + now).get(10, TimeUnit.SECONDS);
            producer.send(topic, "u2", "u2|login|" + now).get(10, TimeUnit.SECONDS);
            producer.send(topic, "u1", "u1|purchase|" + (now + 500)).get(10, TimeUnit.SECONDS);
            producer.send(topic, "u2", "u2|purchase|" + (now + 600)).get(10, TimeUnit.SECONDS);
            producer.close();

            assertTrue(await(() -> render(out).containsAll(List.of("u1:login->purchase", "u2:login->purchase")),
                    30_000),
                    "expected both user sequences, got " + render(out));
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
