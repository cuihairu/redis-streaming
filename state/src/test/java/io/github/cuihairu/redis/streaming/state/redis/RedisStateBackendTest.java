package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RedisStateBackendTest {

    @Test
    void prefixesKeysForCreatedStates() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStateBackend backend = new RedisStateBackend(redisson, "p:");

        ValueState<String> valueState = backend.createValueState(new StateDescriptor<>("v", String.class));
        assertEquals("p:v", ((RedisValueState<String>) valueState).getKey());

        ListState<Integer> listState = backend.createListState(new StateDescriptor<>("l", Integer.class));
        assertEquals("p:l", ((RedisListState<Integer>) listState).getKey());

        SetState<Long> setState = backend.createSetState(new StateDescriptor<>("s", Long.class));
        assertEquals("p:s", ((RedisSetState<Long>) setState).getKey());

        MapState<String, Integer> mapState = backend.createMapState("m", String.class, Integer.class);
        assertEquals("p:m", ((RedisMapState<String, Integer>) mapState).getKey());

        assertEquals(redisson, backend.getRedisson());
        assertEquals("p:", backend.getKeyPrefix());
    }
}

