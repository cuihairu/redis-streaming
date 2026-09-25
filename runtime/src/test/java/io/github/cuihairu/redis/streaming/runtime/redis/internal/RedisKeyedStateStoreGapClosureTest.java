package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisKeyedStateStore}: schema guard for blank keys, TTL application
 * arms in {@code touch}/{@code registerStateKey} (including the defensive {@code null}-TTL arm
 * reached by swapping the normalized field reflectively), the hot-key warning throttle and the
 * missing/invalid partition-id guards of the state metrics recorders.
 */
class RedisKeyedStateStoreGapClosureTest {

    private RedissonClient redisson;
    private RSet<String> index;
    private RMap<String, String> schema;
    private RKeys rkeys;
    private RedisRuntimeMetricsCollector collector;
    private RedisRuntimeMetricsCollector previousCollector;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        index = mock(RSet.class);
        schema = mock(RMap.class);
        rkeys = mock(RKeys.class);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) schema);
        when(redisson.getKeys()).thenReturn(rkeys);
        collector = mock(RedisRuntimeMetricsCollector.class);
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(collector);
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
    }

    private RedisKeyedStateStore<String> store(Duration ttl, int everyN, long hotKeyThreshold,
                                               Duration hotKeyInterval) {
        return new RedisKeyedStateStore<>(redisson, new ObjectMapper(), "prefix", "job", "topic",
                "group", "op", ttl, everyN, 1, hotKeyThreshold, hotKeyInterval,
                true, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = RedisKeyedStateStore.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void ensureSchemaIgnoresNullAndBlankRedisKeys() {
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 0, 0, null);
        RMap<String, String> map = mock(RMap.class);
        StateDescriptor<String> d = new StateDescriptor<>("s", String.class);
        assertDoesNotThrow(() -> s.ensureSchema(new RedisKeyedStateStore.StateMapRef(null, map), d));
        assertDoesNotThrow(() -> s.ensureSchema(new RedisKeyedStateStore.StateMapRef("   ", map), d));
    }

    @Test
    void touchAppliesTtlOnlyForPositiveTtl() throws Exception {
        RMap<String, String> map = mock(RMap.class);

        RedisKeyedStateStore<String> positive = store(Duration.ofSeconds(30), 0, 0, null);
        positive.touch("k", "s", map);
        verify(map).expire((Duration) Duration.ofSeconds(30));

        RedisKeyedStateStore<String> zero = store(Duration.ZERO, 0, 0, null);
        zero.touch("k", "s", map);
        RedisKeyedStateStore<String> negative = store(Duration.ofSeconds(-7), 0, 0, null);
        negative.touch("k", "s", map);
        verify(map, times(1)).expire(any(Duration.class));

        RedisKeyedStateStore<String> nulled = store(Duration.ofSeconds(30), 0, 0, null);
        setField(nulled, "stateTtl", null);
        assertDoesNotThrow(() -> nulled.touch("k", "s", map));
        verify(map, times(1)).expire(any(Duration.class));
    }

    @Test
    void touchWarnsOncePerIntervalForHotKeys() throws Exception {
        RMap<String, String> map = mock(RMap.class);
        when(map.size()).thenReturn(5);
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 1, 2, Duration.ofDays(1));
        s.setCurrentPartitionId(0);

        s.touch("hot", "s", map);
        verify(collector, times(1)).incKeyedStateHotKey("job", "topic", "group", "op", "s", 0, 5);

        s.touch("hot", "s", map);
        verify(collector, times(1)).incKeyedStateHotKey("job", "topic", "group", "op", "s", 0, 5);

        Field f = RedisKeyedStateStore.class.getDeclaredField("hotKeyLastWarnAtMs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var last = (java.util.concurrent.ConcurrentHashMap<String, Long>) f.get(s);
        last.put("hot", System.currentTimeMillis() - 3L * 24 * 3600 * 1000);

        s.touch("hot", "s", map);
        verify(collector, times(2)).incKeyedStateHotKey("job", "topic", "group", "op", "s", 0, 5);
    }

    @Test
    void touchWarnsOnEveryWriteWhenIntervalIsZeroOrMissing() throws Exception {
        RMap<String, String> map = mock(RMap.class);
        when(map.size()).thenReturn(5);

        RedisKeyedStateStore<String> zeroInterval = store(Duration.ZERO, 1, 2, Duration.ofSeconds(-1));
        zeroInterval.setCurrentPartitionId(0);
        zeroInterval.touch("hot", "s", map);
        zeroInterval.touch("hot", "s", map);
        verify(collector, times(2)).incKeyedStateHotKey("job", "topic", "group", "op", "s", 0, 5);

        org.mockito.Mockito.clearInvocations(collector);
        RedisKeyedStateStore<String> missingInterval = store(Duration.ZERO, 1, 2, Duration.ofMinutes(1));
        setField(missingInterval, "keyedStateHotKeyWarnInterval", null);
        missingInterval.setCurrentPartitionId(0);
        assertDoesNotThrow(() -> missingInterval.touch("hot2", "s", map));
        verify(collector, times(1)).incKeyedStateHotKey("job", "topic", "group", "op", "s", 0, 5);
    }

    @Test
    void registerStateKeyExpiresOnlyWithPositiveTtl() throws Exception {
        RedisKeyedStateStore<String> positive = store(Duration.ofSeconds(15), 0, 0, null);
        positive.registerStateKey("rk");
        verify(rkeys).expire(Duration.ofSeconds(15), "rk");

        RedisKeyedStateStore<String> zero = store(Duration.ZERO, 0, 0, null);
        zero.registerStateKey("rk");
        RedisKeyedStateStore<String> negative = store(Duration.ofSeconds(-3), 0, 0, null);
        negative.registerStateKey("rk");
        RedisKeyedStateStore<String> nulled = store(Duration.ofSeconds(15), 0, 0, null);
        setField(nulled, "stateTtl", null);
        nulled.registerStateKey("rk");
        verify(rkeys, times(1)).expire(any(), anyString());
    }

    @Test
    void stateMetricsRecordersRequireValidPartitionId() {
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 0, 0, null);

        s.recordKeyedStateWrite("s", 1L);
        s.recordKeyedStateDelete("s");
        verify(collector, never()).incKeyedStateWrite(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(collector, never()).incKeyedStateDelete(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());

        s.setCurrentPartitionId(-1);
        s.recordKeyedStateWrite("s", 1L);
        s.recordKeyedStateDelete("s");
        verify(collector, never()).incKeyedStateDelete("job", "topic", "group", "op", "s", -1);

        s.setCurrentPartitionId(3);
        s.recordKeyedStateWrite("s", 1L);
        s.recordKeyedStateWrite("s", -1L);
        s.recordKeyedStateDelete("s");
        verify(collector, times(2)).incKeyedStateWrite("job", "topic", "group", "op", "s", 3);
        verify(collector).incKeyedStateDelete("job", "topic", "group", "op", "s", 3);
        verify(collector).recordKeyedStateWriteLatency(eq("job"), eq("topic"), eq("group"), eq("op"), eq("s"), eq(3), eq(1L));
    }
}
