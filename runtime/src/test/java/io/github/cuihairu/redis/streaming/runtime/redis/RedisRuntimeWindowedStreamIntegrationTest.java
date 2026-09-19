package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the Redis runtime windowed-stream operators
 * (reduce/aggregate/apply/sum/count). Event time in this engine equals consumer delivery
 * time, so tests align sends to the beginning of a tumbling window and then send a
 * trigger record after the window closes to advance the watermark and fire it.
 */
@Tag("integration")
class RedisRuntimeWindowedStreamIntegrationTest {

    private static final long WINDOW_MS = 1500L;

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisRuntimeConfig testConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("rt-win-" + UUID.randomUUID().toString().substring(0, 6))
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

    /**
     * Boots a job that maps message payloads to ints, keys them by "k", applies
     * {@code windowOp} and sinks results. Inputs are sent inside one window; a trigger
     * record is sent after the window ends to advance the watermark and fire it.
     */
    private static <T> List<T> runWindowed(List<String> inputs,
                                           Function<KeyedStream<String, Integer>, DataStream<T>> windowOp)
            throws Exception {
        List<T> out = new CopyOnWriteArrayList<>();
        RedisRuntimeConfig cfg = testConfig();
        String topic = "rt-win-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-grp-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            KeyedStream<String, Integer> keyed = env.fromMqTopic(topic, group)
                    .map(m -> Integer.parseInt((String) m.getPayload()))
                    .keyBy(v -> "k");
            windowOp.apply(keyed).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    alignToWindowStart();
                    for (String in : inputs) {
                        producer.send(topic, "key", in).get(5, TimeUnit.SECONDS);
                    }
                    Thread.sleep(WINDOW_MS + 300);
                    producer.send(topic, "key", "999").get(5, TimeUnit.SECONDS);
                    long deadline = System.currentTimeMillis() + 10_000;
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
        assertFalse(out.isEmpty(), "windowed operator never fired within 10s");
        return out;
    }

    @Test
    void windowedReduceFiresOnWatermark() throws Exception {
        List<Integer> out = runWindowed(List.of("1", "2"),
                keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS)).reduce(Integer::sum));
        assertEquals(List.of(3), out);
    }

    @Test
    void userWatermarkGeneratorAdvancesBeyondOutOfOrdernessHeuristic() throws Exception {
        List<Integer> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-win-" + java.util.UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:wintest:" + java.util.UUID.randomUUID().toString().substring(0, 6))
                .watermarkOutOfOrderness(Duration.ofSeconds(10))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "rt-win-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-grp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            env.fromMqTopic(topic, group)
                    .assignTimestampsAndWatermarks(
                            new io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator<
                                    io.github.cuihairu.redis.streaming.mq.Message>() {
                                @Override
                                public void onEvent(io.github.cuihairu.redis.streaming.mq.Message event, long eventTimestamp,
                                                    io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator.WatermarkOutput output) {
                                    if ("999".equals(String.valueOf(event.getPayload()))) {
                                        // the trigger record knows the stream is quiesced: jump the
                                        // watermark to its own time, which the 10s-lag heuristic
                                        // cannot reach
                                        output.emitWatermark(new io.github.cuihairu.redis.streaming.api.watermark.Watermark(eventTimestamp));
                                    }
                                }

                                @Override
                                public void onPeriodicEmit(io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator.WatermarkOutput output) {
                                }
                            })
                    .map(m -> Integer.parseInt((String) m.getPayload()))
                    .keyBy(v -> "k")
                    .window(io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow.<Integer>ofMillis(WINDOW_MS))
                    .reduce(Integer::sum)
                    .addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    alignToWindowStart();
                    producer.send(topic, "key", "41").get(5, TimeUnit.SECONDS);
                    Thread.sleep(WINDOW_MS + 400); // now inside the next window; W0 is closed but
                    producer.send(topic, "key", "42").get(5, TimeUnit.SECONDS); // heuristic wm is 10s behind
                    Thread.sleep(800);
                    // W0 ("41") must NOT have fired: heuristic wm (delivery - 10s) is behind its close time
                    assertTrue(out.isEmpty(), "10s out-of-orderness heuristic should not have fired W0 yet: " + out);
                    producer.send(topic, "key", "999").get(5, TimeUnit.SECONDS);
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                    assertFalse(out.isEmpty(), "generator-emitted watermark did not fire W0");
                    assertTrue(out.contains(41), "fired windows should include W0 of the first element: " + out);
                    Thread.sleep(1500);
                    // the trigger's own window must still be open when the heuristic lags by 10s
                    assertFalse(out.contains(999), "trigger window should not fire while heuristic wm lags 10s: " + out);
                } finally {
                    producer.close();
                }
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void windowedSumFiresOnWatermark() throws Exception {
        List<Integer> out = runWindowed(List.of("5", "7"),
                keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS)).sum(v -> v));
        assertEquals(1, out.size());
        assertEquals(12, out.get(0).intValue());
    }

    @Test
    void windowedCountFiresOnWatermark() throws Exception {
        List<Long> out = runWindowed(List.of("5", "7", "9"),
                keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS)).count());
        assertEquals(1, out.size());
        assertEquals(3L, out.get(0).longValue());
    }

    @Test
    void windowedAggregateFiresOnWatermark() throws Exception {
        List<Integer> out = runWindowed(List.of("4", "9", "2"),
                keyed -> keyed.window(TumblingWindow.<Integer>ofMillis(WINDOW_MS)).aggregate(new MaxAccumulator()));
        assertEquals(1, out.size());
        assertEquals(9, out.get(0).intValue());
    }

    @Test
    void windowedApplyBuffersElementsAndFires() throws Exception {
        List<String> out = runWindowed(List.of("4", "9"), keyed -> keyed
                .window(TumblingWindow.<Integer>ofMillis(WINDOW_MS))
                .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) -> {
                    List<Integer> seen = new ArrayList<>();
                    for (Integer e : elements) {
                        seen.add(e);
                    }
                    collector.collect(key + ":" + seen);
                }));
        assertEquals(1, out.size());
        assertEquals("k:[4, 9]", out.get(0));
    }

    public static final class MaxAccumulator implements AggregateFunction<Integer, Integer> {
        public static final class Max implements AggregateFunction.Accumulator<Integer> {
            private int max = Integer.MIN_VALUE;

            public int getMax() {
                return max;
            }

            public void setMax(int max) {
                this.max = max;
            }
        }

        @Override
        public Accumulator<Integer> createAccumulator() {
            return new Max();
        }

        @Override
        public Accumulator<Integer> add(Integer value, Accumulator<Integer> accumulator) {
            Max max = (Max) accumulator;
            max.max = Math.max(max.max, value);
            return max;
        }

        @Override
        public Integer getResult(Accumulator<Integer> accumulator) {
            return ((Max) accumulator).max;
        }

        @Override
        public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
            Max x = (Max) a;
            Max y = (Max) b;
            x.max = Math.max(x.max, y.max);
            return x;
        }
    }
}
