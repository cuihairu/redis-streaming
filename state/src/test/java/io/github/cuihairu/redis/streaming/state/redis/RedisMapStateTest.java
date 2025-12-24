package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMapStateTest {

    @Test
    void delegatesBasicMapOperations() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Integer> map = mock(RMap.class);
        when(redisson.<String, Integer>getMap("k")).thenReturn(map);

        when(map.get("a")).thenReturn(1);
        when(map.containsKey("a")).thenReturn(true);
        when(map.containsKey("x")).thenReturn(false);
        when(map.isEmpty()).thenReturn(false);
        when(map.entrySet()).thenReturn(Map.of("a", 1, "b", 2).entrySet());
        when(map.keySet()).thenReturn(Set.of("a", "b"));
        when(map.values()).thenReturn(Set.of(1, 2));

        RedisMapState<String, Integer> state = new RedisMapState<>(redisson, "k", String.class, Integer.class);

        assertEquals(1, state.get("a"));
        state.put("c", 3);
        verify(map).put("c", 3);

        state.remove("c");
        verify(map).remove("c");

        assertTrue(state.contains("a"));
        assertFalse(state.contains("x"));

        assertFalse(state.isEmpty());
        assertEquals(Set.of("a", "b"), state.keys());
        assertEquals(Set.of(1, 2), state.values());
        assertEquals(Map.of("a", 1, "b", 2).entrySet(), state.entries());

        state.clear();
        verify(map).clear();
        assertEquals("k", state.getKey());
    }
}
