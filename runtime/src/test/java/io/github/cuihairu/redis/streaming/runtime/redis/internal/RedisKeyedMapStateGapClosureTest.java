package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link RedisKeyedMapState}: the current-key guard, per-entry get/put/
 * remove/contains, the collection reads (entries/keys/values/isEmpty), clear, and the
 * serialize/deserialize failure wraps.
 */
class RedisKeyedMapStateGapClosureTest {

    private static final String FIELD = "s:user-1";
    private static final String NAME = "m";

    @SuppressWarnings("unchecked")
    private RMap<String, String> map() {
        return mock(RMap.class);
    }

    @SuppressWarnings("unchecked")
    private RedisKeyedStateStore<String> storeWith(String key, RMap<String, String> map) {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(key);
        when(store.stateFieldForKey(key)).thenReturn(FIELD);
        when(store.stateMapRefForKey(NAME, FIELD))
                .thenReturn(new RedisKeyedStateStore.StateMapRef("rk", map));
        return store;
    }

    private RedisKeyedMapState<String, String, Integer> state(RedisKeyedStateStore<String> store) {
        return new RedisKeyedMapState<>(store, NAME, String.class, Integer.class, new ObjectMapper());
    }

    @Test
    void everyAccessWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        RedisKeyedMapState<String, String, Integer> state = state(store);

        assertThrows(IllegalStateException.class, () -> state.get("a"));
        assertThrows(IllegalStateException.class, () -> state.put("a", 1));
        assertThrows(IllegalStateException.class, () -> state.remove("a"));
        assertThrows(IllegalStateException.class, () -> state.contains("a"));
        assertThrows(IllegalStateException.class, state::entries);
        assertThrows(IllegalStateException.class, state::keys);
        assertThrows(IllegalStateException.class, state::values);
        assertThrows(IllegalStateException.class, state::isEmpty);
        assertThrows(IllegalStateException.class, state::clear);
    }

    @Test
    void getDeserializesTheStoredJsonAndMissesReturnNull() {
        RMap<String, String> map = map();
        when(map.get("\"a\"")).thenReturn("42");
        when(map.get("\"b\"")).thenReturn(null);
        RedisKeyedStateStore<String> store = storeWith("user-1", map);

        assertEquals(42, state(store).get("a"));
        assertNull(state(store).get("b"));
        verify(store, org.mockito.Mockito.times(2)).recordKeyedStateRead(eq(NAME), anyLong());
    }

    @Test
    void putSerializesAndTouchesTheHash() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);

        state(store).put("a", 42);

        verify(map).put("\"a\"", "42");
        verify(store).touch(eq("rk"), eq(NAME), eq(map));
        verify(store).recordKeyedStateWrite(eq(NAME), anyLong());
    }

    @Test
    void putWithNullValueRemovesTheEntry() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);

        state(store).put("a", null);

        verify(map).remove("\"a\"");
        verify(store).recordKeyedStateDelete(NAME);
        verify(store).recordKeyedStateWrite(eq(NAME), anyLong());
    }

    @Test
    void putWithUnserializableValueFailsBeforeTouchingTheHash() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        RedisKeyedMapState<String, String, Object> state =
                new RedisKeyedMapState<>(store, NAME, String.class, Object.class, new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.put("a", new Object()));
        assertTrue(e.getMessage().contains("Failed to serialize state value for " + NAME));
        verify(map, never()).put(anyString(), anyString());
    }

    @Test
    void putWithUnserializableKeyFailsWrapped() {
        RMap<String, String> map = map();
        RedisKeyedMapState<String, Object, Integer> state =
                new RedisKeyedMapState<>(storeWith("user-1", map), NAME, Object.class, Integer.class,
                        new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.put(new Object(), 1));
        assertTrue(e.getMessage().contains("Failed to serialize state map key for " + NAME));
    }

    @Test
    void removeAndContainsAndIsEmptyHitTheHash() {
        RMap<String, String> map = map();
        when(map.containsKey("\"a\"")).thenReturn(true);
        when(map.isEmpty()).thenReturn(false);
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        RedisKeyedMapState<String, String, Integer> state = state(store);

        assertTrue(state.contains("a"));
        assertFalse(state.isEmpty());
        state.remove("a");

        verify(map).remove("\"a\"");
        verify(store).recordKeyedStateDelete(NAME);
    }

    @Test
    void collectionReadsDecodeKeysAndValues() {
        RMap<String, String> map = map();
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("\"a\"", "1");
        stored.put("\"b\"", "2");
        when(map.readAllMap()).thenReturn(stored);
        RedisKeyedMapState<String, String, Integer> state = state(storeWith("user-1", map));

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : state.entries()) {
            seen.put(e.getKey(), e.getValue());
        }
        assertEquals(Map.of("a", 1, "b", 2), seen);

        Set<String> keys = new HashSet<>();
        state.keys().forEach(keys::add);
        assertEquals(Set.of("a", "b"), keys);

        List<Integer> values = new java.util.ArrayList<>();
        state.values().forEach(values::add);
        assertEquals(List.of(1, 2), values);
    }

    @Test
    void brokenStoredValueWrapsTheFailure() {
        RMap<String, String> map = map();
        when(map.readAllMap()).thenReturn(Map.of("\"a\"", "not-json"));
        RedisKeyedMapState<String, String, Integer> state = state(storeWith("user-1", map));

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.values().iterator().next());
        assertTrue(e.getMessage().contains("Failed to deserialize state value for " + NAME));
    }

    @Test
    void brokenStoredFieldKeyWrapsTheFailure() {
        RMap<String, String> map = map();
        when(map.readAllMap()).thenReturn(Map.of("not-json", "1"));
        RedisKeyedMapState<String, String, Integer> state = state(storeWith("user-1", map));

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.keys().iterator().next());
        assertTrue(e.getMessage().contains("Failed to deserialize state map key for " + NAME));
    }

    @Test
    void clearDeletesTheWholePerKeyHash() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);

        state(store).clear();

        verify(map).delete();
        verify(store).recordKeyedStateDelete(NAME);
    }
}
