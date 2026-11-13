package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MQ-11 end-to-end on real Redis: the commit frontier is a max() reduced across
 * concurrent ack workers — with many workers acking distinct ids out of order, the
 * stored frontier must end at the maximum id every round. The frontier hash is plain
 * text (StringCodec) — the same bytes the fixed consumer's Lua script reads and
 * writes. (On the old code this test fails, partly as a codec-format artifact since
 * the old Java compare ran on a binary-codec client while this test seeds text; the
 * lost-update race itself is pinned codec-independently by the mock-based tests in
 * {@code RedisMessageConsumerCommitFrontierScriptTest}.)
 */
@Tag("integration")
class CommitFrontierAtomicityIntegrationTest {

    private RedissonClient client;
    private RedisMessageConsumer consumer;
    private String topic;
    private String group;
    private RStream<String, Object> stream;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        topic = "it-frontier-" + UUID.randomUUID().toString().substring(0, 8);
        group = "g1";
        stream = client.getStream(StreamKeys.partitionStream(topic, 0),
                org.redisson.client.codec.StringCodec.INSTANCE);
        consumer = new RedisMessageConsumer(client, "it-frontier-consumer",
                mockRegistry(), MqOptions.builder().build());
    }

    private static TopicPartitionRegistry mockRegistry() {
        return org.mockito.Mockito.mock(TopicPartitionRegistry.class);
    }

    @AfterEach
    void tearDown() {
        try {
            client.getKeys().delete(StreamKeys.commitFrontier(topic, 0));
            client.getKeys().delete(StreamKeys.partitionStream(topic, 0));
        } finally {
            client.shutdown();
        }
    }

    private void ack(String messageId) throws Exception {
        Class<?>[] types = {String.class, String.class, int.class,
                RStream.class, String.class, Map.class};
        // ackViaBackend moved to the dispatch processor collaborator; the field is
        // package-private, so reach it reflectively from this package
        Field dispatchField = RedisMessageConsumer.class.getDeclaredField("dispatch");
        dispatchField.setAccessible(true);
        Object dispatch = dispatchField.get(consumer);
        Method m = dispatch.getClass().getDeclaredMethod("ackViaBackend", types);
        m.setAccessible(true);
        m.invoke(dispatch, topic, group, 0, stream, messageId, null);
    }

    @Test
    void concurrentAcksNeverRegressTheFrontier() throws Exception {
        String frontierKey = StreamKeys.commitFrontier(topic, 0);
        // MQ-11: the frontier hash is plain text "ms-seq" (StringCodec) — the same
        // representation the consumer's Lua script reads and writes
        RMap<String, String> frontier = client.getMap(frontierKey,
                org.redisson.client.codec.StringCodec.INSTANCE);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 40; round++) {
                frontier.delete();
                frontier.put(group, "5-0");

                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<Object>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final String id = "5-" + (t + 1);
                    futures.add(pool.submit((Callable<Object>) () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        ack(id);
                        return null;
                    }));
                }
                for (Future<Object> f : futures) {
                    f.get(20, TimeUnit.SECONDS);
                }

                assertEquals("5-" + threads, frontier.get(group),
                        "round " + round + ": the frontier must end at the max acked id, "
                                + "never at a smaller id that wrote last");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Audit fix, end-to-end on real Redis: a still-pending id pins the frontier.
     * 5-1 and 5-2 are delivered into the PEL; acking 5-2 succeeds while 5-1 stays
     * pending — the frontier must not advance to 5-2 (the old code did, letting the
     * retention housekeeper XTRIM the pending 5-1). Once 5-1 is acked the frontier
     * moves to it, and the next ack may then pass both.
     */
    @Test
    void pendingMessagePinsTheFrontier() throws Exception {
        stream.createGroup(org.redisson.api.stream.StreamCreateGroupArgs.name(group).id(new org.redisson.api.stream.StreamMessageId(0, 0)).makeStream());
        stream.add(new org.redisson.api.stream.StreamMessageId(5, 1),
                org.redisson.api.stream.StreamAddArgs.entry("k", "v1"));
        stream.add(new org.redisson.api.stream.StreamMessageId(5, 2),
                org.redisson.api.stream.StreamAddArgs.entry("k", "v2"));
        // deliver both into the PEL without acking
        stream.readGroup(group, "c1",
                org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(2));

        RMap<String, String> frontier = client.getMap(StreamKeys.commitFrontier(topic, 0),
                org.redisson.client.codec.StringCodec.INSTANCE);

        ack("5-2"); // succeeds, but 5-1 is still pending below it
        assertEquals(null, frontier.get(group),
                "the frontier must stay pinned below the pending 5-1");

        ack("5-1"); // the pinned message leaves the PEL
        assertEquals("5-1", frontier.get(group),
                "the frontier may advance to the released pending minimum");

        ack("5-2"); // now nothing pending sits below it
        assertEquals("5-2", frontier.get(group),
                "the frontier passes both ids once the PEL is drained");
    }
}
