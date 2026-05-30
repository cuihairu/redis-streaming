package io.github.cuihairu.redis.streaming.mq.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "deprecation"})
class TopicRegistryTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RSet<Object> mockRSet;

    private TopicRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new TopicRegistry(mockRedissonClient);
    }

    @Test
    void registerTopicUsesConfiguredPrefixAndReturnsAdded() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("topicA")).thenReturn(true);

        TopicRegistry testRegistry = new TopicRegistry(mockRedissonClient, "mqtest");
        assertTrue(testRegistry.registerTopic("topicA"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockRedissonClient).getSet(keyCaptor.capture(), eq(StringCodec.INSTANCE));
        assertEquals("mqtest:topics:registry", keyCaptor.getValue());
    }

    @Test
    void registerTopicWithNewTopic_returnsTrue() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("new-topic")).thenReturn(true);

        boolean result = registry.registerTopic("new-topic");

        assertTrue(result);
        verify(mockRSet).add("new-topic");
    }

    @Test
    void registerTopicWithExistingTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("existing-topic")).thenReturn(false);

        boolean result = registry.registerTopic("existing-topic");

        assertFalse(result);
        verify(mockRSet).add("existing-topic");
    }

    @Test
    void registerTopicReturnsFalseOnError() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        boolean result = registry.registerTopic("topicA");

        assertFalse(result);
    }

    @Test
    void constructorFallsBackToDefaultPrefixWhenBlank() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("t")).thenReturn(true);

        TopicRegistry testRegistry = new TopicRegistry(mockRedissonClient, "  ");
        assertTrue(testRegistry.registerTopic("t"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockRedissonClient).getSet(keyCaptor.capture(), eq(StringCodec.INSTANCE));
        assertEquals(TopicRegistry.DEFAULT_PREFIX + ":topics:registry", keyCaptor.getValue());
    }

    @Test
    void constructorFallsBackToDefaultPrefixWhenNull() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("t")).thenReturn(true);

        TopicRegistry testRegistry = new TopicRegistry(mockRedissonClient, null);
        assertTrue(testRegistry.registerTopic("t"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockRedissonClient).getSet(keyCaptor.capture(), eq(StringCodec.INSTANCE));
        assertEquals(TopicRegistry.DEFAULT_PREFIX + ":topics:registry", keyCaptor.getValue());
    }

    @Test
    void unregisterTopicWithExistingTopic_returnsTrue() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.remove("topicA")).thenReturn(true);

        boolean result = registry.unregisterTopic("topicA");

        assertTrue(result);
        verify(mockRSet).remove("topicA");
    }

    @Test
    void unregisterTopicWithNonExistentTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.remove("non-existent")).thenReturn(false);

        boolean result = registry.unregisterTopic("non-existent");

        assertFalse(result);
        verify(mockRSet).remove("non-existent");
    }

    @Test
    void unregisterTopicReturnsFalseOnError() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        boolean result = registry.unregisterTopic("topicA");

        assertFalse(result);
    }

    @Test
    void isTopicRegisteredWithExistingTopic_returnsTrue() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.contains("existing-topic")).thenReturn(true);

        boolean result = registry.isTopicRegistered("existing-topic");

        assertTrue(result);
        verify(mockRSet).contains("existing-topic");
    }

    @Test
    void isTopicRegisteredWithNonExistentTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.contains("non-existent")).thenReturn(false);

        boolean result = registry.isTopicRegistered("non-existent");

        assertFalse(result);
        verify(mockRSet).contains("non-existent");
    }

    @Test
    void isTopicRegisteredReturnsFalseOnError() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        boolean result = registry.isTopicRegistered("topicA");

        assertFalse(result);
    }

    @Test
    void getAllTopicsReturnsTopics() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        Set<Object> topics = new HashSet<>();
        topics.add("topic1");
        topics.add("topic2");
        topics.add("topic3");
        when(mockRSet.readAll()).thenReturn(topics);

        Set<String> result = registry.getAllTopics();

        assertEquals(3, result.size());
        assertTrue(result.contains("topic1"));
        assertTrue(result.contains("topic2"));
        assertTrue(result.contains("topic3"));
        verify(mockRSet).readAll();
    }

    @Test
    void getAllTopicsReturnsEmptySetWhenNoTopics() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.readAll()).thenReturn(Set.of());

        Set<String> result = registry.getAllTopics();

        assertTrue(result.isEmpty());
        verify(mockRSet).readAll();
    }

    @Test
    void getAllTopicsReturnsEmptySetOnError() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        Set<String> result = registry.getAllTopics();

        assertEquals(Set.of(), result);
    }

    @Test
    void getTopicCountReturnsCount() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.size()).thenReturn(5);

        int count = registry.getTopicCount();

        assertEquals(5, count);
        verify(mockRSet).size();
    }

    @Test
    void getTopicCountReturnsZeroWhenNoTopics() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.size()).thenReturn(0);

        int count = registry.getTopicCount();

        assertEquals(0, count);
        verify(mockRSet).size();
    }

    @Test
    void getTopicCountReturnsZeroOnError() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        int count = registry.getTopicCount();

        assertEquals(0, count);
    }

    @Test
    void clearRegistryDeletesUnderlyingSet() {
        when(mockRedissonClient.getSet(anyString())).thenReturn(mockRSet);

        registry.clearRegistry();

        verify(mockRSet).delete();
    }

    @Test
    void clearRegistryHandlesError() {
        when(mockRedissonClient.getSet(anyString())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> registry.clearRegistry());
    }

    @Test
    void clearRegistryWithCustomPrefix() {
        when(mockRedissonClient.getSet(anyString())).thenReturn(mockRSet);

        TopicRegistry testRegistry = new TopicRegistry(mockRedissonClient, "custom");
        testRegistry.clearRegistry();

        verify(mockRSet).delete();
    }

    @Test
    void registerTopicWithNullTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);

        boolean result = registry.registerTopic(null);

        assertFalse(result);
    }

    @Test
    void unregisterTopicWithNullTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);

        boolean result = registry.unregisterTopic(null);

        assertFalse(result);
    }

    @Test
    void isTopicRegisteredWithNullTopic_returnsFalse() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);

        boolean result = registry.isTopicRegistered(null);

        assertFalse(result);
    }

    @Test
    void registerTopicWithEmptyTopic_returnsTrue() {
        when(mockRedissonClient.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRSet);
        when(mockRSet.add("")).thenReturn(true);

        boolean result = registry.registerTopic("");

        assertTrue(result);
    }

    @Test
    void constructorWithDefaultPrefix() {
        assertNotNull(registry);
    }

    @Test
    void constructorWithCustomPrefix() {
        TopicRegistry customRegistry = new TopicRegistry(mockRedissonClient, "custom:prefix");

        assertNotNull(customRegistry);
    }
}
