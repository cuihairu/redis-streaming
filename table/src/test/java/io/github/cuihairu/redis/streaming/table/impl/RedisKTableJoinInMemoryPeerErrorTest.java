package io.github.cuihairu.redis.streaming.table.impl;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for B-23: when a join/leftJoin against an {@link InMemoryKTable} peer
 * failed, the catch block dereferenced {@code otherTable.tableName} — but {@code otherTable}
 * is only set for RedisKTable peers, so the log call itself threw a bare NPE that replaced
 * the original joiner exception and masked the root cause.
 */
class RedisKTableJoinInMemoryPeerErrorTest {

    private static RedissonClient inMemoryRedisson() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        Map<String, Map<String, String>> storage = new ConcurrentHashMap<>();
        when(redissonClient.getMap(anyString(), eq(StringCodec.INSTANCE))).thenAnswer(inv -> {
            Map<String, String> backing = storage.computeIfAbsent(inv.getArgument(0), k -> new ConcurrentHashMap<>());
            return mockRMap(backing);
        });
        return redissonClient;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RMap<String, String> mockRMap(Map<String, String> backing) {
        RMap<String, String> map = mock(RMap.class);
        when(map.get(anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            return backing.get(String.valueOf(k));
        });
        when(map.put(anyString(), anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            return backing.put(String.valueOf(k), String.valueOf(v));
        });
        when(map.entrySet()).thenAnswer(inv -> backing.entrySet());
        doAnswer(inv -> {
            backing.clear();
            return null;
        }).when(map).clear();
        return map;
    }

    @Test
    void joinFailureAgainstInMemoryPeerPreservesRootCause() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "b23-l", String.class, String.class);
        left.put("k", "L");
        InMemoryKTable<String, String> right = new InMemoryKTable<>();
        right.put("k", "R");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> left.join(right, (l, r) -> {
                    throw new IllegalStateException("joiner bug");
                }));
        assertEquals("Join failed", ex.getMessage(),
                "old code: the catch block itself NPEd on otherTable.tableName and the bare NPE propagated");
        assertInstanceOf(IllegalStateException.class, ex.getCause(),
                "the original joiner exception must be preserved as the cause");
        assertEquals("joiner bug", ex.getCause().getMessage());
    }

    @Test
    void leftJoinFailureAgainstInMemoryPeerPreservesRootCause() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "b23-lj", String.class, String.class);
        left.put("k", "L");
        InMemoryKTable<String, String> right = new InMemoryKTable<>();
        right.put("k", "R");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> left.leftJoin(right, (l, r) -> {
                    throw new IllegalStateException("joiner bug");
                }));
        assertEquals("Left join failed", ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("joiner bug", ex.getCause().getMessage());
    }

    @Test
    void joinAgainstInMemoryPeerStillSucceedsWhenJoinerWorks() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "b23-ok", String.class, String.class);
        left.put("k", "L");
        InMemoryKTable<String, String> right = new InMemoryKTable<>();
        right.put("k", "R");

        @SuppressWarnings("unchecked")
        RedisKTable<String, String> joined =
                (RedisKTable<String, String>) left.join(right, (l, r) -> l + "-" + r);

        assertEquals("L-R", joined.get("k"));
    }
}
