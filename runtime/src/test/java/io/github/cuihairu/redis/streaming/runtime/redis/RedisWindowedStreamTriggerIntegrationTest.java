package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof (todo B3) that the Redis window operator consults
 * {@link WindowAssigner#getDefaultTrigger()} on its execution path: an
 * {@code onElement} FIRE emits a partial window result WHILE the window is still open —
 * impossible before the wiring, where the only emission point was the close-time fire.
 * The partial fire keeps the bucket's state (FIRE, not FIRE_AND_PURGE), so the later
 * close-time fire still emits the full accumulation.
 *
 * <p>Event time here equals consumer delivery time (same convention as
 * {@code RedisRuntimeWindowedStreamIntegrationTest}): inputs are aligned to the start of
 * a tumbling window, a trigger record is sent after the window ends to advance the
 * watermark and fire it.
 */
@Tag("integration")
class RedisWindowedStreamTriggerIntegrationTest {

    private static final long WINDOW_MS = 1500L;

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisRuntimeConfig testConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("rt-win-trig-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:wintest:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    /** Sleep so that "now" sits just inside a fresh window slot (first 20% of it). */
    private static void alignToWindowStart() throws InterruptedException {
        long phase = System.currentTimeMillis() % WINDOW_MS;
        if (phase > WINDOW_MS * 0.2) {
            Thread.sleep(WINDOW_MS - phase + 20);
        }
    }

    /** Fires (keeping the window contents) on every 2nd element of its bucket. */
    private static final class EveryNthFireTrigger implements WindowAssigner.Trigger<Integer> {
        private final int n;
        private long seen;

        private EveryNthFireTrigger(int n) {
            this.n = n;
        }

        @Override
        public WindowAssigner.TriggerResult onElement(Integer element, long timestamp, WindowAssigner.Window window) {
            if (++seen % n == 0) {
                return WindowAssigner.TriggerResult.FIRE;
            }
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    void onElementFireEmitsPartialWhileWindowStillClosesAtWatermark() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        List<Integer> out = new CopyOnWriteArrayList<>();
        RedisRuntimeConfig cfg = testConfig();
        String topic = "rt-win-trig-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-win-trig-grp-" + UUID.randomUUID().toString().substring(0, 8);
        TumblingWindow<Integer> tumbling = TumblingWindow.ofMillis(WINDOW_MS);
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            KeyedStream<String, Integer> keyed = env.fromMqTopic(topic, group)
                    .map(m -> Integer.parseInt((String) m.getPayload()))
                    .keyBy(v -> "k");
            DataStream<Integer> windowed = keyed.window(new WindowAssigner<Integer>() {
                @Override
                public Iterable<Window> assignWindows(Integer element, long timestamp) {
                    return tumbling.assignWindows(element, timestamp);
                }

                @Override
                public Trigger<Integer> getDefaultTrigger() {
                    factoryCalls.incrementAndGet();
                    return new EveryNthFireTrigger(2);
                }
            }).reduce(Integer::sum);
            windowed.addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    alignToWindowStart();
                    producer.send(topic, "key", "1").get(5, TimeUnit.SECONDS);
                    producer.send(topic, "key", "2").get(5, TimeUnit.SECONDS);

                    // The window is still OPEN (watermark cannot have passed its end yet —
                    // nothing past the window end has been delivered). Any emission here can
                    // only come from the trigger's onElement FIRE.
                    assertTrue(waitUntil(() -> !out.isEmpty(), 8_000),
                            "trigger onElement FIRE never emitted the partial sum while the window was open");
                    assertEquals(List.of(3), out,
                            "the partial fire must be exactly the 1+2 accumulation, before any close fire");

                    Thread.sleep(WINDOW_MS + 300);
                    producer.send(topic, "key", "999").get(5, TimeUnit.SECONDS); // advances watermark past window end
                    assertTrue(waitUntil(() -> out.size() >= 2, 10_000),
                            "the still-open bucket never closed at the watermark");
                    assertEquals(List.of(3, 3), out,
                            "the close fire must still see the kept accumulation (FIRE does not purge)");
                } finally {
                    producer.close();
                }
            }
        } finally {
            client.shutdown();
        }
        assertTrue(factoryCalls.get() >= 2,
                "getDefaultTrigger must be consulted per bucket — got " + factoryCalls.get() + " calls");
    }
}
