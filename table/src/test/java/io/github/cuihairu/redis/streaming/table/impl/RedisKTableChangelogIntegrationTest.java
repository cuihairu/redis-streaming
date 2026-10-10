package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for {@link RedisKTable} changelog mode: put/del events
 * replayed from the beginning of the topic (consumer group created at 0-0) and
 * followed continuously; distinct explicit groups each receive the full history.
 * Skips when Redis is unreachable.
 */
@Tag("integration")
class RedisKTableChangelogIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
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

    private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    private static String kv(KTable.KeyValue<String, Integer> e) {
        return e.getKey() + "=" + e.getValue();
    }

    @Test
    void changelogReplaysFullHistoryAndFollowsNewEvents() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String name = "it-changelog-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> table =
                    new RedisKTable<>(redis, name, String.class, Integer.class).withChangelog();
            assertTrue(table.isChangelogEnabled());

            table.put("a", 1);
            table.put("b", 2);
            table.put("a", null); // delete event

            List<KTable.KeyValue<String, Integer>> out = new CopyOnWriteArrayList<>();
            try (RedisJobClient job = (RedisJobClient) table.toStream().addSink(out::add)) {
                // history replay must reconstruct the full change sequence in order
                assertTrue(await(() -> out.size() >= 3, 20_000),
                        "history replay should deliver 3 events, got " + out);
                assertEquals(List.of("a=1", "b=2", "a=null"), out.subList(0, 3).stream().map(
                        RedisKTableChangelogIntegrationTest::kv).toList());

                // continuous follow: a new put arrives on the running stream
                table.put("c", 3);
                assertTrue(await(() -> out.stream().anyMatch(e -> "c".equals(e.getKey())), 20_000),
                        "new put should arrive on the live stream, got " + out);
                assertEquals("c=3", kv(out.get(out.size() - 1)));
            }
        } finally {
            redis.getKeys().deleteByPattern("*table-changelog:" + name + "*");
            redis.getKeys().deleteByPattern("*" + name + "*");
            redis.shutdown();
        }
    }

    @Test
    void explicitGroupsEachReceiveFullHistory() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        String name = "it-changelog-g-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> table =
                    new RedisKTable<>(redis, name, String.class, Integer.class).withChangelog();
            table.put("x", 10);
            table.put("y", 20);

            List<KTable.KeyValue<String, Integer>> outX = new CopyOnWriteArrayList<>();
            List<KTable.KeyValue<String, Integer>> outY = new CopyOnWriteArrayList<>();
            // each explicit group gets the full history (broadcast across groups)
            try (RedisJobClient jobX = (RedisJobClient) table.toStream("grp-x").addSink(outX::add);
                 RedisJobClient jobY = (RedisJobClient) table.toStream("grp-y").addSink(outY::add)) {
                assertTrue(await(() -> outX.size() >= 2, 20_000), "group X history, got " + outX);
                assertTrue(await(() -> outY.size() >= 2, 20_000), "group Y history, got " + outY);
                assertEquals(List.of("x=10", "y=20"), outX.stream().map(
                        RedisKTableChangelogIntegrationTest::kv).toList());
                assertEquals(List.of("x=10", "y=20"), outY.stream().map(
                        RedisKTableChangelogIntegrationTest::kv).toList());
            }
        } finally {
            redis.getKeys().deleteByPattern("*table-changelog:" + name + "*");
            redis.getKeys().deleteByPattern("*" + name + "*");
            redis.shutdown();
        }
    }
}
