package io.github.cuihairu.redis.streaming.state.backend;

import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StateBackend interface
 */
class StateBackendTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(StateBackend.class.isInterface());
    }

    @Test
    void testCreateValueStateMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(StateBackend.class.getMethod("createValueState", StateDescriptor.class));
    }

    @Test
    void testCreateMapStateMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(StateBackend.class.getMethod("createMapState", String.class, Class.class, Class.class));
    }

    @Test
    void testCreateListStateMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(StateBackend.class.getMethod("createListState", StateDescriptor.class));
    }

    @Test
    void testCreateSetStateMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(StateBackend.class.getMethod("createSetState", StateDescriptor.class));
    }

    @Test
    void testCloseMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(StateBackend.class.getMethod("close"));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return new ValueState<T>() {
                    private T value;

                    @Override
                    public void update(T value) {
                        this.value = value;
                    }

                    @Override
                    public T value() {
                        return value;
                    }

                    @Override
                    public void clear() {
                        value = null;
                    }
                };
            }

            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return new MapState<K, V>() {
                    private final java.util.Map<K, V> map = new java.util.HashMap<>();

                    @Override
                    public V get(K key) { return map.get(key); }

                    @Override
                    public void put(K key, V value) { map.put(key, value); }

                    @Override
                    public void remove(K key) { map.remove(key); }

                    @Override
                    public boolean contains(K key) { return map.containsKey(key); }

                    @Override
                    public Iterable<java.util.Map.Entry<K, V>> entries() { return map.entrySet(); }

                    @Override
                    public Iterable<K> keys() { return map.keySet(); }

                    @Override
                    public Iterable<V> values() { return map.values(); }

                    @Override
                    public boolean isEmpty() { return map.isEmpty(); }

                    @Override
                    public void clear() { map.clear(); }
                };
            }

            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return new ListState<T>() {
                    private final java.util.List<T> list = new java.util.ArrayList<>();

                    @Override
                    public void add(T value) { list.add(value); }

                    @Override
                    public Iterable<T> get() { return new java.util.ArrayList<>(list); }

                    @Override
                    public void update(Iterable<T> values) {
                        list.clear();
                        values.forEach(list::add);
                    }

                    @Override
                    public void addAll(Iterable<T> values) { values.forEach(list::add); }

                    @Override
                    public void clear() { list.clear(); }
                };
            }

            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return new SetState<T>() {
                    private final java.util.Set<T> set = new java.util.HashSet<>();

                    @Override
                    public boolean add(T value) { return set.add(value); }

                    @Override
                    public boolean remove(T value) { return set.remove(value); }

                    @Override
                    public boolean contains(T value) { return set.contains(value); }

                    @Override
                    public Iterable<T> get() { return new java.util.HashSet<>(set); }

                    @Override
                    public boolean isEmpty() { return set.isEmpty(); }

                    @Override
                    public int size() { return set.size(); }

                    @Override
                    public void clear() { set.clear(); }
                };
            }

            @Override
            public void close() {
                // No-op for test
            }
        };

        // When - create states
        StateDescriptor<String> descriptor = new StateDescriptor<>("test", String.class);
        ValueState<String> valueState = backend.createValueState(descriptor);
        MapState<String, Integer> mapState = backend.createMapState("map", String.class, Integer.class);
        ListState<Integer> listState = backend.createListState(new StateDescriptor<>("list", Integer.class));
        SetState<String> setState = backend.createSetState(descriptor);

        // Then - verify operations
        valueState.update("hello");
        assertEquals("hello", valueState.value());

        mapState.put("key", 42);
        assertEquals(42, mapState.get("key"));

        listState.add(1);
        listState.add(2);
        java.util.List<Integer> listResult = new java.util.ArrayList<>();
        listState.get().forEach(listResult::add);
        assertEquals(2, listResult.size());

        setState.add("value");
        assertTrue(setState.contains("value"));

        // Cleanup
        backend.close();
    }

    @Test
    void testValueStateClear() {
        // Given
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return new ValueState<T>() {
                    private T value;

                    @Override
                    public void update(T value) { this.value = value; }

                    @Override
                    public T value() { return value; }

                    @Override
                    public void clear() { value = null; }
                };
            }

            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return null;
            }

            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public void close() { }
        };

        // When
        ValueState<Integer> state = backend.createValueState(new StateDescriptor<>("counter", Integer.class));
        state.update(10);
        assertEquals(10, state.value());
        state.clear();
        assertNull(state.value());
    }

    @Test
    void testMapStateClear() {
        // Given
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return new MapState<K, V>() {
                    private final java.util.Map<K, V> map = new java.util.HashMap<>();

                    @Override
                    public V get(K key) { return map.get(key); }
                    @Override
                    public void put(K key, V value) { map.put(key, value); }
                    @Override
                    public void remove(K key) { map.remove(key); }
                    @Override
                    public boolean contains(K key) { return map.containsKey(key); }
                    @Override
                    public Iterable<java.util.Map.Entry<K, V>> entries() { return map.entrySet(); }
                    @Override
                    public Iterable<K> keys() { return map.keySet(); }
                    @Override
                    public Iterable<V> values() { return map.values(); }
                    @Override
                    public boolean isEmpty() { return map.isEmpty(); }
                    @Override
                    public void clear() { map.clear(); }
                };
            }

            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public void close() { }
        };

        // When
        MapState<String, String> state = backend.createMapState("test", String.class, String.class);
        state.put("key1", "value1");
        state.put("key2", "value2");
        assertFalse(state.isEmpty());
        state.clear();
        assertTrue(state.isEmpty());
    }

    @Test
    void testListStateClear() {
        // Given
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return null;
            }

            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return new ListState<T>() {
                    private final java.util.List<T> list = new java.util.ArrayList<>();

                    @Override
                    public void add(T value) { list.add(value); }
                    @Override
                    public Iterable<T> get() { return new java.util.ArrayList<>(list); }
                    @Override
                    public void update(Iterable<T> values) {
                        list.clear();
                        values.forEach(list::add);
                    }
                    @Override
                    public void addAll(Iterable<T> values) { values.forEach(list::add); }
                    @Override
                    public void clear() { list.clear(); }
                };
            }

            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public void close() { }
        };

        // When
        ListState<String> state = backend.createListState(new StateDescriptor<>("list", String.class));
        state.add("item1");
        state.add("item2");
        java.util.List<String> result = new java.util.ArrayList<>();
        state.get().forEach(result::add);
        assertEquals(2, result.size());
        state.clear();
        result.clear();
        state.get().forEach(result::add);
        assertEquals(0, result.size());
    }

    @Test
    void testSetStateClear() {
        // Given
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return null;
            }

            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return null;
            }

            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return new SetState<T>() {
                    private final java.util.Set<T> set = new java.util.HashSet<>();

                    @Override
                    public boolean add(T value) { return set.add(value); }
                    @Override
                    public boolean remove(T value) { return set.remove(value); }
                    @Override
                    public boolean contains(T value) { return set.contains(value); }
                    @Override
                    public Iterable<T> get() { return new java.util.HashSet<>(set); }
                    @Override
                    public boolean isEmpty() { return set.isEmpty(); }
                    @Override
                    public int size() { return set.size(); }
                    @Override
                    public void clear() { set.clear(); }
                };
            }

            @Override
            public void close() { }
        };

        // When
        SetState<Integer> state = backend.createSetState(new StateDescriptor<>("set", Integer.class));
        state.add(1);
        state.add(2);
        assertEquals(2, state.size());
        assertFalse(state.isEmpty());
        state.clear();
        assertEquals(0, state.size());
        assertTrue(state.isEmpty());
    }

    @Test
    void testGenericTypes() {
        // Given
        StateBackend backend = new StateBackend() {
            @Override
            public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
                return new ValueState<T>() {
                    private T value;
                    @Override
                    public void update(T value) { this.value = value; }
                    @Override
                    public T value() { return value; }
                    @Override
                    public void clear() { value = null; }
                };
            }
            @Override
            public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
                return new MapState<K, V>() {
                    private final java.util.Map<K, V> map = new java.util.HashMap<>();
                    @Override
                    public V get(K key) { return map.get(key); }
                    @Override
                    public void put(K key, V value) { map.put(key, value); }
                    @Override
                    public void remove(K key) { map.remove(key); }
                    @Override
                    public boolean contains(K key) { return map.containsKey(key); }
                    @Override
                    public Iterable<java.util.Map.Entry<K, V>> entries() { return map.entrySet(); }
                    @Override
                    public Iterable<K> keys() { return map.keySet(); }
                    @Override
                    public Iterable<V> values() { return map.values(); }
                    @Override
                    public boolean isEmpty() { return map.isEmpty(); }
                    @Override
                    public void clear() { map.clear(); }
                };
            }
            @Override
            public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
                return new ListState<T>() {
                    private final java.util.List<T> list = new java.util.ArrayList<>();
                    @Override
                    public void add(T value) { list.add(value); }
                    @Override
                    public Iterable<T> get() { return new java.util.ArrayList<>(list); }
                    @Override
                    public void update(Iterable<T> values) {
                        list.clear();
                        values.forEach(list::add);
                    }
                    @Override
                    public void addAll(Iterable<T> values) { values.forEach(list::add); }
                    @Override
                    public void clear() { list.clear(); }
                };
            }
            @Override
            public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
                return new SetState<T>() {
                    private final java.util.Set<T> set = new java.util.HashSet<>();
                    @Override
                    public boolean add(T value) { return set.add(value); }
                    @Override
                    public boolean remove(T value) { return set.remove(value); }
                    @Override
                    public boolean contains(T value) { return set.contains(value); }
                    @Override
                    public Iterable<T> get() { return new java.util.HashSet<>(set); }
                    @Override
                    public boolean isEmpty() { return set.isEmpty(); }
                    @Override
                    public int size() { return set.size(); }
                    @Override
                    public void clear() { set.clear(); }
                };
            }
            @Override
            public void close() { }
        };

        // When & Then - should support various types
        assertNotNull(backend.createValueState(new StateDescriptor<>("string", String.class)));
        assertNotNull(backend.createValueState(new StateDescriptor<>("int", Integer.class)));
        assertNotNull(backend.createMapState("map1", String.class, Integer.class));
        assertNotNull(backend.createMapState("map2", Integer.class, String.class));
        assertNotNull(backend.createMapState("map3", Long.class, Double.class));
    }
}
