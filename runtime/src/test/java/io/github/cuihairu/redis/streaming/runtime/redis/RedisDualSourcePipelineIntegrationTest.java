package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.JoinType;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisJoinEnvelopeCodec;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 end-to-end legs of the engine-level dual-source pipeline ({@code fromMqTopics},
 * design plan A) over a real Redis: one pipeline consuming the two producer topics directly,
 * keying the envelopes by join key, and running the join operator — no join topic relay.
 *
 * <p>The second leg pins the reason the aligned watermark is a plain minimum: with a LEFT
 * join in waiting mode ({@code emitUnmatchedImmediately=false}), a left row's null emission
 * must stay held while <em>any</em> source still owes a watermark, because the peer side may
 * still deliver an in-window partner. Only the slower source's advance may settle it.
 */
class RedisDualSourcePipelineIntegrationTest {

    /** (l, r) -> "l|r" — {@code null} renders as {@code null} for the unmatched row. */
    private static final JoinFunction<String, String, String> FN = (l, r) -> l + "|" + r;

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

    /**
     * Envelope stream over the two sources: parse the wire envelope, assign its own timestamp
     * as event time, let the bounded generator advance each source's watermark
     * ({@code maxTs - outOfOrderness - 1} — {@code ts - 1} with zero out-of-orderness).
     * The per-source factory form is required here: one shared instance would let the right
     * source's max raise the left source's watermark and close left windows early.
     */
    private static DataStream<Envelope<String, String, String>> envelopes(
            RedisStreamExecutionEnvironment env, String leftTopic, String leftGroup,
            String rightTopic, String rightGroup) {
        TimestampAssigner<Envelope<String, String, String>> byEnvelope =
                (event, recordTimestamp) -> event.getTimestamp();
        return env.fromMqTopics(leftTopic, leftGroup, rightTopic, rightGroup)
                .map(m -> RedisJoinEnvelopeCodec.<String, String, String>parse(String.valueOf(m.getPayload())))
                .assignTimestampsAndWatermarks(byEnvelope,
                        () -> new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ZERO));
    }

    @Test
    @Tag("integration")
    void innerJoinPairsRecordsArrivingOnTwoDifferentTopics() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return; // gated on a reachable Redis, like the other runtime integration legs
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(cfg);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String leftTopic = "it-ds-join-l-" + tag;
        String rightTopic = "it-ds-join-r-" + tag;
        List<String> out = new CopyOnWriteArrayList<>();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    RedisRuntimeConfig.builder().jobName("it-ds-inner-" + tag).build());

            JoinConfig<String, String, String> config = JoinConfig.<String, String, String>innerJoin(
                    k -> k, k -> k, JoinWindow.ofSize(Duration.ofSeconds(30)));
            envelopes(env, leftTopic, leftTopic + "-g", rightTopic, rightTopic + "-g")
                    .keyBy(Envelope::getJoinKey)
                    .process(StreamJoinOperator.asKeyedProcessFunction(config, FN))
                    .addSink(out::add);
            job = env.executeAsync();

            long ts = System.currentTimeMillis();
            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            send(producer, leftTopic, Envelope.forLeft("u1", ts, "L1"));
            send(producer, rightTopic, Envelope.forRight("u1", ts, "R1"));
            send(producer, leftTopic, Envelope.forLeft("u2", ts, "L2"));
            send(producer, rightTopic, Envelope.forRight("u2", ts, "R2"));
            producer.close();

            List<String> expected = List.of("L1|R1", "L2|R2");
            assertTrue(await(() -> out.containsAll(expected), 30_000),
                    "both pairs must join across the two topics, got " + out);
            Thread.sleep(500); // grace: a stray or duplicate emission must still be caught below
            assertEquals(expected, out.stream().sorted().toList());
        } finally {
            if (job != null) {
                job.close();
            }
            try {
                redis.getKeys().deleteByPattern("*" + tag + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    @Test
    @Tag("integration")
    void aWaitingLeftJoinSettlesOnlyWhenTheSlowerSourceAdvances() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return; // gated on a reachable Redis, like the other runtime integration legs
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(cfg);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String leftTopic = "it-ds-wait-l-" + tag;
        String rightTopic = "it-ds-wait-r-" + tag;
        List<String> out = new CopyOnWriteArrayList<>();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    RedisRuntimeConfig.builder().jobName("it-ds-wait-" + tag).build());

            // left k1@1000 can pair with any right peer in [ts-2s, ts+2s]; in waiting mode the
            // unmatched row is held until the aligned watermark passes 1000+2000=3000
            JoinConfig<String, String, String> config = JoinConfig.<String, String, String>builder()
                    .joinType(JoinType.LEFT)
                    .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(2)))
                    .leftKeySelector(k -> k)
                    .rightKeySelector(k -> k)
                    .emitUnmatchedImmediately(false)
                    .build();
            envelopes(env, leftTopic, leftTopic + "-g", rightTopic, rightTopic + "-g")
                    .keyBy(Envelope::getJoinKey)
                    .process(StreamJoinOperator.asKeyedProcessFunction(config, FN))
                    .addSink(out::add);
            job = env.executeAsync();

            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            send(producer, leftTopic, Envelope.forLeft("k1", 1000L, "L1"));
            send(producer, rightTopic, Envelope.forRight("k2", 5000L, "R2"));
            producer.close();

            // watermarks: left source 999, right source 4999, aligned min = 999 < 3000 — the
            // unmatched k1 row must stay held even though the right side has raced far ahead
            Thread.sleep(1000);
            assertTrue(out.isEmpty(),
                    "the row must not settle before every source has passed its close time, got " + out);

            // a left record at 6000 raises the left source to 5999, so the aligned watermark
            // becomes min(5999, 4999) = 4999 >= 3000 and k1's close settles exactly once
            producer = mq.createProducer();
            send(producer, leftTopic, Envelope.forLeft("k3", 6000L, "L3"));
            producer.close();

            assertTrue(await(() -> out.size() == 1, 30_000),
                    "k1's unmatched row must settle once the slower source advanced, got " + out);
            Thread.sleep(500); // grace: k3's own close (6000+2000=8000 > 4999) must NOT settle yet
            assertEquals(List.of("L1|null"), out);
        } finally {
            if (job != null) {
                job.close();
            }
            try {
                redis.getKeys().deleteByPattern("*" + tag + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    private static void send(MessageProducer producer, String topic, Envelope<String, String, String> e)
            throws Exception {
        producer.send(topic, String.valueOf(e.getJoinKey()), RedisJoinEnvelopeCodec.toJson(e))
                .get(10, TimeUnit.SECONDS);
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
