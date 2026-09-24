package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@code RateLimitingSink} factories, deny policies and exception type. */
class RateLimitingSinkCoverageTest {

    private final List<String> written = new ArrayList<>();
    private final StreamSink<String> delegate = written::add;
    private final Function<String, String> keySelector = s -> s;

    @Test
    void ctorWithNullDenyPolicyDefaultsToDrop() throws Exception {
        RateLimiter allowAll = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return false;
            }
        };
        RateLimitingSink<String> sink =
                new RateLimitingSink<>(allowAll, keySelector, delegate, null);
        assertDoesNotThrow(() -> sink.invoke("dropped"));
        assertTrue(written.isEmpty(), "null deny policy behaves as DROP");
    }

    @Test
    void dropFactorySilentlyDiscardsDeniedElement() throws Exception {
        RateLimiter denyAll = (key, nowMillis) -> false;
        RateLimitingSink<String> sink = RateLimitingSink.drop(denyAll, keySelector, delegate);
        sink.invoke("x");
        assertTrue(written.isEmpty());
    }

    @Test
    void throwingFactoryRaisesRateLimitedException() {
        RateLimiter denyAll = (key, nowMillis) -> false;
        RateLimitingSink<String> sink = RateLimitingSink.throwing(denyAll, keySelector, delegate);
        RateLimitingSink.RateLimitedException ex =
                assertThrows(RateLimitingSink.RateLimitedException.class, () -> sink.invoke("k1"));
        assertTrue(ex.getMessage().contains("k1"));
    }

    @Test
    void allowedElementIsForwardedToDelegate() throws Exception {
        RateLimiter allowAll = (key, nowMillis) -> true;
        RateLimitingSink<String> drop = RateLimitingSink.drop(allowAll, keySelector, delegate);
        RateLimitingSink<String> throwing = RateLimitingSink.throwing(allowAll, keySelector, delegate);
        drop.invoke("a");
        throwing.invoke("b");
        assertEquals(List.of("a", "b"), written);
    }

    @Test
    void limiterReceivesSelectedKey() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        RateLimiter recording = (key, nowMillis) -> {
            seen.incrementAndGet();
            assertEquals("key-of-v", key);
            return true;
        };
        RateLimitingSink<String> sink = RateLimitingSink.drop(recording, s -> "key-of-" + s, delegate);
        sink.invoke("v");
        assertEquals(1, seen.get());
    }
}
