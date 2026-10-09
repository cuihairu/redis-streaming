package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
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
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RT-M3: the watermark is no longer purely record-driven.
 *
 * <p>Idle flush: with {@code watermarkIdleTimeout} configured, a pipeline whose source
 * went quiet fires its remaining due windows (the flush raises the watermark to
 * {@code MAX_VALUE} on a sweep, which fires the event-time timers, which include the
 * window due-set sweep) — before the fix, the window simply never fired until a new
 * record happened to arrive.</p>
 *
 * <p>Due-set sweep: the per-record drain is capped by {@code windowMaxFiresPerRecord};
 * the remainder stays due, and the idle watermark flush drains it — the due-set sweep
 * timer armed at the earliest close score runs only as part of that flush, never at an
 * ordinary record crossing (which would re-consult close triggers the record drain just
 * saw). Several stream keys on one MQ partition close their window at the same instant,
 * which is exactly when the cap binds.</p>
 */
@Tag("integration")
class IdleWatermarkWindowFlushIntegrationTest {

    private static final long WINDOW_MS = 800L;

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    /** Sleep so that "now" sits just inside a fresh window slot (first 20% of it). */
    private static void alignToWindowStart() throws InterruptedException {
        long phase = System.currentTimeMillis() % WINDOW_MS;
        if (phase > WINDOW_MS * 0.2) {
            Thread.sleep(WINDOW_MS - phase + 20);
        }
    }

    private static List<Long> run(String topicSuffix,
                                  RedisRuntimeConfig cfg,
                                  BiConsumer<MessageProducer, String> sendScript,
                                  int expectedResults) throws Exception {
        List<Long> out = new CopyOnWriteArrayList<>();
        String uid = UUID.randomUUID().toString().substring(0, 6);
        String topic = "rt-idle-" + topicSuffix + "-" + uid;
        String group = "rt-idle-grp-" + uid;
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            env.fromMqTopic(topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> v)
                    .window(TumblingWindow.<String>ofMillis(WINDOW_MS))
                    .count()
                    .addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    alignToWindowStart();
                    sendScript.accept(producer, topic);
                    long deadline = System.currentTimeMillis() + 30_000;
                    while (out.size() < expectedResults && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                } finally {
                    producer.close();
                }
            }
        } finally {
            client.shutdown();
        }
        return out;
    }

    @Test
    void idleFlushFiresRemainingWindowsWithoutFurtherRecords() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-idle-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:idletest:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .watermarkIdleTimeout(Duration.ofMillis(1200))
                .build();

        List<Long> out = run("flush", cfg, (producer, topic) -> {
            try {
                // two elements into the current slot (same MQ key → same partition), then
                // the source goes quiet — no further record ever advances the watermark
                for (String in : List.of("k", "k")) {
                    producer.send(topic, "fixed", in).get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 1);

        assertTrue(out.contains(2L),
                "the quiet pipeline should flush its due window after the idle timeout, got " + out);
    }

    @Test
    void dueSweepDrainsWindowsBeyondThePerRecordCap() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-idle-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:idletest:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .windowMaxFiresPerRecord(2)
                .watermarkIdleTimeout(Duration.ofMillis(1200))
                .build();

        List<Long> out = run("sweep", cfg, (producer, topic) -> {
            try {
                // four stream keys accumulate in the same slot, all on ONE MQ partition
                // (same message key) so their windows share the partition's due set
                for (String in : List.of("a", "b", "b", "c", "c", "c", "d", "d", "d", "d")) {
                    producer.send(topic, "fixed", in).get(5, TimeUnit.SECONDS);
                }
                Thread.sleep(WINDOW_MS + 300);
                // one record advances the watermark past every slot-0 close: the capped
                // drain fires two and the rest stay due; the source then goes quiet and the
                // idle watermark flush (via the due-set sweep timer) delivers the remainder
                producer.send(topic, "fixed", "z").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 5);

        // every accumulated element (1+2+3+4) must eventually reach the sink: a capped
        // drain that strands windows would lose their counts forever. The floor is what
        // the gate guarantees — sum >= 10 proves conservation (stranded windows would
        // cap the sum at 3, the pre-flush behavior). Splits (sends straddling a boundary
        // under load), at-least-once redelivery double-counts, and the "z" record's own
        // window (fired by the MAX_VALUE flush when it lands before job close) can push
        // the observed sum above 10, so it is a floor, not an equality.
        long sum = out.stream().mapToLong(Long::longValue).sum();
        assertTrue(sum >= 10 && out.size() >= 4,
                "all four keyed windows should fire despite the per-record cap of 2, got " + out);
    }
}
