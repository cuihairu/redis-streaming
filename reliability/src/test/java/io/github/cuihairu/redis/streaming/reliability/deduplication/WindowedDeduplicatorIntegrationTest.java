package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-09 regression: the pre-fix implementation kept a plain set whose whole-key TTL was
 * refreshed on every write, so under continued traffic no element ever expired (and
 * under idle traffic the whole set vanished at once). Elements must expire
 * individually after the window has passed since <em>they</em> were last seen.
 *
 * <p>Uses only the public pre-fix API so the file doubles as the old-code reproduction.
 */
@Tag("integration")
class WindowedDeduplicatorIntegrationTest {

    private RedissonClient client;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    private String uniqueName() {
        return "b09-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void elementExpiresWhileTrafficContinues() throws Exception {
        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(client, uniqueName(), Duration.ofMillis(300), s -> s);

        long start = System.currentTimeMillis();
        dedup.markAsSeen("A");

        // continued traffic: the pre-fix code refreshed the whole-set TTL on every one of
        // these writes, keeping "A" alive forever
        int pumped = 0;
        while (System.currentTimeMillis() - start < 700) {
            dedup.markAsSeen("B" + (pumped++));
            Thread.sleep(50);
        }
        assertTrue(System.currentTimeMillis() - start >= 700, "traffic must run past the window");

        assertFalse(dedup.isDuplicate("A"),
                "A's window elapsed long ago; continued traffic on other elements must not keep it alive");
        // secondary: only in-window entries remain, not the whole pump history
        assertTrue(dedup.getUniqueCount() <= 8,
                "the set must hold only recent entries, got " + dedup.getUniqueCount()
                        + " after pumping " + pumped);
    }

    @Test
    void uniqueCountShrinksAfterIdleWindow() throws Exception {
        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(client, uniqueName(), Duration.ofMillis(250), s -> s);

        dedup.markAsSeen("x");
        dedup.markAsSeen("y");
        assertEquals(2, dedup.getUniqueCount());

        Thread.sleep(450);
        assertEquals(0, dedup.getUniqueCount(), "entries must expire once the window passes");
    }

    @Test
    void backstopTtlIsStampedOnWrites() {
        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(client, uniqueName(), Duration.ofSeconds(30), s -> s);

        dedup.markAsSeen("k");
        long ttl = dedup.getRemainingTTL();
        assertTrue(ttl > 0, "a write must stamp a TTL on the key");
        assertTrue(ttl >= 29_000, "the backstop must outlive the window");
        assertTrue(ttl <= 91_000, "the backstop must be bounded (window + margin), got " + ttl);
    }

    @Test
    void legacyPlainSetIsMigratedToScoredLayout() throws Exception {
        String name = uniqueName();
        RSet<String> legacy = client.getSet(name);
        legacy.add("old1");
        legacy.add("old2");

        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(client, name, Duration.ofMillis(400), s -> s);

        // first touch migrates the plain set: members count as seen once more, now
        assertTrue(dedup.isDuplicate("old1"), "migrated members are in-window at migration time");
        assertEquals(2, dedup.getUniqueCount());
        assertEquals(RType.ZSET, client.getKeys().getType(name),
                "the key must be re-laid out as a scored set");

        Thread.sleep(600);
        assertFalse(dedup.isDuplicate("old1"), "migrated members expire like any other entry");
        assertEquals(0, dedup.getUniqueCount());
    }
}
