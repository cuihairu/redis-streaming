package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link RedisKeyedSetState}: the current-key guard, add/remove/contains/
 * get/isEmpty/size/clear and the serialization failure wraps.
 */
class RedisKeyedSetStateGapClosureTest {

    private static final String FIELD = "s:user-1";
    private static final String NAME = "s";

    @SuppressWarnings("unchecked")
    private RSet<String> set() {
        return mock(RSet.class);
    }

    @SuppressWarnings("unchecked")
    private RedisKeyedStateStore<String> storeWith(String key, RSet<String> set) {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(key);
        when(store.stateFieldForKey(key)).thenReturn(FIELD);
        when(store.stateItemKey(NAME, FIELD)).thenReturn("ik");
        when(store.stateSetForKey(NAME, FIELD)).thenReturn(set);
        return store;
    }

    private RedisKeyedSetState<String, Integer> state(RedisKeyedStateStore<String> store) {
        return new RedisKeyedSetState<>(store, new StateDescriptor<>(NAME, Integer.class),
                new ObjectMapper());
    }

    @Test
    void everyAccessWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        RedisKeyedSetState<String, Integer> state = state(store);

        assertThrows(IllegalStateException.class, () -> state.add(1));
        assertThrows(IllegalStateException.class, () -> state.remove(1));
        assertThrows(IllegalStateException.class, () -> state.contains(1));
        assertThrows(IllegalStateException.class, state::get);
        assertThrows(IllegalStateException.class, state::isEmpty);
        assertThrows(IllegalStateException.class, state::size);
        assertThrows(IllegalStateException.class, state::clear);
    }

    @Test
    void addReturnsTheRedisAnswerAndRecordsTheWrite() {
        RSet<String> set = set();
        RedisKeyedStateStore<String> store = storeWith("user-1", set);
        when(set.add("42")).thenReturn(true);
        RedisKeyedSetState<String, Integer> state = state(store);

        assertTrue(state.add(42));
        verify(store).recordKeyedStateWrite(eq(NAME), anyLong());
    }

    @Test
    void removeRecordsDeleteAndReturnsTheRedisAnswer() {
        RSet<String> set = set();
        RedisKeyedStateStore<String> store = storeWith("user-1", set);
        when(set.remove("\"x\"")).thenReturn(false);
        RedisKeyedSetState<String, String> state =
                new RedisKeyedSetState<>(store,
                        new io.github.cuihairu.redis.streaming.api.state.StateDescriptor<>(NAME, String.class),
                        new ObjectMapper());

        assertFalse(state.remove("x"));
        verify(store).recordKeyedStateDelete(NAME);
    }

    @Test
    void containsReadsThroughJson() {
        RSet<String> set = set();
        when(set.contains("42")).thenReturn(true);
        RedisKeyedSetState<String, Integer> state = state(storeWith("user-1", set));

        assertTrue(state.contains(42));
    }

    @Test
    void getDeserializesEveryMember() {
        RSet<String> set = set();
        when(set.readAll()).thenReturn(Set.of("1", "2"));
        RedisKeyedSetState<String, Integer> state = state(storeWith("user-1", set));

        java.util.Set<Integer> got = new java.util.HashSet<>();
        state.get().forEach(got::add);
        assertEquals(Set.of(1, 2), got);
    }

    @Test
    void brokenStoredMemberWrapsTheFailure() {
        RSet<String> set = set();
        when(set.readAll()).thenReturn(Set.of("not-json"));
        RedisKeyedSetState<String, Integer> state = state(storeWith("user-1", set));

        RuntimeException e = assertThrows(RuntimeException.class, state::get);
        assertTrue(e.getMessage().contains("Failed to deserialize state value for " + NAME));
    }

    @Test
    void addWithUnserializableMemberWrapsTheFailure() {
        RSet<String> set = set();
        RedisKeyedSetState<String, Object> state = new RedisKeyedSetState<>(
                storeWith("user-1", set), new io.github.cuihairu.redis.streaming.api.state.StateDescriptor<>(NAME, Object.class),
                new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.add(new Object()));
        assertTrue(e.getMessage().contains("Failed to serialize state value for " + NAME));
    }

    @Test
    void isEmptyAndSizeDelegateToTheRedisSet() {
        RSet<String> set = set();
        when(set.isEmpty()).thenReturn(false);
        when(set.size()).thenReturn(3);
        RedisKeyedSetState<String, Integer> state = state(storeWith("user-1", set));

        assertFalse(state.isEmpty());
        assertEquals(3, state.size());
    }

    @Test
    void clearDeletesTheWholePerKeySet() {
        RSet<String> set = set();
        RedisKeyedSetState<String, Integer> state = state(storeWith("user-1", set));

        state.clear();

        verify(set).delete();
    }

    @Test
    void jsonEncodingIsTypeTaggedByTheDescriptorType() {
        // 42 as Integer encodes to "42", as String to "\"42\"" — the descriptor type decides
        RSet<String> set = set();
        RedisKeyedSetState<String, Integer> intState = state(storeWith("user-1", set));
        when(set.add("42")).thenReturn(true);
        assertTrue(intState.add(42));
        verify(set).add("42");

        when(set.add("\"42\"")).thenReturn(true);
        RedisKeyedSetState<String, String> strState =
                new RedisKeyedSetState<>(storeWith("user-1", set),
                        new io.github.cuihairu.redis.streaming.api.state.StateDescriptor<>(NAME, String.class),
                        new ObjectMapper());
        assertTrue(strState.add("42"));
        verify(set).add("\"42\"");
    }
}
