package io.github.cuihairu.redis.streaming.examples.ratelimit;

import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemorySlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.NamedRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimitingSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives {@link RateLimitExample} and the same sink wiring it demonstrates.
 * Purely in-memory limiters — runs everywhere, no Redis needed.
 */
class RateLimitExampleTest {

    @Test
    void exampleRunCompletesWithoutExternalDependencies() {
        assertDoesNotThrow(RateLimitExample::run);
    }

    @Test
    void slidingWindowSinkForwardsAtMostTheLimitPerWindow() throws Exception {
        List<String> forwarded = new ArrayList<>();
        RateLimitingSink<String> sink = RateLimitingSink.drop(
                new NamedRateLimiter("sliding-test", new InMemorySlidingWindowRateLimiter(60_000, 5)),
                s -> "user:1",
                forwarded::add);

        for (int i = 0; i < 10; i++) {
            sink.invoke("m-" + i);
        }

        assertEquals(5, forwarded.size(),
                "the window allows 5 — the other 5 must be dropped before reaching the sink");
    }

    @Test
    void tokenBucketSinkAllowsInitialBurstUpToCapacity() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        RateLimitingSink<String> sink = RateLimitingSink.drop(
                new NamedRateLimiter("token-test", new InMemoryTokenBucketRateLimiter(10, 5)),
                s -> "user:1",
                v -> forwarded.incrementAndGet());

        for (int i = 0; i < 20; i++) {
            sink.invoke("m-" + i);
        }

        assertEquals(10, forwarded.get(),
                "burst of 10 passes immediately; the 5/s refill cannot cover the rest within the loop");
    }
}
