package io.github.cuihairu.redis.streaming.mq.partition;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TopicPartitionRegistry
 */
@SuppressWarnings({"unchecked", "deprecation"})
class TopicPartitionRegistryTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RMap<Object, Object> mockMetaMap;

    @Mock
    private RSet<Object> mockTopicSet;

    @Mock
    private RSet<Object> mockPartitionSet;

    private TopicPartitionRegistry registry;

    @Test
    void constructor_withValidRedissonClient_createsRegistry() {
        MockitoAnnotations.openMocks(this);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertNotNull(registry);
    }

    @Test
    void constructor_withNullRedissonClient_createsRegistry() {
        registry = new TopicPartitionRegistry(null);

        assertNotNull(registry);
    }

    @Test
    void ensureTopic_withValidParameters_initializesTopic() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockTopicSet, mockPartitionSet);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.containsKey(anyString())).thenReturn(false);

        registry = new TopicPartitionRegistry(mockRedissonClient);
        registry.ensureTopic("test-topic", 5);

        verify(mockTopicSet).add("test-topic");
        verify(mockMetaMap).put("partitionCount", "5");
        verify(mockPartitionSet, times(5)).add(anyString());
    }

    @Test
    void ensureTopic_whenMetaAlreadyExists_skipsInitialization() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockTopicSet);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.containsKey(anyString())).thenReturn(true);

        registry = new TopicPartitionRegistry(mockRedissonClient);
        registry.ensureTopic("test-topic", 5);

        verify(mockTopicSet).add("test-topic");
        verify(mockMetaMap, never()).put(anyString(), anyString());
    }

    @Test
    void ensureTopic_withZeroPartitionCount_usesOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockTopicSet);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.containsKey(anyString())).thenReturn(false);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockPartitionSet);

        registry = new TopicPartitionRegistry(mockRedissonClient);
        registry.ensureTopic("test-topic", 0);

        verify(mockMetaMap).put("partitionCount", "1");
    }

    @Test
    void ensureTopic_withNegativePartitionCount_usesOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockTopicSet);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.containsKey(anyString())).thenReturn(false);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockPartitionSet);

        registry = new TopicPartitionRegistry(mockRedissonClient);
        registry.ensureTopic("test-topic", -5);

        verify(mockMetaMap).put("partitionCount", "1");
    }

    @Test
    void ensureTopic_whenExceptionThrown_continuesSuccessfully() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenThrow(new RuntimeException("Redis error"));

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertDoesNotThrow(() -> registry.ensureTopic("test-topic", 5));
    }

    @Test
    void getPartitionCount_withExistingMeta_returnsCount() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("5");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(5, registry.getPartitionCount("test-topic"));
    }

    @Test
    void getPartitionCount_withNullMeta_returnsOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn(null);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(1, registry.getPartitionCount("test-topic"));
    }

    @Test
    void getPartitionCount_withZeroCount_returnsOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("0");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(1, registry.getPartitionCount("test-topic"));
    }

    @Test
    void getPartitionCount_withNegativeCount_returnsOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("-1");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(1, registry.getPartitionCount("test-topic"));
    }

    @Test
    void getPartitionCount_withInvalidValue_returnsOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("invalid");
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenThrow(new NumberFormatException());

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(1, registry.getPartitionCount("test-topic"));
    }

    @Test
    void getPartitionCount_whenExceptionThrown_returnsOne() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenThrow(new RuntimeException("Redis error"));

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertEquals(1, registry.getPartitionCount("test-topic"));
    }

    @Test
    void listPartitionStreams_withMultiplePartitions_returnsAllKeys() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("3");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        List<String> streams = registry.listPartitionStreams("test-topic");

        assertEquals(3, streams.size());
        assertTrue(streams.get(0).contains(":p:0"));
        assertTrue(streams.get(1).contains(":p:1"));
        assertTrue(streams.get(2).contains(":p:2"));
    }

    @Test
    void listPartitionStreams_withDefaultPartition_returnsSingleKey() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn(null);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        List<String> streams = registry.listPartitionStreams("test-topic");

        assertEquals(1, streams.size());
        assertTrue(streams.get(0).contains(":p:0"));
    }

    @Test
    void updatePartitionCount_withIncrease_returnsTrue() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("3");
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockPartitionSet);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertTrue(registry.updatePartitionCount("test-topic", 5));
        verify(mockMetaMap).put("partitionCount", "5");
    }

    @Test
    void updatePartitionCount_withDecrease_returnsFalse() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("5");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertFalse(registry.updatePartitionCount("test-topic", 3));
        verify(mockMetaMap, never()).put(anyString(), anyString());
    }

    @Test
    void updatePartitionCount_withSameValue_returnsFalse() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("5");

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertFalse(registry.updatePartitionCount("test-topic", 5));
    }

    @Test
    void updatePartitionCount_withNullMeta_initializesAndReturnsTrue() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn(null);
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockPartitionSet);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertTrue(registry.updatePartitionCount("test-topic", 5));
    }

    @Test
    void updatePartitionCount_withZeroCount_returnsFalse() {
        MockitoAnnotations.openMocks(this);
        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertFalse(registry.updatePartitionCount("test-topic", 0));
    }

    @Test
    void updatePartitionCount_withNegativeCount_returnsFalse() {
        MockitoAnnotations.openMocks(this);
        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertFalse(registry.updatePartitionCount("test-topic", -1));
    }

    @Test
    void updatePartitionCount_whenExceptionThrown_returnsFalse() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenThrow(new RuntimeException("Redis error"));

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertFalse(registry.updatePartitionCount("test-topic", 5));
    }

    @Test
    void updatePartitionCount_withInvalidMetaValue_usesDefault() {
        MockitoAnnotations.openMocks(this);
        lenient().when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockMetaMap);
        lenient().when(mockMetaMap.get(anyString())).thenReturn("invalid");
        lenient().when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockPartitionSet);

        registry = new TopicPartitionRegistry(mockRedissonClient);

        assertTrue(registry.updatePartitionCount("test-topic", 3));
    }
}
