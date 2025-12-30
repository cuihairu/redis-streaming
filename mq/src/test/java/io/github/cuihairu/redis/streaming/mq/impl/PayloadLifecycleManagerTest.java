package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PayloadLifecycleManager
 */
class PayloadLifecycleManagerTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @Mock
    private org.redisson.api.RBucket<Object> mockBucket;

    @Mock
    private org.redisson.api.RSet<Object> mockSet;

    @Mock
    private org.redisson.api.RScoredSortedSet<Object> mockSortedSet;

    public PayloadLifecycleManagerTest() {
        // Initialize mocks if needed - but JUnit 5 doesn't have @Before like JUnit 4
        // We'll initialize in setup methods when needed
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        assertNotNull(manager);
        // Uses default StreamKeys.controlPrefix() for controlPrefix
    }

    @Test
    void testConstructorWithMqOptions() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .keyPrefix("test:prefix")
                .retentionMs(3600000L)
                .build();

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, options);

        assertNotNull(manager);
    }

    @Test
    void testConstructorWithNullMqOptions() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, null);

        assertNotNull(manager);
        // Falls back to StreamKeys.controlPrefix()
    }

    @Test
    void testConstructorWithNullRedissonClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        assertNotNull(manager);
    }

    @Test
    void testGeneratePayloadKey() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .keyPrefix("test:prefix")
                .build();

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, options);

        String key = manager.generatePayloadKey("my-topic", 5);

        assertNotNull(key);
        assertTrue(key.contains("test:prefix"));
        assertTrue(key.contains("my-topic"));
        assertTrue(key.contains(":p:5:"));
        // Should contain a UUID
        assertTrue(key.matches(".*:p:5:[a-f0-9\\-]+"));
    }

    @Test
    void testGeneratePayloadKeyWithDifferentPartitions() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        String key0 = manager.generatePayloadKey("topic", 0);
        String key1 = manager.generatePayloadKey("topic", 1);
        String key2 = manager.generatePayloadKey("topic", 2);

        assertTrue(key0.contains(":p:0:"));
        assertTrue(key1.contains(":p:1:"));
        assertTrue(key2.contains(":p:2:"));
    }

    @Test
    void testStoreLargePayloadWithNullRedissonClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        assertThrows(RuntimeException.class, () ->
            manager.storeLargePayload("test-topic", 0, "test-payload")
        );
    }

    @Test
    void testLoadPayloadWithNullRedissonClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        assertThrows(RuntimeException.class, () ->
            manager.loadPayload("test-key")
        );
    }

    @Test
    void testDeletePayloadKeyWithNullKey() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        assertFalse(manager.deletePayloadKey(null));
    }

    @Test
    void testDeletePayloadKeyWithEmptyKey() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        assertFalse(manager.deletePayloadKey(""));
    }

    @Test
    void testDeletePayloadKeyWithValidKeyAndNullClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        // Should return false, not throw exception
        assertFalse(manager.deletePayloadKey("test:key"));
    }

    @Test
    void testExtractPayloadHashRefWithNullStreamData() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        assertNull(manager.extractPayloadHashRef(null));
    }

    @Test
    void testExtractPayloadHashRefWithMapHeaders() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test:hash:key");
        headers.put("other", "value");
        streamData.put("headers", headers);

        String ref = manager.extractPayloadHashRef(streamData);

        assertEquals("test:hash:key", ref);
    }

    @Test
    void testExtractPayloadHashRefWithNullHeadersMap() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put("other", "value");
        // No PAYLOAD_HASH_REF header
        streamData.put("headers", headers);

        assertNull(manager.extractPayloadHashRef(streamData));
    }

    @Test
    void testExtractPayloadHashRefWithStringHeaders() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        String jsonHeaders = "{\"x-payload-hash-ref\":\"test:json:key\",\"other\":\"value\"}";
        streamData.put("headers", jsonHeaders);

        String ref = manager.extractPayloadHashRef(streamData);

        assertEquals("test:json:key", ref);
    }

    @Test
    void testExtractPayloadHashRefWithInvalidJsonHeaders() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        streamData.put("headers", "invalid-json");

        assertNull(manager.extractPayloadHashRef(streamData));
    }

    @Test
    void testExtractPayloadHashRefWithNoHeaders() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();

        assertNull(manager.extractPayloadHashRef(streamData));
    }

    @Test
    void testDeletePayloadHashFromStreamDataWithValidRef() {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getBucket(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockBucket);
        when(mockBucket.delete()).thenReturn(true);
        when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSet);
        when(mockRedissonClient.getScoredSortedSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSortedSet);

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test:delete:key");
        streamData.put("headers", headers);

        boolean result = manager.deletePayloadHashFromStreamData(streamData);

        assertTrue(result);
        verify(mockBucket).delete();
    }

    @Test
    void testDeletePayloadHashFromStreamDataWithNullRef() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> streamData = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        streamData.put("headers", headers);

        boolean result = manager.deletePayloadHashFromStreamData(streamData);

        assertFalse(result);
    }

    @Test
    void testDeletePayloadHashFromMessageWithNullMessage() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        assertFalse(manager.deletePayloadHashFromMessage(null));
    }

    @Test
    void testDeletePayloadHashFromMessageWithNullHeaders() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Message message = new Message();
        message.setHeaders(null);

        assertFalse(manager.deletePayloadHashFromMessage(message));
    }

    @Test
    void testDeletePayloadHashFromMessageWithValidRef() {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getBucket(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockBucket);
        when(mockBucket.delete()).thenReturn(true);
        when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSet);
        when(mockRedissonClient.getScoredSortedSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSortedSet);

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Message message = new Message();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test:message:key");
        message.setHeaders(headers);

        boolean result = manager.deletePayloadHashFromMessage(message);

        assertTrue(result);
    }

    @Test
    void testCleanupTopicPayloadHashesWithNullClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        // Should handle gracefully and return 0
        long result = manager.cleanupTopicPayloadHashes("test-topic");

        assertEquals(0, result);
    }

    @Test
    void testCleanupOrphanedPayloadHashesWithNullClient() {
        PayloadLifecycleManager manager = new PayloadLifecycleManager(null);

        // Should handle gracefully and return 0
        long result = manager.cleanupOrphanedPayloadHashes(3600000L);

        assertEquals(0, result);
    }

    @Test
    void testCleanupOrphanedPayloadHashesWithNegativeTime() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        // Negative time should be clamped to 0
        long result = manager.cleanupOrphanedPayloadHashes(-1000L);

        // With mock setup not returning data, should return 0
        // In real scenario it would query from cutoff time = System.currentTimeMillis()
        assertEquals(0, result);
    }

    @Test
    void testMqOptionsWithZeroRetention() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .keyPrefix("test:prefix")
                .retentionMs(0L) // No TTL
                .build();

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, options);

        assertNotNull(manager);
    }

    @Test
    void testMqOptionsWithBlankKeyPrefix() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .keyPrefix("  ")
                .retentionMs(3600000L)
                .build();

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, options);

        // Should fall back to StreamKeys.controlPrefix()
        assertNotNull(manager);
    }

    @Test
    void testGeneratePayloadKeyWithSpecialCharactersInTopic() {
        MockitoAnnotations.openMocks(this);
        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        String key = manager.generatePayloadKey("topic.with-dots_and:colons", 0);

        assertNotNull(key);
        assertTrue(key.contains("topic.with-dots_and:colons"));
    }

    @Test
    void testStoreLargePayloadWithObject() {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getBucket(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockBucket);
        when(mockRedissonClient.getSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSet);
        when(mockRedissonClient.getScoredSortedSet(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockSortedSet);

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Map<String, Object> payloadObject = new HashMap<>();
        payloadObject.put("key", "value");
        payloadObject.put("number", 42);

        String key = manager.storeLargePayload("test-topic", 0, payloadObject);

        assertNotNull(key);
        verify(mockBucket).set(anyString());
    }

    @Test
    void testLoadPayloadReturnsObject() {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getBucket(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockBucket);
        when(mockBucket.get()).thenReturn("{\"key\":\"value\",\"nested\":{\"data\":123}}");

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        Object result = manager.loadPayload("test:key");

        assertNotNull(result);
        // Jackson parses JSON as Map for objects
        assertInstanceOf(Map.class, result);
    }

    @Test
    void testLoadPayloadWithMissingKey() {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getBucket(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn(mockBucket);
        when(mockBucket.get()).thenReturn(null);

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient);

        assertThrows(RuntimeException.class, () ->
            manager.loadPayload("nonexistent:key")
        );
    }

    @Test
    void testConstructorWithDefaultKeyPrefix() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder().build();
        // Don't set keyPrefix - should use StreamKeys.controlPrefix()

        PayloadLifecycleManager manager = new PayloadLifecycleManager(mockRedissonClient, options);

        assertNotNull(manager);
    }
}
