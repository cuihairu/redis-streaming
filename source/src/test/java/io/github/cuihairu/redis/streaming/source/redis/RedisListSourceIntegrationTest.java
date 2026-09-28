package io.github.cuihairu.redis.streaming.source.redis;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis integration tests for {@link RedisListSource} (todo §8). Covers the read
 * paths end to end: FIFO drain via LPOP semantics, empty-list handling across all read
 * APIs, continuous consume and periodic poll/pollBatch against a live list, typed JSON
 * deserialization, and graceful degradation when Redis is unreachable.
 *
 * <p>Note: the class delivers via polling (LPOP + back-off); it has no BLPOP/BRPOP —
 * the consume loop's empty-list sleep is the closest behavior and is what these tests
 * exercise.</p>
 */
@Tag("integration")
class RedisListSourceIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static String uniqueListName() {
        return "redis-list-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void readsDrainsAndReportsListElements() {
        RedissonClient client = createClient();
        String list = uniqueListName();
        try {
            RList<String> seeded = client.getList(list);
            seeded.add("a");
            seeded.add("b");
            seeded.add("c");

            RedisListSource<String> source = new RedisListSource<>(client, list, String.class);
            assertEquals(3, source.getSize());
            assertFalse(source.isEmpty());

            // readOne pops the head; readAll drains the rest in FIFO order
            assertEquals("a", source.readOne());
            assertEquals(List.of("b", "c"), source.readAll());

            assertTrue(source.isEmpty());
            assertEquals(0, source.getSize());
            assertNull(source.readOne(), "a drained list must read as null, not throw");
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    @Test
    void emptyAndMissingListsReadAsEmptyAcrossAllApis() {
        RedissonClient client = createClient();
        String list = uniqueListName(); // never created in Redis
        try {
            RedisListSource<String> source = new RedisListSource<>(client, list, String.class);
            assertTrue(source.isEmpty());
            assertEquals(0, source.getSize());
            assertNull(source.readOne());
            assertEquals(List.of(), source.readBatch(3));
            assertEquals(List.of(), source.readAll());
            assertEquals(0L, client.getKeys().delete(list), "no key may have been created by reads");
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    @Test
    void consumeDeliversValuesPushedWhileRunning() throws Exception {
        RedissonClient client = createClient();
        String list = uniqueListName();
        try {
            RedisListSource<String> source = new RedisListSource<>(client, list, String.class);
            List<String> out = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);
            source.consume(v -> {
                out.add(v);
                latch.countDown();
                if (out.size() >= 3) {
                    source.stop();
                }
            });

            RList<String> seeded = client.getList(list);
            seeded.add("a");
            seeded.add("b");
            seeded.add("c");

            assertTrue(latch.await(10, TimeUnit.SECONDS), "consume must deliver all three values");
            assertEquals(List.of("a", "b", "c"), out);
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    @Test
    void pollDeliversElementsPushedBeforeStart() throws Exception {
        RedissonClient client = createClient();
        String list = uniqueListName();
        try {
            RList<String> seeded = client.getList(list);
            seeded.add("p1");
            seeded.add("p2");

            RedisListSource<String> source = new RedisListSource<>(client, list, String.class);
            List<String> out = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);
            source.poll(v -> {
                out.add(v);
                latch.countDown();
                if (out.size() >= 2) {
                    source.stop();
                }
            }, java.time.Duration.ofMillis(20));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "poll must deliver both pushed values");
            assertEquals(List.of("p1", "p2"), out);
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    @Test
    void pollBatchDeliversBatchesAcrossTicks() throws Exception {
        RedissonClient client = createClient();
        String list = uniqueListName();
        try {
            RList<String> seeded = client.getList(list);
            seeded.add("x");
            seeded.add("y");
            seeded.add("z");

            RedisListSource<String> source = new RedisListSource<>(client, list, String.class);
            List<String> out = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            // batch of 2: tick 1 delivers [x, y], tick 2 delivers [z] and stops
            source.pollBatch(batch -> {
                out.addAll(batch);
                if (out.size() >= 3) {
                    source.stop();
                    latch.countDown();
                }
            }, 2, java.time.Duration.ofMillis(20));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "pollBatch must deliver all three values across ticks");
            assertEquals(List.of("x", "y", "z"), out);
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    @Test
    void typedValuesRoundTripThroughJson() {
        RedissonClient client = createClient();
        String list = uniqueListName();
        try {
            RList<String> seeded = client.getList(list);
            seeded.add("{\"name\":\"widget\"}");
            seeded.add("{\"name\":\"gadget\"}");

            RedisListSource<Item> source = new RedisListSource<>(client, list, Item.class);
            List<Item> all = source.readAll();
            assertEquals(2, all.size());
            assertEquals("widget", all.get(0).name());
            assertEquals("gadget", all.get(1).name());
        } finally {
            client.getKeys().delete(list);
            client.shutdown();
        }
    }

    /**
     * Connection-failure path: Redisson 3.29 connects eagerly in create(), so an
     * unreachable server cannot yield a client object at all — instead a client whose
     * connection is gone (shutdown here; server restart or network cut behaves the same)
     * makes every command fail, and the read APIs must degrade to null/empty rather
     * than propagate.
     */
    @Test
    void connectionFailuresDegradeToNullAndEmptyInsteadOfThrowing() {
        RedissonClient dead = createClient();
        dead.shutdown();

        RedisListSource<String> source = new RedisListSource<>(dead, uniqueListName(), String.class);
        assertNull(source.readOne(), "readOne must swallow the connection failure");
        assertEquals(List.of(), source.readBatch(3), "readBatch must degrade to an empty batch");
        assertEquals(List.of(), source.readAll(), "readAll must degrade to an empty list");
    }

    record Item(String name) {}
}
