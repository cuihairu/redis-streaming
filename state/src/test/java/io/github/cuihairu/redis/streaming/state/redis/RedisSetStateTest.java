package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSetStateTest {

    @Test
    void delegatesOperationsAndMaterializesSnapshotOnGet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getSet("k")).thenReturn(set);

        when(set.add("a")).thenReturn(true);
        when(set.remove("a")).thenReturn(true);
        when(set.contains("a")).thenReturn(true);
        when(set.isEmpty()).thenReturn(false);
        when(set.size()).thenReturn(2);
        when(set.iterator()).thenReturn(Set.of("a", "b").iterator());

        RedisSetState<String> state = new RedisSetState<>(redisson, "k", String.class);

        assertTrue(state.add("a"));
        assertTrue(state.remove("a"));
        assertTrue(state.contains("a"));
        assertFalse(state.isEmpty());
        assertEquals(2, state.size());

        Iterable<String> snapshot = state.get();
        assertTrue(snapshot instanceof Set);
        assertEquals(Set.of("a", "b"), snapshot);

        state.clear();
        verify(set).clear();
        assertEquals("k", state.getKey());
    }
}
