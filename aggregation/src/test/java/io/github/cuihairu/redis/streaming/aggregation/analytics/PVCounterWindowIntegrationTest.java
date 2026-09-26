package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B-41 end-to-end regression on real Redis: counts follow the trailing window
 * {@code [now - window, now]} — a future-dated event is stored but does not count,
 * and an event older than the window changes nothing. (The deterministic pre-fix
 * discriminators are the mock-based {@link PVCounterWindowSemanticsTest}.)
 */
@Tag("integration")
class PVCounterWindowIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(5);

    private RedissonClient client;
    private PVCounter counter;
    private String prefix;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        prefix = "b41-" + UUID.randomUUID().toString().substring(0, 8);
        counter = new PVCounter(client, prefix, WINDOW);
    }

    @AfterEach
    void tearDown() {
        counter.close();
        client.getKeys().deleteByPattern(prefix + ":*");
        client.shutdown();
    }

    @Test
    void futureEventsAreStoredButDoNotCountAndLateEventsChangeNothing() {
        Instant now = Instant.now();
        String page = "home";

        counter.recordPageView(page, now.minusMillis(1_000));
        counter.recordPageView(page, now.minusMillis(2_000));
        counter.recordPageView(page, now.minusMillis(3_000));
        assertEquals(3L, counter.getPageViewCount(page), "sanity: three in-window views");

        // an event older than the 5s window: recorded? counted? must be a no-op
        long afterLate = counter.recordPageView(page, now.minusMillis(6_000));
        assertEquals(3L, afterLate, "an out-of-window event must not change the count");

        // a future-dated event is stored but does not count until the window reaches it
        long afterFuture = counter.recordPageView(page, now.plusMillis(10_000));
        assertEquals(3L, afterFuture, "a future-dated event must not count yet");
        assertEquals(3L, counter.getPageViewCount(page));
        assertEquals(4L, counter.getPageViewCount(page, now.minusSeconds(30), now.plusSeconds(30)),
                "the future event is stored (range-visible) though it does not count; "
                        + "the late event was rejected and is absent");
    }
}
