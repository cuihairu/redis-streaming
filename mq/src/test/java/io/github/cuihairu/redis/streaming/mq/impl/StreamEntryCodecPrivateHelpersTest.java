package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers StreamEntryCodec private helpers (getPayloadSize, generatePayloadHashKey,
 * storePayloadInHash, loadPayloadFromHash) and the RedissonClient-based wrapper overloads.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class StreamEntryCodecPrivateHelpersTest {

    private RedissonClient client;
    private RBucket<String> bucket;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) bucket);
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = StreamEntryCodec.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    // ===== getPayloadSize =====

    static class Unserializable {
        public String getBoom() {
            throw new IllegalStateException("no serialization");
        }
    }

    @Test
    void getPayloadSizeHandlesNullJsonAndFallback() throws Exception {
        Class<?>[] sig = {Object.class};
        assertEquals(0, invokeStatic("getPayloadSize", sig, (Object) null));
        int jsonSize = (Integer) invokeStatic("getPayloadSize", sig, "hello");
        assertTrue(jsonSize >= 5);
        Unserializable bad = new Unserializable();
        assertEquals(bad.toString().length() * 2, invokeStatic("getPayloadSize", sig, bad));
    }

    // ===== generatePayloadHashKey =====

    @Test
    void generatePayloadHashKeyEmbedsTopicAndPartition() throws Exception {
        Class<?>[] sig = {String.class, int.class};
        String key = (String) invokeStatic("generatePayloadHashKey", sig, "tp", 2);
        assertTrue(key.contains(":payload:tp:p:2:"), key);
    }

    // ===== storePayloadInHash =====

    @Test
    void storePayloadInHashStoresJsonWithTtl() throws Exception {
        Class<?>[] sig = {RedissonClient.class, String.class, Object.class};
        invokeStatic("storePayloadInHash", sig, client, "hk", Map.of("a", 1));
        verify(bucket).set(eq("{\"a\":1}"));
        verify(bucket).expire(eq(Duration.ofHours(24)));
    }

    @Test
    void storePayloadInHashWrapsFailures() throws Exception {
        Class<?>[] sig = {RedissonClient.class, String.class, Object.class};
        doThrow(new IllegalStateException("redis down")).when(bucket).set(anyString());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> invokeStatic("storePayloadInHash", sig, client, "hk", "p"));
        assertTrue(e.getMessage().contains("Failed to store large payload in hash"));
    }

    // ===== loadPayloadFromHash =====

    @Test
    void loadPayloadFromHashReadsJson() throws Exception {
        Class<?>[] sig = {RedissonClient.class, String.class};
        when(bucket.get()).thenReturn("{\"k\":\"v\"}");
        Object loaded = invokeStatic("loadPayloadFromHash", sig, client, "hk");
        assertInstanceOf(Map.class, loaded);
    }

    @Test
    void loadPayloadFromHashMissingKeyThrowsPayloadMissing() throws Exception {
        Class<?>[] sig = {RedissonClient.class, String.class};
        when(bucket.get()).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> invokeStatic("loadPayloadFromHash", sig, client, "hk"));
        assertTrue(e.getMessage().contains("Failed to load payload from hash"));
        assertTrue(e.getCause().getMessage().contains("Payload not found in hash"));
    }

    // ===== parsePartitionEntry: RedissonClient-based hash loading =====

    @Test
    void parsePartitionEntryLoadsHashPayloadViaRedissonClient() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "");
        data.put("timestamp", Instant.now().toString());
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "hk");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE, "99");
        data.put("headers", headers);
        when(bucket.get()).thenReturn("\"big-payload\"");

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data, client);

        assertEquals("big-payload", m.getPayload());
        assertNull(m.getHeaders().get(PayloadHeaders.PAYLOAD_HASH_REF));
        assertNull(m.getHeaders().get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNull(m.getHeaders().get(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE));
    }

    @Test
    void parsePartitionEntryMissingHashPayloadThrows() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", null);
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "gone");
        data.put("headers", headers);
        when(bucket.get()).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> StreamEntryCodec.parsePartitionEntry("t", "1-0", data, client));
        assertTrue(e.getMessage().contains("Failed to load payload from hash"));
    }

    // ===== wrapper overloads with RedissonClient =====

    @Test
    void buildDlqEntryWithRedissonClientStoresLargePayloadInHash() {
        Message m = new Message();
        m.setTopic("t");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70_000; i++) {
            big.append('x');
        }
        m.setPayload(big.toString());

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(m, client);

        assertNull(entry.get("payload"));
        Map<String, String> headers = castHeaders(entry.get("headers"));
        assertEquals(PayloadHeaders.STORAGE_TYPE_HASH, headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNotNull(headers.get(PayloadHeaders.PAYLOAD_HASH_REF));
        verify(bucket, atLeastOnce()).set(anyString());
    }

    @Test
    void parseDlqEntryWithRedissonClientResolvesHashPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("payload", "");
        data.put("retryCount", "1");
        data.put("timestamp", Instant.now().toString());
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "hk");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        data.put("headers", headers);
        when(bucket.get()).thenReturn("\"dlq-payload\"");

        Message m = StreamEntryCodec.parseDlqEntry("1-0", data, client);

        assertEquals("dlq-payload", m.getPayload());
        assertNull(m.getHeaders().get(PayloadHeaders.PAYLOAD_HASH_REF));
    }

    @Test
    void wrapperOverloadsAcceptNullRedissonClient() {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("small");
        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(m, (RedissonClient) null);
        assertEquals("small", entry.get("payload")); // inline path with null client
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        Message parsed = StreamEntryCodec.parseDlqEntry("1-0", data, (RedissonClient) null);
        assertEquals("t", parsed.getTopic());
    }

    @Test
    void parseDlqEntryHeaderVariantsCoverMapAndStringAndBroken() {
        // Map headers (Jackson convertValue path)
        Map<String, Object> withMap = new HashMap<>();
        withMap.put("originalTopic", "t");
        withMap.put("headers", Map.of("a", "b"));
        Message m1 = StreamEntryCodec.parseDlqEntry("1-0", withMap, (PayloadLifecycleManager) null);
        assertEquals("b", m1.getHeaders().get("a"));

        // JSON string headers (Jackson readValue TypeReference path)
        Map<String, Object> withJson = new HashMap<>();
        withJson.put("originalTopic", "t");
        withJson.put("headers", "{\"c\":\"d\"}");
        Message m2 = StreamEntryCodec.parseDlqEntry("1-1", withJson, (PayloadLifecycleManager) null);
        assertEquals("d", m2.getHeaders().get("c"));

        // Broken JSON string headers (swallowed)
        Map<String, Object> broken = new HashMap<>();
        broken.put("originalTopic", "t");
        broken.put("headers", "not-json");
        Message m3 = StreamEntryCodec.parseDlqEntry("1-2", broken, (PayloadLifecycleManager) null);
        assertNull(m3.getHeaders().get("c"));
    }

    @SuppressWarnings("rawtypes")
    private static Map<String, String> castHeaders(Object o) {
        return (Map) o;
    }
}
