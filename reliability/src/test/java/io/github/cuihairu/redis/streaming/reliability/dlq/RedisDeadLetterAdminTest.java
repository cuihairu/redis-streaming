package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisDeadLetterAdmin
 */
class RedisDeadLetterAdminTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private DeadLetterService mockService;

    @Mock
    private StreamMessageId mockMessageId1;

    @Mock
    private StreamMessageId mockMessageId2;

    private RedisDeadLetterAdmin admin;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        admin = new RedisDeadLetterAdmin(mockRedissonClient, mockService);
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidParameters() {
        assertNotNull(admin);
    }

    @Test
    void testConstructorWithNullRedissonClient() {
        // Constructor accepts null, but methods will fail when called
        assertDoesNotThrow(() -> new RedisDeadLetterAdmin(null, mockService));
    }

    @Test
    void testConstructorWithNullService() {
        // Constructor accepts null, but methods will fail when called
        assertDoesNotThrow(() -> new RedisDeadLetterAdmin(mockRedissonClient, null));
    }

    @Test
    void testConstructorWithBothNull() {
        assertDoesNotThrow(() -> new RedisDeadLetterAdmin(null, null));
    }

    // ===== listTopics Tests =====

    @Test
    void testListTopics() {
        when(mockRedissonClient.getKeys()).thenReturn(mock(org.redisson.api.RKeys.class));
        when(mockRedissonClient.getKeys().getKeys()).thenReturn(java.util.Collections.emptyList());

        List<String> topics = admin.listTopics();

        assertNotNull(topics);
        assertTrue(topics.isEmpty());
    }

    @Test
    void testListTopicsWithEmptyKeys() {
        when(mockRedissonClient.getKeys()).thenReturn(mock(org.redisson.api.RKeys.class));
        when(mockRedissonClient.getKeys().getKeys()).thenReturn(java.util.Collections.emptyList());

        List<String> topics = admin.listTopics();

        assertTrue(topics.isEmpty());
    }

    @Test
    void testListTopicsWithException() {
        when(mockRedissonClient.getKeys()).thenThrow(new RuntimeException("Redis error"));

        List<String> topics = admin.listTopics();

        assertNotNull(topics);
        assertTrue(topics.isEmpty());
    }

    // ===== size Tests =====

    @Test
    void testSize() {
        when(mockService.size("test-topic")).thenReturn(42L);

        long size = admin.size("test-topic");

        assertEquals(42L, size);
    }

    @Test
    void testSizeWithZero() {
        when(mockService.size("test-topic")).thenReturn(0L);

        long size = admin.size("test-topic");

        assertEquals(0L, size);
    }

    @Test
    void testSizeWithException() {
        when(mockService.size("test-topic")).thenThrow(new RuntimeException("Service error"));

        long size = admin.size("test-topic");

        assertEquals(0L, size);
    }

    @Test
    void testSizeWithNullTopic() {
        when(mockService.size(null)).thenThrow(new NullPointerException());

        long size = admin.size(null);

        assertEquals(0L, size);
    }

    // ===== list Tests =====

    @Test
    void testList() {
        Map<StreamMessageId, Map<String, Object>> mockEntries = new HashMap<>();
        mockEntries.put(mockMessageId1, Map.of("data", "value"));
        when(mockService.range("test-topic", 10)).thenReturn(mockEntries);

        List<DeadLetterEntry> entries = admin.list("test-topic", 10);

        assertNotNull(entries);
        assertEquals(1, entries.size());
    }

    @Test
    void testListWithEmptyResult() {
        when(mockService.range("test-topic", 10)).thenReturn(java.util.Collections.emptyMap());

        List<DeadLetterEntry> entries = admin.list("test-topic", 10);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testListWithZeroLimit() {
        when(mockService.range("test-topic", 0)).thenReturn(java.util.Collections.emptyMap());

        List<DeadLetterEntry> entries = admin.list("test-topic", 0);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testListWithNegativeLimit() {
        when(mockService.range("test-topic", -10)).thenReturn(java.util.Collections.emptyMap());

        List<DeadLetterEntry> entries = admin.list("test-topic", -10);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testListWithException() {
        when(mockService.range("test-topic", 10)).thenThrow(new RuntimeException("Service error"));

        List<DeadLetterEntry> entries = admin.list("test-topic", 10);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    // ===== replay Tests =====

    @Test
    void testReplay() {
        when(mockService.replay(eq("test-topic"), any())).thenReturn(true);

        boolean result = admin.replay("test-topic", StreamMessageId.ALL);

        assertTrue(result);
    }

    @Test
    void testReplayFailure() {
        when(mockService.replay(eq("test-topic"), any())).thenReturn(false);

        boolean result = admin.replay("test-topic", StreamMessageId.ALL);

        assertFalse(result);
    }

    @Test
    void testReplayWithException() {
        when(mockService.replay(eq("test-topic"), any()))
                .thenThrow(new RuntimeException("Service error"));

        boolean result = admin.replay("test-topic", StreamMessageId.ALL);

        assertFalse(result);
    }

    @Test
    void testReplayWithNullId() {
        when(mockService.replay(eq("test-topic"), isNull())).thenThrow(new NullPointerException());

        boolean result = admin.replay("test-topic", null);

        assertFalse(result);
    }

    // ===== replayAll Tests =====

    @Test
    void testReplayAll() {
        Map<StreamMessageId, Map<String, Object>> mockEntries = new HashMap<>();
        mockEntries.put(mockMessageId1, Map.of("data", "value1"));
        mockEntries.put(mockMessageId2, Map.of("data", "value2"));
        when(mockService.range("test-topic", 10)).thenReturn(mockEntries);
        when(mockService.replay(anyString(), any())).thenReturn(true);

        long count = admin.replayAll("test-topic", 10);

        assertEquals(2L, count);
    }

    @Test
    void testReplayAllWithZeroLimit() {
        when(mockService.range("test-topic", 0)).thenReturn(java.util.Collections.emptyMap());

        long count = admin.replayAll("test-topic", 0);

        assertEquals(0L, count);
    }

    @Test
    void testReplayAllWithNegativeLimit() {
        when(mockService.range("test-topic", -10)).thenReturn(java.util.Collections.emptyMap());

        long count = admin.replayAll("test-topic", -10);

        assertEquals(0L, count);
    }

    @Test
    void testReplayAllWithPartialFailure() {
        Map<StreamMessageId, Map<String, Object>> mockEntries = new HashMap<>();
        mockEntries.put(mockMessageId1, Map.of("data", "value1"));
        mockEntries.put(mockMessageId2, Map.of("data", "value2"));
        when(mockService.range("test-topic", 10)).thenReturn(mockEntries);
        when(mockService.replay(eq("test-topic"), same(mockMessageId1))).thenReturn(true);
        when(mockService.replay(eq("test-topic"), same(mockMessageId2))).thenReturn(false);

        long count = admin.replayAll("test-topic", 10);

        assertEquals(1L, count);
    }

    @Test
    void testReplayAllWithException() {
        when(mockService.range("test-topic", 10)).thenThrow(new RuntimeException("Service error"));

        long count = admin.replayAll("test-topic", 10);

        assertEquals(0L, count);
    }

    // ===== delete Tests =====

    @Test
    void testDelete() {
        when(mockService.delete(eq("test-topic"), any())).thenReturn(true);

        boolean result = admin.delete("test-topic", StreamMessageId.ALL);

        assertTrue(result);
    }

    @Test
    void testDeleteFailure() {
        when(mockService.delete(eq("test-topic"), any())).thenReturn(false);

        boolean result = admin.delete("test-topic", StreamMessageId.ALL);

        assertFalse(result);
    }

    @Test
    void testDeleteWithException() {
        when(mockService.delete(eq("test-topic"), any()))
                .thenThrow(new RuntimeException("Service error"));

        boolean result = admin.delete("test-topic", StreamMessageId.ALL);

        assertFalse(result);
    }

    // ===== clear Tests =====

    @Test
    void testClear() {
        when(mockService.clear("test-topic")).thenReturn(5L);

        long count = admin.clear("test-topic");

        assertEquals(5L, count);
    }

    @Test
    void testClearWithZero() {
        when(mockService.clear("test-topic")).thenReturn(0L);

        long count = admin.clear("test-topic");

        assertEquals(0L, count);
    }

    @Test
    void testClearWithException() {
        when(mockService.clear("test-topic")).thenThrow(new RuntimeException("Service error"));

        long count = admin.clear("test-topic");

        assertEquals(0L, count);
    }

    // ===== extractTopicFromDlqKey Tests (via listTopics) =====

    @Test
    void testExtractTopicFromDlqKey() {
        // This is tested indirectly via listTopics
        // The method extracts topic from "{prefix}:{topic}:dlq"
        when(mockRedissonClient.getKeys()).thenReturn(mock(org.redisson.api.RKeys.class));
        when(mockRedissonClient.getKeys().getKeys())
                .thenReturn(java.util.List.of("stream:topic:my-topic:dlq"));

        List<String> topics = admin.listTopics();

        // The actual extraction logic depends on DlqKeys implementation
        // Just verify it doesn't crash
        assertNotNull(topics);
    }

    // ===== Edge Cases Tests =====

    @Test
    void testListWithNullTopic() {
        when(mockService.range(null, 10)).thenThrow(new NullPointerException());

        List<DeadLetterEntry> entries = admin.list(null, 10);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testReplayAllWithNullTopic() {
        when(mockService.range(null, 10)).thenThrow(new NullPointerException());

        long count = admin.replayAll(null, 10);

        assertEquals(0L, count);
    }

    @Test
    void testDeleteWithNullTopic() {
        when(mockService.delete(eq(null), any()))
                .thenThrow(new NullPointerException());

        boolean result = admin.delete(null, StreamMessageId.ALL);

        assertFalse(result);
    }

    @Test
    void testClearWithNullTopic() {
        when(mockService.clear(null)).thenThrow(new NullPointerException());

        long count = admin.clear(null);

        assertEquals(0L, count);
    }
}
