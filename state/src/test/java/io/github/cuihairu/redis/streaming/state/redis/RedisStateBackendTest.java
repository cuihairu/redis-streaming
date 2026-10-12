package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RList;
import org.redisson.api.RListAsync;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisStateBackend}: the adapter pins the solo current key around
 * every delegated operation, lands state in the runtime keyed layout under the job
 * namespace ({@code {prefix}:{job}:cg:standalone:topic:standalone:p:0:state:standalone:...}),
 * registers keys in the {@code stateKeys} index and the {@code stateSchema} registry, and
 * stores plain JSON through the StringCodec (no Redisson default-codec type info).
 */
class RedisStateBackendTest {

    private static final String JOB = "job1";
    private static final String STATE_BASE =
            "streaming:runtime:" + JOB + ":cg:standalone:topic:standalone:p:0:state:standalone:";

    @SuppressWarnings("unchecked")
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RSet<String> indexSet = mock(RSet.class);
    @SuppressWarnings("unchecked")
    private final RMap<String, String> schemaMap = mock(RMap.class);
    private final Map<String, RMap<String, String>> dataMaps = new HashMap<>();

    RedisStateBackendTest() {
        when(redisson.getSet(anyString(), any(Codec.class))).thenAnswer(inv -> indexSet);
        when(redisson.getList(anyString(), any(Codec.class))).thenAnswer(inv -> mock(RList.class));
        when(redisson.getMap(anyString(), any(Codec.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.endsWith(":stateSchema")) {
                return schemaMap;
            }
            return dataMaps.computeIfAbsent(key, k -> mock(RMap.class));
        });
    }

    private RMap<String, String> dataMap(String redisKey) {
        return dataMaps.get(redisKey);
    }

    @Test
    void valueStateRoundTripsPlainJsonInTheKeyedHash() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);
        ValueState<Integer> counter = backend.createValueState(new StateDescriptor<>("v", Integer.class));

        assertNull(counter.value());

        counter.update(42);
        String hashKey = STATE_BASE + "v";
        verify(redisson).getMap(hashKey, StringCodec.INSTANCE);
        verify(dataMap(hashKey)).put("s:solo", "42");

        when(dataMap(hashKey).get("s:solo")).thenReturn("42");
        assertEquals(42, counter.value());

        counter.clear();
        verify(dataMap(hashKey)).remove("s:solo");
    }

    @Test
    void valueStateReturnsTheDescriptorDefaultWhenAbsent() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);
        ValueState<Integer> counter = backend.createValueState(new StateDescriptor<>("dv", Integer.class, 7));

        assertEquals(7, counter.value());
    }

    @Test
    void listStateReplacesAtomicallyAndReadsBack() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);
        ListState<String> activityLog = backend.createListState(new StateDescriptor<>("l", String.class));

        String listKey = STATE_BASE + "l:s:solo";
        RBatch batch = mock(RBatch.class);
        @SuppressWarnings("unchecked")
        RListAsync<String> listAsync = mock(RListAsync.class);
        when(redisson.createBatch(any(BatchOptions.class))).thenReturn(batch);
        when(batch.<String>getList(eq(listKey), any(Codec.class))).thenReturn(listAsync);

        activityLog.update(List.of("a", "b"));

        verify(listAsync).deleteAsync();
        verify(listAsync).addAllAsync(List.of("\"a\"", "\"b\""));
        verify(batch).execute();

        @SuppressWarnings("unchecked")
        RList<String> redisList = mock(RList.class);
        when(redisson.<String>getList(eq(listKey), any(Codec.class))).thenReturn(redisList);
        when(redisList.readAll()).thenReturn(List.of("\"a\"", "\"b\""));

        assertEquals(List.of("a", "b"), activityLog.get());

        activityLog.add("c");
        verify(redisList).add("\"c\"");

        activityLog.addAll(List.of("d", "e"));
        verify(listAsync).addAllAsync(List.of("\"d\"", "\"e\""));

        activityLog.clear();
        verify(redisList).clear();
    }

    @Test
    void mapStateRoundTripsJsonEncodedFields() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);
        MapState<String, Integer> preferences = backend.createMapState("m", String.class, Integer.class);

        String hashKey = STATE_BASE + "m:s:solo";
        preferences.put("k", 42);
        verify(dataMap(hashKey)).put("\"k\"", "42");

        when(dataMap(hashKey).get("\"k\"")).thenReturn("42");
        assertEquals(42, preferences.get("k"));

        when(dataMap(hashKey).containsKey("\"k\"")).thenReturn(true);
        assertTrue(preferences.contains("k"));

        when(dataMap(hashKey).readAllMap()).thenReturn(Map.of("\"k\"", "42"));
        Map<String, Integer> seen = new HashMap<>();
        preferences.entries().forEach(e -> seen.put(e.getKey(), e.getValue()));
        assertEquals(Map.of("k", 42), seen);
        assertEquals(List.of("k"), preferences.keys());
        assertEquals(List.of(42), preferences.values());
        assertFalse(preferences.isEmpty());

        preferences.remove("k");
        verify(dataMap(hashKey)).remove("\"k\"");

        preferences.clear();
        verify(dataMap(hashKey)).delete();
    }

    @Test
    void setStateRoundTripsJsonEncodedMembers() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);
        SetState<String> visitors = backend.createSetState(new StateDescriptor<>("s", String.class));

        String setKey = STATE_BASE + "s:s:solo";
        @SuppressWarnings("unchecked")
        RSet<String> setMock = mock(RSet.class);
        when(redisson.<String>getSet(eq(setKey), any(Codec.class))).thenReturn(setMock);

        when(setMock.add("\"u1\"")).thenReturn(true);
        assertTrue(visitors.add("u1"));
        when(setMock.add("\"u1\"")).thenReturn(false);
        assertFalse(visitors.add("u1"));

        when(setMock.contains("\"u1\"")).thenReturn(true);
        assertTrue(visitors.contains("u1"));

        when(setMock.size()).thenReturn(1);
        assertEquals(1, visitors.size());
        when(setMock.isEmpty()).thenReturn(false);
        assertFalse(visitors.isEmpty());

        when(setMock.readAll()).thenReturn(Set.of("\"u1\""));
        Set<String> members = new HashSet<>();
        visitors.get().forEach(members::add);
        assertEquals(Set.of("u1"), members);

        visitors.remove("u1");
        verify(setMock).remove("\"u1\"");

        visitors.clear();
        verify(setMock).delete();
    }

    @Test
    void statesRegisterInTheJobNamespaceForCheckpointing() {
        RedisStateBackend backend = new RedisStateBackend(redisson, JOB);

        backend.createValueState(new StateDescriptor<>("v", Integer.class)).update(1);
        backend.createListState(new StateDescriptor<>("l", Integer.class)).add(2);
        backend.createMapState("m", String.class, Integer.class).put("k", 3);
        backend.createSetState(new StateDescriptor<>("s", Integer.class)).add(4);

        verify(redisson).getSet("streaming:runtime:" + JOB + ":stateKeys", StringCodec.INSTANCE);
        verify(redisson).getMap("streaming:runtime:" + JOB + ":stateSchema", StringCodec.INSTANCE);
        verify(indexSet).add(STATE_BASE + "v");
        verify(indexSet).add(STATE_BASE + "l:s:solo");
        verify(indexSet).add(STATE_BASE + "m:s:solo");
        verify(indexSet).add(STATE_BASE + "s:s:solo");
        verify(schemaMap).put(STATE_BASE + "v", "java.lang.Integer|1");
    }

    @Test
    void accessorsExposeClientAndPrefix() {
        assertEquals("streaming:runtime", new RedisStateBackend(redisson, JOB).getKeyPrefix());
        assertEquals("custom:pfx", new RedisStateBackend(redisson, "j2", "custom:pfx").getKeyPrefix());
        assertSame(redisson, new RedisStateBackend(redisson, JOB).getRedisson());
        verify(redisson).getSet("custom:pfx:j2:stateKeys", StringCodec.INSTANCE);
    }
}
