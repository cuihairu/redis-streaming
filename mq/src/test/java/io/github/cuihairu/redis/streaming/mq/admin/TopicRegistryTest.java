package io.github.cuihairu.redis.streaming.mq.admin;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicRegistryTest {

    @Test
    void registerTopicUsesConfiguredPrefixAndReturnsAdded() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(set);
        when(set.add("topicA")).thenReturn(true);

        TopicRegistry registry = new TopicRegistry(redisson, "mqtest");
        assertTrue(registry.registerTopic("topicA"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisson).getSet(keyCaptor.capture(), eq(StringCodec.INSTANCE));
        assertEquals("mqtest:topics:registry", keyCaptor.getValue());
    }

    @Test
    void constructorFallsBackToDefaultPrefixWhenBlank() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet(anyString(), eq(StringCodec.INSTANCE))).thenReturn(set);
        when(set.add("t")).thenReturn(true);

        TopicRegistry registry = new TopicRegistry(redisson, "  ");
        assertTrue(registry.registerTopic("t"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisson).getSet(keyCaptor.capture(), eq(StringCodec.INSTANCE));
        assertEquals(TopicRegistry.DEFAULT_PREFIX + ":topics:registry", keyCaptor.getValue());
    }

    @Test
    void unregisterTopicReturnsFalseOnError() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        TopicRegistry registry = new TopicRegistry(redisson);
        assertFalse(registry.unregisterTopic("topicA"));
    }

    @Test
    void getAllTopicsReturnsEmptySetOnError() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getSet(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("boom"));

        TopicRegistry registry = new TopicRegistry(redisson);
        assertEquals(Set.of(), registry.getAllTopics());
    }

    @Test
    void clearRegistryDeletesUnderlyingSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RSet set = mock(RSet.class);
        when(redisson.getSet(anyString())).thenReturn(set);

        TopicRegistry registry = new TopicRegistry(redisson, "mqtest");
        registry.clearRegistry();

        verify(set).delete();
    }
}
