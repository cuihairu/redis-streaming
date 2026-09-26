package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-37 end-to-end regression against real Redis: a 1-minute window's write used to
 * land on the same key as a 1-hour window (identical window start on the hour), so
 * the hour window's read range [H, H+1h) also counted the minute window's values.
 * Each window shape must own its key.
 *
 * <p>Uses only the public API, so it reproduces on the pre-fix code.
 */
@Tag("integration")
class WindowAggregatorKeyIsolationIntegrationTest {

    private RedissonClient client;
    private WindowAggregator aggregator;
    private String prefix;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        prefix = "b37-" + UUID.randomUUID().toString().substring(0, 8);
        aggregator = new WindowAggregator(client, prefix);
        aggregator.registerFunction("COUNT", values -> (long) values.size());
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern(prefix + ":*");
        client.shutdown();
    }

    @Test
    void minuteWindowValuesDoNotLeakIntoHourWindowResults() {
        Instant onTheHour = Instant.ofEpochMilli(3_600_000);

        aggregator.addValue(TumblingWindow.ofHours(1), "k", "hourVal", onTheHour.plusMillis(300_000));
        Long hourCount0 = aggregator.getAggregatedResult(
                TumblingWindow.ofHours(1), "k", "COUNT", onTheHour.plusMillis(300_000));
        assertEquals(Long.valueOf(1L), hourCount0,
                "sanity: the hour window sees its value");

        // a 1-minute window's add at :30 has window start H — under the old key layout
        // this wrote into the hour window's key, and the hour read range [H, H+1h)
        // then also counted the minute value (cross-window contamination)
        aggregator.addValue(TumblingWindow.ofMinutes(1), "k", "minuteVal", onTheHour.plusMillis(30_000));

        Long hourCount = aggregator.getAggregatedResult(
                TumblingWindow.ofHours(1), "k", "COUNT", onTheHour.plusMillis(300_000));
        assertEquals(Long.valueOf(1L), hourCount,
                "the hour window must not count a different-size window's values (B-37)");
        Long minuteCount = aggregator.getAggregatedResult(
                TumblingWindow.ofMinutes(1), "k", "COUNT", onTheHour.plusMillis(30_000));
        assertEquals(Long.valueOf(1L), minuteCount,
                "the minute window must see its own value only");
    }
}
