package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.redisson.api.StreamMessageId.*;

/**
 * Unit tests for RedisDeadLetterAdmin
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterAdminTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private DeadLetterService deadLetterService;

    @Mock
    private RKeys rKeys;

    private RedisDeadLetterAdmin admin;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(redissonClient.getKeys()).thenReturn(rKeys);
        // Reset DlqKeys prefix to default before each test
        DlqKeys.configure("");
    }

    @AfterEach
    void tearDown() {
        // Reset DlqKeys prefix to default after each test
        DlqKeys.configure("");
    }

    // ==================== Constructor Tests ====================

    @Test
    void constructor_withValidArgs_createsAdmin() {
        // When
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);

        // Then
        assertNotNull(admin);
    }

    @Test
    void constructor_withNullRedissonClient_createsAdmin() {
        // When
        admin = new RedisDeadLetterAdmin(null, deadLetterService);

        // Then - should still create but may fail at runtime
        assertNotNull(admin);
    }

    @Test
    void constructor_withNullService_createsAdmin() {
        // When
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // Then - should still create but may fail at runtime
        assertNotNull(admin);
    }

    // ==================== listTopics() Tests ====================

    @Test
    void listTopics_withNoDlqKeys_returnsEmptyList() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(rKeys.getKeys()).thenReturn(List.of("other:key", "stream:topic:test:p:0"));

        // When
        List<String> result = admin.listTopics();

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void listTopics_withValidDlqKeys_returnsTopics() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(rKeys.getKeys()).thenReturn(List.of("stream:topic:test1:dlq", "stream:topic:test2:dlq"));

        // When
        List<String> result = admin.listTopics();

        // Then
        assertEquals(2, result.size());
        assertTrue(result.contains("test1"));
        assertTrue(result.contains("test2"));
    }

    @Test
    void listTopics_whenGetKeysThrows_returnsEmptyList() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(rKeys.getKeys()).thenThrow(new RuntimeException("Redis error"));

        // When
        List<String> result = admin.listTopics();

        // Then
        assertTrue(result.isEmpty());
    }

    // ==================== size() Tests ====================

    // ==================== size() Tests ====================

    @Test
    void size_withValidTopic_delegatesToService() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.size("test-topic")).thenReturn(5L);

        // When
        long result = admin.size("test-topic");

        // Then
        assertEquals(5, result);
        verify(deadLetterService).size("test-topic");
    }

    @Test
    void size_whenServiceThrows_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.size("test-topic")).thenThrow(new RuntimeException("Service error"));

        // When
        long result = admin.size("test-topic");

        // Then
        assertEquals(0, result);
    }

    @Test
    void size_withNullService_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        long result = admin.size("test-topic");

        // Then
        assertEquals(0, result);
    }

    // ==================== list() Tests ====================

    @Test
    void list_withValidTopic_delegatesToServiceAndParsesEntries() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        Map<StreamMessageId, Map<String, Object>> raw = Map.of(
                new StreamMessageId(1, 0), Map.of("originalTopic", "test", "payload", "data1"),
                new StreamMessageId(2, 0), Map.of("originalTopic", "test", "payload", "data2")
        );
        when(deadLetterService.range("test-topic", 10)).thenReturn(raw);

        // When
        List<DeadLetterEntry> result = admin.list("test-topic", 10);

        // Then
        assertEquals(2, result.size());
        verify(deadLetterService).range("test-topic", 10);
    }

    @Test
    void list_withEmptyRange_returnsEmptyList() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.range("test-topic", 10)).thenReturn(Map.of());

        // When
        List<DeadLetterEntry> result = admin.list("test-topic", 10);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void list_whenServiceThrows_returnsEmptyList() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.range("test-topic", 10)).thenThrow(new RuntimeException("Service error"));

        // When
        List<DeadLetterEntry> result = admin.list("test-topic", 10);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void list_withNullService_returnsEmptyList() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        List<DeadLetterEntry> result = admin.list("test-topic", 10);

        // Then
        assertTrue(result.isEmpty());
    }

    // ==================== replay() Tests ====================

    @Test
    void replay_withValidArgs_delegatesToService() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.replay("test-topic", MIN)).thenReturn(true);

        // When
        boolean result = admin.replay("test-topic", MIN);

        // Then
        assertTrue(result);
        verify(deadLetterService).replay("test-topic", MIN);
    }

    @Test
    void replay_whenServiceReturnsFalse_returnsFalse() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.replay("test-topic", MAX)).thenReturn(false);

        // When
        boolean result = admin.replay("test-topic", MAX);

        // Then
        assertFalse(result);
    }

    @Test
    void replay_whenServiceThrows_returnsFalse() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.replay("test-topic", MIN)).thenThrow(new RuntimeException("Service error"));

        // When
        boolean result = admin.replay("test-topic", MIN);

        // Then
        assertFalse(result);
    }

    @Test
    void replay_withNullService_returnsFalse() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        boolean result = admin.replay("test-topic", MIN);

        // Then
        assertFalse(result);
    }

    // ==================== replayAll() Tests ====================

    @Test
    void replayAll_withMultipleEntries_replaysEach() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        Map<StreamMessageId, Map<String, Object>> raw = Map.of(
                new StreamMessageId(1, 0), Map.of("originalTopic", "test", "payload", "data1"),
                new StreamMessageId(2, 0), Map.of("originalTopic", "test", "payload", "data2"),
                new StreamMessageId(3, 0), Map.of("originalTopic", "test", "payload", "data3")
        );
        when(deadLetterService.range("test-topic", 100)).thenReturn(raw);
        when(deadLetterService.replay(eq("test-topic"), any(StreamMessageId.class))).thenReturn(true);

        // When
        long result = admin.replayAll("test-topic", 100);

        // Then
        assertEquals(3, result);
        verify(deadLetterService).range("test-topic", 100);
        verify(deadLetterService, times(3)).replay(eq("test-topic"), any(StreamMessageId.class));
    }

    @Test
    void replayAll_withPartialFailures_countsOnlySuccesses() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        Map<StreamMessageId, Map<String, Object>> raw = Map.of(
                new StreamMessageId(1, 0), Map.of("originalTopic", "test", "payload", "data1"),
                new StreamMessageId(2, 0), Map.of("originalTopic", "test", "payload", "data2"),
                new StreamMessageId(3, 0), Map.of("originalTopic", "test", "payload", "data3")
        );
        when(deadLetterService.range("test-topic", 100)).thenReturn(raw);
        when(deadLetterService.replay(eq("test-topic"), any(StreamMessageId.class)))
                .thenReturn(true)
                .thenReturn(false)  // Second call fails
                .thenReturn(true);

        // When
        long result = admin.replayAll("test-topic", 100);

        // Then
        assertEquals(2, result);
    }

    @Test
    void replayAll_withEmptyRange_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.range("test-topic", 100)).thenReturn(Map.of());

        // When
        long result = admin.replayAll("test-topic", 100);

        // Then
        assertEquals(0, result);
    }

    @Test
    void replayAll_withNegativeMaxCount_usesZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.range("test-topic", 0)).thenReturn(Map.of());

        // When
        long result = admin.replayAll("test-topic", -5);

        // Then
        assertEquals(0, result);
        verify(deadLetterService).range("test-topic", 0);
    }

    @Test
    void replayAll_whenRangeThrows_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.range("test-topic", 100)).thenThrow(new RuntimeException("Service error"));

        // When
        long result = admin.replayAll("test-topic", 100);

        // Then
        assertEquals(0, result);
    }

    @Test
    void replayAll_withNullService_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        long result = admin.replayAll("test-topic", 100);

        // Then
        assertEquals(0, result);
    }

    // ==================== delete() Tests ====================

    @Test
    void delete_withValidArgs_delegatesToService() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.delete("test-topic", MIN)).thenReturn(true);

        // When
        boolean result = admin.delete("test-topic", MIN);

        // Then
        assertTrue(result);
        verify(deadLetterService).delete("test-topic", MIN);
    }

    @Test
    void delete_whenServiceThrows_returnsFalse() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.delete("test-topic", MIN)).thenThrow(new RuntimeException("Service error"));

        // When
        boolean result = admin.delete("test-topic", MIN);

        // Then
        assertFalse(result);
    }

    @Test
    void delete_withNullService_returnsFalse() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        boolean result = admin.delete("test-topic", MIN);

        // Then
        assertFalse(result);
    }

    // ==================== clear() Tests ====================

    @Test
    void clear_withValidTopic_delegatesToService() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.clear("test-topic")).thenReturn(5L);

        // When
        long result = admin.clear("test-topic");

        // Then
        assertEquals(5, result);
        verify(deadLetterService).clear("test-topic");
    }

    @Test
    void clear_whenServiceThrows_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        when(deadLetterService.clear("test-topic")).thenThrow(new RuntimeException("Service error"));

        // When
        long result = admin.clear("test-topic");

        // Then
        assertEquals(0, result);
    }

    @Test
    void clear_withNullService_returnsZero() {
        // Given
        admin = new RedisDeadLetterAdmin(redissonClient, null);

        // When
        long result = admin.clear("test-topic");

        // Then
        assertEquals(0, result);
    }

}
