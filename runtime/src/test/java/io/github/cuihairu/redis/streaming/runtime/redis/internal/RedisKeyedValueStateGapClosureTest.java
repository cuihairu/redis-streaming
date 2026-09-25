package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guard branches of {@link RedisKeyedValueState}: {@code update}/{@code clear} without a current
 * key throw {@link IllegalStateException} instead of corrupting keyed state.
 */
class RedisKeyedValueStateGapClosureTest {

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
}
