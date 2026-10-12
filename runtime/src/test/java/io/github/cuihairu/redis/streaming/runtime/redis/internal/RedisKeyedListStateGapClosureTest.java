package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RList;
import org.redisson.api.RListAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for {@link RedisKeyedListState}: the current-key guard, add/get/update/
 * addAll/clear and the serialization failure wraps.
 */
class RedisKeyedListStateGapClosureTest {

    private static final String FIELD = "s:user-1";
    private static final String NAME = "l";

    @SuppressWarnings("unchecked")
    private RList<String> list() {
        return mock(RList.class);
    }

    @SuppressWarnings("unchecked")
    private RedisKeyedStateStore<String> storeWith(String key, RList<String> list) {
        return storeWith(key, list, mock(RedissonClient.class));
    }

    @SuppressWarnings("unchecked")
    private RedisKeyedStateStore<String> storeWith(String key, RList<String> list, RedissonClient client) {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(key);
        when(store.stateFieldForKey(key)).thenReturn(FIELD);
        when(store.stateItemKey(NAME, FIELD)).thenReturn("ik");
        when(store.stateListForKey(NAME, FIELD)).thenReturn(list);
        when(store.redissonClient()).thenReturn(client);
        return store;
    }

    @SuppressWarnings("unchecked")
    private RListAsync<String> listAsync() {
        return mock(RListAsync.class);
    }

    private RedissonClient batchClient(RBatch batch) {
        RedissonClient client = mock(RedissonClient.class);
        when(client.createBatch(any(BatchOptions.class))).thenReturn(batch);
        return client;
    }

    private RedisKeyedListState<String, Integer> state(RedisKeyedStateStore<String> store) {
        return new RedisKeyedListState<>(store, new StateDescriptor<>(NAME, Integer.class),
                new ObjectMapper());
    }

    @Test
    void everyAccessWithoutCurrentKeyThrows() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        when(store.currentKey()).thenReturn(null);
        RedisKeyedListState<String, Integer> state = state(store);

        assertThrows(IllegalStateException.class, () -> state.add(1));
        assertThrows(IllegalStateException.class, state::get);
        assertThrows(IllegalStateException.class, () -> state.update(List.of(1)));
        assertThrows(IllegalStateException.class, () -> state.addAll(List.of(1)));
        assertThrows(IllegalStateException.class, state::clear);
    }

    @Test
    void addSerializesAndRecordsTheWrite() {
        RList<String> list = list();
        RedisKeyedStateStore<String> store = storeWith("user-1", list);
        RedisKeyedListState<String, Integer> state = state(store);

        state.add(42);

        verify(list).add("42");
        verify(store).recordKeyedStateWrite(eq(NAME), anyLong());
    }

    @Test
    void getDeserializesEveryElement() {
        RList<String> list = list();
        when(list.readAll()).thenReturn(List.of("1", "2"));
        RedisKeyedListState<String, Integer> state = state(storeWith("user-1", list));

        assertEquals(List.of(1, 2), state.get());
    }

    @Test
    void brokenStoredElementWrapsTheFailure() {
        RList<String> list = list();
        when(list.readAll()).thenReturn(List.of("not-json"));
        RedisKeyedListState<String, Integer> state = state(storeWith("user-1", list));

        RuntimeException e = assertThrows(RuntimeException.class, state::get);
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("Failed to deserialize state value for " + NAME));
    }

    @Test
    void addWithUnserializableElementWrapsTheFailure() {
        RList<String> list = list();
        RedisKeyedListState<String, Object> state = new RedisKeyedListState<>(
                storeWith("user-1", list), new StateDescriptor<>(NAME, Object.class), new ObjectMapper());

        RuntimeException e = assertThrows(RuntimeException.class, () -> state.add(new Object()));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("Failed to serialize state value for " + NAME));
    }

    @Test
    void updateReplacesTheWholeListAtomically() {
        RBatch batch = mock(RBatch.class);
        RListAsync<String> listAsync = listAsync();
        when(batch.<String>getList(eq("ik"), any(Codec.class))).thenReturn(listAsync);
        RedisKeyedStateStore<String> store = storeWith("user-1", list(), batchClient(batch));
        RedisKeyedListState<String, Integer> state = state(store);

        state.update(List.of(1, 2));

        verify(listAsync).deleteAsync();
        verify(listAsync).addAllAsync(List.of("1", "2"));
        verify(batch).execute();
        verify(store).registerStateKey("ik");
    }

    @Test
    void updateWithNullClearsTheListAtomically() {
        RBatch batch = mock(RBatch.class);
        RListAsync<String> listAsync = listAsync();
        when(batch.<String>getList(eq("ik"), any(Codec.class))).thenReturn(listAsync);
        RedisKeyedListState<String, Integer> state = state(storeWith("user-1", list(), batchClient(batch)));

        state.update(null);

        verify(listAsync).deleteAsync();
        verify(listAsync, never()).addAllAsync(any());
        verify(batch).execute();
    }

    @Test
    void addAllAppendsAtomically() {
        RBatch batch = mock(RBatch.class);
        RListAsync<String> listAsync = listAsync();
        when(batch.<String>getList(eq("ik"), any(Codec.class))).thenReturn(listAsync);
        RedisKeyedListState<String, Integer> state = state(storeWith("user-1", list(), batchClient(batch)));

        state.addAll(List.of(7, 8));

        verify(listAsync, never()).deleteAsync();
        verify(listAsync).addAllAsync(List.of("7", "8"));
        verify(batch).execute();
    }

    @Test
    void nullAddAllIsANoOp() {
        RedisKeyedStateStore<String> store = mock(RedisKeyedStateStore.class);
        RedisKeyedListState<String, Integer> state = state(store);

        state.addAll(null);

        verifyNoInteractions(store);
    }

    @Test
    void clearClearsTheList() {
        RList<String> list = list();
        RedisKeyedListState<String, Integer> state = state(storeWith("user-1", list));

        state.clear();

        verify(list).clear();
    }
}
