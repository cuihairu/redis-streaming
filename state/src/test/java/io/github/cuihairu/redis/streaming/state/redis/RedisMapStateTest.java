package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    @Test
    void getReturnsNullForNonExistentKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);
        when(map.get("nonexistent")).thenReturn(null);

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        assertNull(state.get("nonexistent"));
    }

    @Test
    void putReturnsPreviousValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);
        when(map.put("key", "newValue")).thenReturn("oldValue");

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        state.put("key", "newValue");
        verify(map).put("key", "newValue");
    }

    @Test
    void removeReturnsPreviousValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);
        when(map.remove("key")).thenReturn("value");

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        state.remove("key");
        verify(map).remove("key");
    }

    @Test
    void containsReturnsFalseForNonExistentKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);
        when(map.containsKey("missing")).thenReturn(false);

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        assertFalse(state.contains("missing"));
    }

    @Test
    void isEmptyReturnsTrueWhenMapIsEmpty() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);
        when(map.isEmpty()).thenReturn(true);

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        assertTrue(state.isEmpty());
    }

    @Test
    void clearRemovesAllEntries() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("k")).thenReturn(map);

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "k", String.class, String.class);
        state.clear();

        verify(map).clear();
    }

    @Test
    void entriesReturnsAllEntries() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Integer> map = mock(RMap.class);
        when(redisson.<String, Integer>getMap("k")).thenReturn(map);
        Map<String, Integer> testData = Map.of("a", 1, "b", 2, "c", 3);
        when(map.entrySet()).thenReturn(testData.entrySet());

        RedisMapState<String, Integer> state = new RedisMapState<>(redisson, "k", String.class, Integer.class);
        assertEquals(testData.entrySet(), state.entries());
    }

    @Test
    void keysReturnsAllKeys() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Integer> map = mock(RMap.class);
        when(redisson.<String, Integer>getMap("k")).thenReturn(map);
        Set<String> testKeys = Set.of("x", "y", "z");
        when(map.keySet()).thenReturn(testKeys);

        RedisMapState<String, Integer> state = new RedisMapState<>(redisson, "k", String.class, Integer.class);
        assertEquals(testKeys, state.keys());
    }

    @Test
    void valuesReturnsAllValues() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Integer> map = mock(RMap.class);
        when(redisson.<String, Integer>getMap("k")).thenReturn(map);
        Set<Integer> testValues = Set.of(10, 20, 30);
        when(map.values()).thenReturn(testValues);

        RedisMapState<String, Integer> state = new RedisMapState<>(redisson, "k", String.class, Integer.class);
        assertEquals(testValues, state.values());
    }

    @Test
    void getKeyReturnsCorrectKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getMap(anyString())).thenReturn(mock(RMap.class));

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "testKey", String.class, String.class);
        assertEquals("testKey", state.getKey());
    }

    @Test
    void constructorWithDifferentKeyTypes() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<Integer, String> map = mock(RMap.class);
        when(redisson.<Integer, String>getMap("k")).thenReturn(map);

        RedisMapState<Integer, String> state = new RedisMapState<>(redisson, "k", Integer.class, String.class);
        state.put(1, "one");
        verify(map).put(1, "one");
    }

    @Test
    void constructorWithDifferentValueTypes() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Double> map = mock(RMap.class);
        when(redisson.<String, Double>getMap("k")).thenReturn(map);

        RedisMapState<String, Double> state = new RedisMapState<>(redisson, "k", String.class, Double.class);
        state.put("pi", 3.14159);
        verify(map).put("pi", 3.14159);
    }

    @Test
    void multipleOperationsOnSameState() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Integer> map = mock(RMap.class);
        when(redisson.<String, Integer>getMap("k")).thenReturn(map);
        when(map.get(any())).thenReturn(null);
        when(map.isEmpty()).thenReturn(true);

        RedisMapState<String, Integer> state = new RedisMapState<>(redisson, "k", String.class, Integer.class);

        state.put("a", 1);
        state.put("b", 2);
        state.put("c", 3);

        verify(map).put("a", 1);
        verify(map).put("b", 2);
        verify(map).put("c", 3);

        state.remove("a");
        verify(map).remove("a");
    }

    @Test
    void getMapIsCalledForEachOperation() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("test")).thenReturn(map);

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "test", String.class, String.class);

        state.get("key");
        state.put("key", "value");
        state.contains("key");
        state.keys();
        state.values();
        state.entries();
        state.isEmpty();
        state.clear();

        // Verify getMap was called each time
        verify(redisson, times(8)).getMap("test");
    }

    @Test
    void stateWithComplexKeyType() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<List<String>, String> map = mock(RMap.class);
        when(redisson.<List<String>, String>getMap("k")).thenReturn(map);

        RedisMapState<List<String>, String> state = new RedisMapState<>(
                redisson, "k",
                (Class<List<String>>) (Object) List.class,
                String.class
        );

        List<String> complexKey = List.of("a", "b");
        state.put(complexKey, "value");
        verify(map).put(complexKey, "value");
    }

    @Test
    void stateWithComplexValueType() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, Map<String, Integer>> map = mock(RMap.class);
        when(redisson.<String, Map<String, Integer>>getMap("k")).thenReturn(map);

        RedisMapState<String, Map<String, Integer>> state = new RedisMapState<>(
                redisson, "k",
                String.class,
                (Class<Map<String, Integer>>) (Object) Map.class
        );

        Map<String, Integer> complexValue = new HashMap<>();
        complexValue.put("x", 1);
        state.put("key", complexValue);
        verify(map).put(eq("key"), any());
    }

    @Test
    void emptyStateBehavior() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("empty")).thenReturn(map);
        when(map.isEmpty()).thenReturn(true);
        when(map.containsKey(any())).thenReturn(false);
        when(map.get(any())).thenReturn(null);
        when(map.entrySet()).thenReturn(Set.of());
        when(map.keySet()).thenReturn(Set.of());
        when(map.values()).thenReturn(Set.of());

        RedisMapState<String, String> state = new RedisMapState<>(redisson, "empty", String.class, String.class);

        assertTrue(state.isEmpty());
        assertFalse(state.contains("any"));
        assertNull(state.get("any"));
        assertEquals(Set.of(), state.keys());
        assertEquals(Set.of(), state.values());
        assertEquals(Set.of(), state.entries());
    }
}
