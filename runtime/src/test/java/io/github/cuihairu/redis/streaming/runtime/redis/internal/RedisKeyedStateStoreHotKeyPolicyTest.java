package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.runtime.redis.KeyedStateHotKeyException;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hot-key handling policy of {@link RedisKeyedStateStore}: sampled detection arms a per-key
 * window, and while the window is active every write obeys
 * {@link RedisRuntimeConfig.HotKeyPolicy} (LOG_ONLY / THROTTLE / FAIL_FAST).
 */
class RedisKeyedStateStoreHotKeyPolicyTest {

    private RedissonClient redisson;
    private RMap<String, String> map;
    private RedisRuntimeMetricsCollector previousCollector;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        map = mock(RMap.class);
        RSet<String> index = mock(RSet.class);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(mock(RedisRuntimeMetricsCollector.class));
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
    }

    private RedisKeyedStateStore<String> store(RedisRuntimeConfig.HotKeyPolicy policy, long throttleMs,
                                               long hotThreshold, Duration hotInterval, int everyN) {
        return new RedisKeyedStateStore<>(redisson, new ObjectMapper(), "prefix", "job", "topic",
                "group", "op", Duration.ZERO, everyN, 1, hotThreshold, hotInterval,
                policy, throttleMs, false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Long> activeWindows(RedisKeyedStateStore<?> s) throws Exception {
        Field f = RedisKeyedStateStore.class.getDeclaredField("hotKeyActiveUntilMs");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(s);
    }

    @Test
    void failFastThrowsWhileWindowActiveAndPassesAfterExpiry() throws Exception {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.FAIL_FAST, 0,
                1, Duration.ofMillis(200), 1);
        s.setCurrentPartitionId(0);

        // first write only samples and arms the handling window
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        assertEquals(1, activeWindows(s).size());

        // while armed every write fails fast
        KeyedStateHotKeyException thrown = assertThrows(KeyedStateHotKeyException.class,
                () -> s.touch("hot", "s", map));
        assertTrue(thrown.getMessage().contains("hot"));

        // key cools down (below threshold) and the window expires -> writes pass again
        when(map.size()).thenReturn(0);
        Thread.sleep(350);
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        assertTrue(activeWindows(s).isEmpty(), "cooling key must not re-arm the window");
    }

    @Test
    void logOnlyNeverArmsHandlingWindow() throws Exception {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY, 0,
                1, Duration.ofMillis(50), 1);
        s.setCurrentPartitionId(0);

        s.touch("hot", "s", map);
        s.touch("hot", "s", map);
        assertTrue(activeWindows(s).isEmpty(), "LOG_ONLY must never arm enforcement");
    }

    @Test
    void logOnlyWithNullRedisKeyDoesNotCrash() throws Exception {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY, 0,
                1, Duration.ofMillis(50), 1);
        s.setCurrentPartitionId(0);

        // null redisKey should not throw and should not arm enforcement
        assertDoesNotThrow(() -> s.touch(null, "s", map));
        assertTrue(activeWindows(s).isEmpty());
    }

    @Test
    void throttleSleepsWhileActiveAndSkipsAfterExpiry() throws Exception {
        when(map.size()).thenReturn(5);
        long throttleMs = 250;
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.THROTTLE, throttleMs,
                1, Duration.ofMillis(1000), 1);
        s.setCurrentPartitionId(0);

        // arming write: no window yet -> no sleep
        long start = System.nanoTime();
        s.touch("hot", "s", map);
        long armMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(armMs < throttleMs, "arming write must not sleep, took " + armMs + "ms");

        // active window -> flat bounded sleep (no exception)
        start = System.nanoTime();
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        long activeMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(activeMs >= throttleMs, "active write must sleep >= " + throttleMs + "ms, took " + activeMs);
        assertTrue(activeMs < 5000, "sleep must stay bounded, took " + activeMs + "ms");

        // window expired (and key cooled) -> no sleep
        when(map.size()).thenReturn(0);
        Thread.sleep(1200);
        start = System.nanoTime();
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        long expiredMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(expiredMs < throttleMs, "expired write must not sleep, took " + expiredMs + "ms");
    }

    @Test
    void throttleWithZeroCapSleepsNothing() throws Exception {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.THROTTLE, 0,
                1, Duration.ofDays(1), 1);
        s.setCurrentPartitionId(0);
        s.touch("hot", "s", map);
        long start = System.nanoTime();
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000, "zero-cap throttle must not sleep, took " + elapsedMs + "ms");
    }

    @Test
    void throttleRestoresInterruptFlagWhenSleepInterrupted() throws Exception {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.THROTTLE, 60_000,
                1, Duration.ofDays(1), 1);
        s.setCurrentPartitionId(0);
        s.touch("hot", "s", map); // arms the window

        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt(); // flag set inside the worker: sleep throws at once
            try {
                s.touch("hot", "s", map);
            } catch (Throwable t) {
                escaped.set(t);
            }
        });
        worker.start();
        worker.join(5_000);

        assertNull(escaped.get(), "interrupts must never escape touch: " + escaped.get());
        assertTrue(worker.isInterrupted(), "interrupt flag must be restored after the throttle sleep");
    }

    @Test
    void failFastPropagatesUnwrappedFromValueStateUpdate() {
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(RedisRuntimeConfig.HotKeyPolicy.FAIL_FAST, 0,
                1, Duration.ofDays(1), 1);
        s.setCurrentPartitionId(0);
        s.setCurrentKey("k");
        ValueState<String> state = s.getValueState(new StateDescriptor<>("counter", String.class, "0"));

        state.update("v1"); // samples + arms the window
        Throwable thrown = assertThrows(KeyedStateHotKeyException.class, () -> state.update("v2"));
        assertEquals(KeyedStateHotKeyException.class, thrown.getClass(),
                "FAIL_FAST must surface unwrapped (not as a serialization RuntimeException)");
        assertNull(thrown.getCause(), "hot-key failure must not be chained as a cause either");
    }

    @Test
    void configDefaultsValidationAndNullPolicyFallback() {
        RedisRuntimeConfig def = RedisRuntimeConfig.builder().jobName("hk").build();
        assertEquals(RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY, def.getKeyedStateHotKeyPolicy());
        assertEquals(200, def.getKeyedStateHotKeyThrottleMaxMs());

        RedisRuntimeConfig tuned = RedisRuntimeConfig.builder().jobName("hk")
                .keyedStateHotKeyPolicy(RedisRuntimeConfig.HotKeyPolicy.FAIL_FAST)
                .keyedStateHotKeyThrottleMaxMs(50)
                .build();
        assertEquals(RedisRuntimeConfig.HotKeyPolicy.FAIL_FAST, tuned.getKeyedStateHotKeyPolicy());
        assertEquals(50, tuned.getKeyedStateHotKeyThrottleMaxMs());

        assertThrows(IllegalArgumentException.class, () -> RedisRuntimeConfig.builder()
                .jobName("hk").keyedStateHotKeyThrottleMaxMs(-1).build());
        assertEquals(RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY, RedisRuntimeConfig.builder()
                .jobName("hk").keyedStateHotKeyPolicy(null).build().getKeyedStateHotKeyPolicy());

        // store-level null-safe fallbacks: null policy -> LOG_ONLY, negative cap -> 0
        RedisKeyedStateStore<String> s = new RedisKeyedStateStore<>(redisson, new ObjectMapper(),
                "prefix", "job", "topic", "group", "op", Duration.ZERO, 1, 1, 1L, Duration.ofMillis(1),
                null, -5, false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        when(map.size()).thenReturn(5);
        s.setCurrentPartitionId(0);
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
        assertDoesNotThrow(() -> s.touch("hot", "s", map));
    }
}
