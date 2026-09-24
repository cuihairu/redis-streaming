package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.SelectiveFailingMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fault-injection and edge branches of {@link RedisKeyedStateStore} with mocked Redisson
 * collaborators: multi-shard key derivation, index registration failures, ensureSchema error arms
 * for read/write/clear, touch TTL/size-report/hot-key failure arms and the recordKeyedState*
 * metric failure arms (via a selectively failing metrics collector).
 */
class RedisKeyedStateStoreFaultInjectionTest {

    private static final String PREFIX = "it-r2-kss";
    private static final String SCHEMA_KEY = PREFIX + ":job:stateSchema";

    private RedissonClient redisson;
    private RKeys rkeys;
    private RSet<String> index;
    private RMap<String, String> schema;
    private RedisRuntimeMetricsCollector previousCollector;
    private SelectiveFailingMetricsCollector collector;
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        rkeys = mock(RKeys.class);
        index = (RSet<String>) mock(RSet.class);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> maps.computeIfAbsent(inv.getArgument(0), k -> mock(RMap.class)));
        schema = maps.computeIfAbsent(SCHEMA_KEY, k -> mock(RMap.class));
        when(index.readAll()).thenReturn(new java.util.HashSet<>());

        collector = new SelectiveFailingMetricsCollector();
        previousCollector = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(collector);
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
    }

    private RedisKeyedStateStore<String> store(Duration ttl, int sizeReportEveryN,
                                              int shards, long hotKeyThreshold, Duration hotKeyInterval,
                                              boolean evolution, RedisRuntimeConfig.StateSchemaMismatchPolicy policy) {
        return new RedisKeyedStateStore<>(redisson, new ObjectMapper(), PREFIX, "job", "t", "g", "op",
                ttl, sizeReportEveryN, shards, hotKeyThreshold, hotKeyInterval, evolution, policy);
    }

    @Test
    void stateMapRefShardsAcrossKeysAndAcceptsNullField() {
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 0, 2, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.setCurrentPartitionId(0);
        RedisKeyedStateStore.StateMapRef nullField = s.stateMapRef("st", null);
        RedisKeyedStateStore.StateMapRef withField = s.stateMapRef("st", "field-a");
        assertTrue(nullField.redisKey().endsWith(":shard:0"), nullField.redisKey());
        assertTrue(withField.redisKey().contains(":shard:"), withField.redisKey());
        assertNotNull(nullField.map());
        assertSame(nullField.map(), s.stateMapRef("st", null).map());
    }

    @Test
    void stateMapRefToleratesIndexRegistrationFailure() {
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 0, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.setCurrentPartitionId(0);
        doThrow(new IllegalStateException("index add down")).when(index).add(anyString());
        RedisKeyedStateStore.StateMapRef ref = s.stateMapRef("st", "f");
        assertNotNull(ref.map());
    }

    @Test
    void ensureSchemaFailureArmsForReadWriteAndClear() {
        RedisKeyedStateStore<String> failStore = store(Duration.ZERO, 0, 1, 0L, Duration.ofMinutes(1),
                true, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        failStore.setCurrentPartitionId(0);

        String k1 = PREFIX + ":r1";
        when(schema.get(k1)).thenThrow(new IllegalStateException("schema get down"));
        failStore.ensureSchema(new RedisKeyedStateStore.StateMapRef(k1, mock(RMap.class)),
                new StateDescriptor<>("st", String.class, null, 1));

        String k2 = PREFIX + ":r2";
        when(schema.get(k2)).thenReturn(null);
        when(schema.put(eq(k2), anyString())).thenThrow(new IllegalStateException("schema put down"));
        failStore.ensureSchema(new RedisKeyedStateStore.StateMapRef(k2, mock(RMap.class)),
                new StateDescriptor<>("st", String.class, null, 1));

        String k3 = PREFIX + ":r3";
        when(schema.get(k3)).thenReturn("  ");
        failStore.ensureSchema(new RedisKeyedStateStore.StateMapRef(k3, mock(RMap.class)),
                new StateDescriptor<>("st", String.class, null, 1));

        String k4 = PREFIX + ":r4";
        when(schema.get(k4)).thenReturn(String.class.getName() + "|1");
        failStore.ensureSchema(new RedisKeyedStateStore.StateMapRef(k4, mock(RMap.class)),
                new StateDescriptor<>("st", String.class, null, 1));

        RedisKeyedStateStore<String> clearStore = store(Duration.ZERO, 0, 1, 0L, Duration.ofMinutes(1),
                true, RedisRuntimeConfig.StateSchemaMismatchPolicy.CLEAR);
        clearStore.setCurrentPartitionId(0);
        String k5 = PREFIX + ":r5";
        when(schema.get(k5)).thenReturn("java.lang.Long|9");
        doThrow(new IllegalStateException("keys delete down")).when(rkeys).delete(k5);
        when(schema.put(eq(k5), anyString())).thenThrow(new IllegalStateException("schema put down"));
        clearStore.ensureSchema(new RedisKeyedStateStore.StateMapRef(k5, mock(RMap.class)),
                new StateDescriptor<>("st", String.class, null, 1));
    }

    @Test
    void touchCoversTtlAndSizeReportFailureArms() {
        RedisKeyedStateStore<String> s = store(Duration.ofSeconds(30), 1, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.setCurrentPartitionId(0);

        @SuppressWarnings("unchecked")
        RMap<String, String> expireFails = mock(RMap.class);
        doThrow(new IllegalStateException("expire down")).when(expireFails).expire(any(Duration.class));
        s.touch(PREFIX + ":e", "st", expireFails);
        verify(expireFails).expire(any(Duration.class));

        @SuppressWarnings("unchecked")
        RMap<String, String> sizeFails = mock(RMap.class);
        when(sizeFails.size()).thenThrow(new IllegalStateException("size down"));
        s.touch(PREFIX + ":s", "st", sizeFails);
    }

    @Test
    void touchHotKeyArmsWithNullKeyAndMetricFailure() {
        collector.failOn("incKeyedStateHotKey");
        RedisKeyedStateStore<String> s = store(Duration.ofSeconds(-5), 1, 1, 1L, Duration.ZERO,
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.setCurrentPartitionId(0);

        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(map.size()).thenReturn(7);
        s.touch(null, "st", map);
        s.touch(PREFIX + ":hot", "st", map);

        s.clearCurrentPartitionId();
        s.touch(PREFIX + ":hot2", "st", map);
        s.setCurrentPartitionId(-3);
        s.touch(PREFIX + ":hot3", "st", map);
    }

    @Test
    void touchHotKeyWarnIntervalArms() throws Exception {
        RedisKeyedStateStore<String> longInterval = store(Duration.ZERO, 1, 1, 1L, Duration.ofHours(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        longInterval.setCurrentPartitionId(0);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(map.size()).thenReturn(7);
        longInterval.touch(PREFIX + ":iv", "st", map);
        longInterval.touch(PREFIX + ":iv", "st", map);

        RedisKeyedStateStore<String> shortInterval = store(Duration.ZERO, 1, 1, 1L, Duration.ofMillis(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        shortInterval.setCurrentPartitionId(0);
        shortInterval.touch(PREFIX + ":iv2", "st", map);
        Thread.sleep(5);
        shortInterval.touch(PREFIX + ":iv2", "st", map);
    }

    @Test
    void touchSizedReportMetricsFailureIsSwallowed() {
        collector.failOn("recordKeyedStateSize");
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 1, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.setCurrentPartitionId(0);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(map.size()).thenReturn(3);
        s.touch(PREFIX + ":sz", "st", map);
    }

    @Test
    void registerStateKeyCoversRegistrationAndExpireFailures() {
        RedisKeyedStateStore<String> s = store(Duration.ofSeconds(30), 0, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        doThrow(new IllegalStateException("index add down")).when(index).add(anyString());
        doThrow(new IllegalStateException("expire down")).when(rkeys).expire(any(Duration.class), anyString());
        s.registerStateKey(PREFIX + ":aux");
    }

    @Test
    void registerStateKeyAcceptsJunkKeysAndNegativeTtl() {
        RedisKeyedStateStore<String> s = store(Duration.ofSeconds(-1), 0, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
        s.registerStateKey(null);
        s.registerStateKey(" ");
        s.registerStateKey(PREFIX + ":aux");
    }

    @Test
    void recordMetricHooksSwallowCollectorFailures() {
        collector.failOn("incKeyedStateRead", "recordKeyedStateReadLatency",
                "incKeyedStateWrite", "recordKeyedStateWriteLatency", "incKeyedStateDelete");
        RedisKeyedStateStore<String> s = store(Duration.ZERO, 0, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);

        s.setCurrentPartitionId(2);
        s.recordKeyedStateRead("st", 5L);
        s.recordKeyedStateRead("st", -1L);
        s.recordKeyedStateWrite("st", 0L);
        s.recordKeyedStateWrite("st", -2L);
        s.recordKeyedStateDelete("st");

        s.clearCurrentPartitionId();
        s.recordKeyedStateRead("st", 1L);
        s.recordKeyedStateWrite("st", 1L);
        s.recordKeyedStateDelete("st");
        s.setCurrentPartitionId(-1);
        s.recordKeyedStateRead("st", 1L);
    }
}
