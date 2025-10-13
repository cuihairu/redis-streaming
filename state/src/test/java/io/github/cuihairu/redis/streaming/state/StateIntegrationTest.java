package io.github.cuihairu.redis.streaming.state;

import io.github.cuihairu.redis.streaming.api.state.*;
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for State module
 */
@Slf4j
@Tag("integration")
public class StateIntegrationTest {

    private RedissonClient redisson;
    private RedisStateBackend stateBackend;

    @BeforeEach
    public void setUp() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        redisson = Redisson.create(config);
        stateBackend = new RedisStateBackend(redisson, "test:state:");
    }

    @AfterEach
    public void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    public void testValueState() {
        log.info("Testing ValueState");

        StateDescriptor<String> descriptor = new StateDescriptor<>("test-value", String.class);
        ValueState<String> state = stateBackend.createValueState(descriptor);

        // Initially null
        assertNull(state.value());

        // Update value
        state.update("hello");
        assertEquals("hello", state.value());

        // Update again
        state.update("world");
        assertEquals("world", state.value());

        // Clear
        state.clear();
        assertNull(state.value());

        log.info("ValueState test passed");
    }

    @Test
    public void testMapState() {
        log.info("Testing MapState");

        MapState<String, Integer> state = stateBackend.createMapState("test-map", String.class, Integer.class);

        // Initially empty
        assertTrue(state.isEmpty());

        // Put values
        state.put("a", 1);
        state.put("b", 2);
        state.put("c", 3);

        // Get values
        assertEquals(1, state.get("a"));
        assertEquals(2, state.get("b"));
        assertEquals(3, state.get("c"));

        // Contains
        assertTrue(state.contains("a"));
        assertFalse(state.contains("d"));

        // Keys and values
        Set<String> keys = new HashSet<>();
        state.keys().forEach(keys::add);
        assertEquals(Set.of("a", "b", "c"), keys);

        // Remove
        state.remove("b");
        assertFalse(state.contains("b"));

        // Clear
        state.clear();
        assertTrue(state.isEmpty());

        log.info("MapState test passed");
    }

    @Test
    public void testListState() {
        log.info("Testing ListState");

        StateDescriptor<String> descriptor = new StateDescriptor<>("test-list", String.class);
        ListState<String> state = stateBackend.createListState(descriptor);

        // Add elements
        state.add("a");
        state.add("b");
        state.add("c");

        // Get elements
        Set<String> elements = new HashSet<>();
        state.get().forEach(elements::add);
        assertEquals(Set.of("a", "b", "c"), elements);

        // Update
        state.update(Arrays.asList("x", "y", "z"));
        elements.clear();
        state.get().forEach(elements::add);
        assertEquals(Set.of("x", "y", "z"), elements);

        // Add all
        state.addAll(Arrays.asList("p", "q"));
        elements.clear();
        state.get().forEach(elements::add);
        assertTrue(elements.contains("x"));
        assertTrue(elements.contains("p"));

        // Clear
        state.clear();
        elements.clear();
        state.get().forEach(elements::add);
        assertTrue(elements.isEmpty());

        log.info("ListState test passed");
    }

    @Test
    public void testSetState() {
        log.info("Testing SetState");

        StateDescriptor<String> descriptor = new StateDescriptor<>("test-set", String.class);
        SetState<String> state = stateBackend.createSetState(descriptor);

        // Initially empty
        assertTrue(state.isEmpty());
        assertEquals(0, state.size());

        // Add elements
        assertTrue(state.add("a"));
        assertTrue(state.add("b"));
        assertTrue(state.add("c"));
        assertFalse(state.add("a")); // Duplicate

        // Size
        assertEquals(3, state.size());

        // Contains
        assertTrue(state.contains("a"));
        assertFalse(state.contains("d"));

        // Get elements
        Set<String> elements = new HashSet<>();
        state.get().forEach(elements::add);
        assertEquals(Set.of("a", "b", "c"), elements);

        // Remove
        assertTrue(state.remove("b"));
        assertFalse(state.remove("d"));
        assertEquals(2, state.size());

        // Clear
        state.clear();
        assertTrue(state.isEmpty());

        log.info("SetState test passed");
    }

    @Test
    public void testMultipleStates() {
        log.info("Testing multiple states");

        // Create multiple states with different keys
        ValueState<Integer> counter1 = stateBackend.createValueState(new StateDescriptor<>("counter1", Integer.class));
        ValueState<Integer> counter2 = stateBackend.createValueState(new StateDescriptor<>("counter2", Integer.class));

        counter1.update(10);
        counter2.update(20);

        assertEquals(10, counter1.value());
        assertEquals(20, counter2.value());

        // They should be independent
        counter1.clear();
        assertNull(counter1.value());
        assertEquals(20, counter2.value());

        log.info("Multiple states test passed");
    }
}
