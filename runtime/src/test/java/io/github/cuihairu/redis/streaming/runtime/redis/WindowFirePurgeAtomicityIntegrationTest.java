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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-H3: a window fire whose sink emit fails must not destroy the window. Before the
 * fix, the accumulated state was purged in a {@code finally} around the emit (apply even
 * purged before emitting), and the polled member was not re-queued — the window's data
 * was silently lost; the redelivered trigger record then re-fired a purged (or absent)
 * window. Now the state survives a failed emit and the member returns to the due set, so
 * the redelivery fires the complete window.
 */
@Tag("integration")
class WindowFirePurgeAtomicityIntegrationTest {

    private static final long WINDOW_MS = 1500L;

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisRuntimeConfig testConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("rt-purge-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:purgetest:" + UUID.randomUUID().toString().substring(0, 6))
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

    /**
     * Runs a windowed job whose sink throws on the first window result (the close fire)
     * and succeeds afterwards. Returns everything the sink eventually accepted.
     */
    private static List<Long> runWithFlakySink(String topicSuffix,
                                               java.util.function.Function<
                                                       io.github.cuihairu.redis.streaming.api.stream.KeyedStream<String, Integer>,
                                                       io.github.cuihairu.redis.streaming.api.stream.DataStream<Long>> windowOp)
            throws Exception {
        List<Long> out = new CopyOnWriteArrayList<>();
        AtomicBoolean armed = new AtomicBoolean(true);
        RedisRuntimeConfig cfg = testConfig();
        String topic = "rt-purge-" + topicSuffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        String group = "rt-purge-grp-" + UUID.randomUUID().toString().substring(0, 6);
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            io.github.cuihairu.redis.streaming.api.stream.KeyedStream<String, Integer> keyed =
                    env.fromMqTopic(topic, group)
                            .map(m -> Integer.parseInt((String) m.getPayload()))
                            .keyBy(v -> "k");
            windowOp.apply(keyed).addSink(value -> {
                if (armed.compareAndSet(true, false)) {
                    throw new RuntimeException("sink boom (armed once)");
                }
                out.add(value);
            });
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    alignToWindowStart();
                    for (String in : List.of("1", "2", "3")) {
                        producer.send(topic, "key", in).get(5, TimeUnit.SECONDS);
                    }
                    Thread.sleep(WINDOW_MS + 300);
                    // advances the watermark past the window close: the first fire path hits
                    // the armed sink, the message goes back to RETRY and is redelivered
                    producer.send(topic, "key", "999").get(5, TimeUnit.SECONDS);
                    long deadline = System.currentTimeMillis() + 30_000;
                    while (out.isEmpty() && System.currentTimeMillis() < deadline) {
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
    void failedCountFireKeepsStateAndRedeliversCompleteWindow() throws Exception {
        List<Long> out = runWithFlakySink("cnt", keyed ->
                keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS)).count());
        // the complete window (3 counted elements) must reach the sink despite the failed
        // first fire; before the RT-H3 fix it was lost entirely (the state was purged and
        // the member de-queued, so nothing re-fired it). Extra results beyond it (e.g. the
        // trigger record's own window, since event time equals delivery time here) are fine.
        assertTrue(out.contains(3L),
                "expected the complete window to be re-fired after the failed emit, got " + out);
    }

    @Test
    void failedApplyFireKeepsBufferedResultsAndRedelivers() throws Exception {
        List<Long> out = runWithFlakySink("app", keyed ->
                keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS))
                        .apply((key, window, values, collector) -> {
                            int n = 0;
                            for (Integer ignored : values) {
                                n++;
                            }
                            collector.collect((long) n);
                        }));
        // apply used to purge in a finally BEFORE the buffered results were emitted — the
        // worst RT-H3 variant; the complete buffer (3 elements) must still be delivered
        assertTrue(out.contains(3L),
                "expected the complete buffered results to be re-emitted after the failed emit, got " + out);
    }
}
