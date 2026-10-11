package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.runtime.redis.KeyedStateHotKeyException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link RedisKeyedValueState}: the guard branches (no current key), the
 * read paths (hit / miss → default / broken JSON), the write paths (serialize / remove on
 * null / hot-key FAIL_FAST kept unwrapped) and {@code clear}.
 */
class RedisKeyedValueStateGapClosureTest {

    private static final String FIELD = "s:user-1";

    @SuppressWarnings("unchecked")
    private RMap<String, String> map() {
        return mock(RMap.class);
    }

    @SuppressWarnings("unchecked")
    private RedisKeyedStateStore<String> storeWith(String key, RMap<String, String> map) {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(key);
        when(store.stateFieldForKey(key)).thenReturn(FIELD);
        when(store.stateMapRef(anyString(), eq(FIELD)))
                .thenReturn(new RedisKeyedStateStore.StateMapRef("rk", map));
        return store;
    }

    @Test
    void valueWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s0", String.class), new ObjectMapper());
        IllegalStateException e = assertThrows(IllegalStateException.class, state::value);
        assertTrue(e.getMessage().contains("No current key"));
    }

    @Test
    void updateWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s1", String.class), new ObjectMapper());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> state.update("v"));
        assertTrue(e.getMessage().contains("No current key"));
    }

    @Test
    void clearWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s2", String.class), new ObjectMapper());
        IllegalStateException e = assertThrows(IllegalStateException.class, state::clear);
        assertTrue(e.getMessage().contains("No current key"));
    }

    @Test
    void valueDeserializesTheStoredJson() {
        RMap<String, String> map = map();
        when(map.get(FIELD)).thenReturn("\"v\"");
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s3", String.class), new ObjectMapper());

        assertEquals("v", state.value());
        verify(store).recordKeyedStateRead(eq("s3"), anyLong());
    }

    @Test
    void valueMissReturnsTheDescriptorDefault() {
        RMap<String, String> map = map();
        when(map.get(FIELD)).thenReturn(null);
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s4", String.class, "fallback"), new ObjectMapper());

        assertEquals("fallback", state.value());
    }

    @Test
    void valueWithBrokenJsonWrapsTheFailure() {
        RMap<String, String> map = map();
        when(map.get(FIELD)).thenReturn("not-json");
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<Integer> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s5", Integer.class), new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, state::value);
        assertTrue(e.getMessage().contains("Failed to deserialize state value for s5"));
    }

    @Test
    void updateSerializesAndTouchesTheStateMap() throws Exception {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ObjectMapper objectMapper = new ObjectMapper();
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s6", String.class), objectMapper);

        state.update("v");

        verify(map).put(FIELD, objectMapper.writeValueAsString("v"));
        verify(store).touch(eq("rk"), eq("s6"), eq(map));
        verify(store).recordKeyedStateWrite(eq("s6"), anyLong());
    }

    @Test
    void updateWithNullRemovesTheField() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s7", String.class), new ObjectMapper());

        state.update(null);

        verify(map).remove(FIELD);
        verify(store).touch(eq("rk"), eq("s7"), eq(map));
        verify(store).recordKeyedStateDelete(eq("s7"));
        verify(store).recordKeyedStateWrite(eq("s7"), anyLong());
    }

    @Test
    void updateWithUnserializableValueWrapsTheFailure() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<Object> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s8", Object.class), new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.update(new Object()));
        assertTrue(e.getMessage().contains("Failed to serialize state value for s8"));
    }

    @Test
    void updateKeepsHotKeyFailureUnwrapped() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        // hot-key FAIL_FAST is a policy signal, not a serialization failure: it must reach the
        // MQ retry/DLQ machinery as itself instead of inside a serialization wrapper
        doThrow(new KeyedStateHotKeyException("hot"))
                .when(store).touch(anyString(), anyString(), any());
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s9", String.class), new ObjectMapper());

        KeyedStateHotKeyException e = assertThrows(KeyedStateHotKeyException.class, () -> state.update("v"));
        assertEquals("hot", e.getMessage());
        verify(map).put(eq(FIELD), anyString());
    }

    @Test
    void clearRemovesTheFieldAndRecordsTheDelete() {
        RMap<String, String> map = map();
        RedisKeyedStateStore<String> store = storeWith("user-1", map);
        ValueState<String> state = new RedisKeyedValueState<>(store,
                new StateDescriptor<>("s10", String.class), new ObjectMapper());

        state.clear();

        verify(map).remove(FIELD);
        verify(store).touch(eq("rk"), eq("s10"), eq(map));
        verify(store).recordKeyedStateDelete(eq("s10"));
        verify(store).recordKeyedStateWrite(eq("s10"), anyLong());
    }

}
