package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers RedisMessageConsumer dispatch/requeue/missing-payload/ack paths with mocked Redis,
 * including headers-as-map and headers-as-json-string variants.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageConsumerDispatchCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> dlqStream;
    private RStream<String, Object> dataStream;
    private RMap<String, String> frontier;
    private RBucket<String> bucket;
    private RedisMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        dlqStream = mock(RStream.class);
        dataStream = mock(RStream.class);
        frontier = mock(RMap.class);
        bucket = mock(RBucket.class);

        when(client.getStream(anyString())).thenReturn((RStream) dlqStream);
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RStream) dataStream);
        when(client.getMap(anyString())).thenReturn((RMap) frontier);
        when(client.getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RMap) mock(RMap.class));
        when(client.getScoredSortedSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((org.redisson.api.RScoredSortedSet) mock(org.redisson.api.RScoredSortedSet.class));
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) bucket);
        when(dlqStream.add(any())).thenReturn(new StreamMessageId(9, 0));
        when(dataStream.add(any())).thenReturn(new StreamMessageId(9, 1));

        consumer = new RedisMessageConsumer(client, "unit-dispatch",
                mock(TopicPartitionRegistry.class),
                MqOptions.builder().retryBaseBackoffMs(0).retryMaxBackoffMs(0).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        invoke(consumer, "close", new Class<?>[]{});
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static final Class<?>[] HANDLE_MISSING_4 = {String.class, String.class, int.class, String.class, Map.class};
    private static final Class<?>[] HANDLE_MISSING_6 = {String.class, String.class, int.class, String.class, Map.class, RStream.class};
    private static final Class<?>[] REQUEUE = {RStream.class, String.class, String.class, int.class, Message.class, Map.class};
    private static final Class<?>[] DISPATCH = {String.class, String.class, String.class, int.class, Message.class, MessageHandleResult.class, Map.class, RStream.class};
    private static final Class<?>[] ACK = {String.class, String.class, int.class, RStream.class, String.class, Map.class};
    private static final Class<?>[] SEND_DLQ = {Message.class};

    private Message message(String payload, int retryCount, int maxRetries) {
        Message m = new Message();
        m.setId("5-0");
        m.setTopic("t");
        m.setPayload(payload);
        m.setTimestamp(Instant.now());
        m.setRetryCount(retryCount);
        m.setMaxRetries(maxRetries);
        m.setHeaders(new HashMap<>());
        return m;
    }

    // ===== handleMissingPayload =====

    @Test
    void handleMissingPayloadWithMapHeadersDlqsAndAcks() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "missing:ref:1");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        data.put("headers", headers);
        data.put("key", "k1");
        data.put("retryCount", "1");
        data.put("maxRetries", "2");

        invoke(consumer, "handleMissingPayload", HANDLE_MISSING_4, "t", "g", 0, "5-0", data);

        verify(dlqStream).add(any());
        verify(dataStream).ack(eq("g"), eq(new StreamMessageId(5, 0)));
        verify(frontier).put(eq("g"), eq("5-0"));
    }

    @Test
    void handleMissingPayloadWithStringHeadersParsesJson() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("headers", "{\"h1\":\"v1\"}");
        data.put("retryCount", 2);
        data.put("maxRetries", 3);

        invoke(consumer, "handleMissingPayload", HANDLE_MISSING_4, "t", "g", 0, "5-1", data);

        verify(dlqStream).add(any());
        verify(dataStream).ack(eq("g"), eq(new StreamMessageId(5, 1)));
    }

    @Test
    void handleMissingPayloadSixArgAcksViaProvidedStream() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("headers", new HashMap<String, String>());
        RStream<String, Object> provided = mock(RStream.class);

        invoke(consumer, "handleMissingPayload", HANDLE_MISSING_6, "t", "g", 0, "6-0", data, provided);

        verify(provided).ack(eq("g"), eq(new StreamMessageId(6, 0)));
    }

    // ===== sendToDeadLetterQueue =====

    @Test
    void sendToDeadLetterQueuePublishesRecord() throws Exception {
        Message m = message("p", 1, 3);
        m.setKey("kk");
        invoke(consumer, "sendToDeadLetterQueue", SEND_DLQ, m);
        verify(dlqStream).add(any());
        verify(dlqStream, never()).add(argThat(a -> false));
    }

    // ===== requeueOrDeadLetter =====

    @Test
    void requeueWhenRetriesExhaustedGoesToDlq() throws Exception {
        Message m = message("p", 3, 3);
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, data);
        verify(dlqStream).add(any());
        verify(dataStream).ack(eq("g"), any());
    }

    @Test
    void requeueWithTinyBackoffReEnqueuesDirectly() throws Exception {
        Message m = message("p", 0, 3);
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, data);
        verify(dataStream).ack(eq("g"), any());
        verify(dataStream).add(any());
        verify(dlqStream, never()).add(any());
    }

    @Test
    void requeueWithBackoffSchedulesRetryItemAndPreservesOriginalId() throws Exception {
        RedisMessageConsumer slow = new RedisMessageConsumer(client, "unit-slow",
                mock(TopicPartitionRegistry.class),
                MqOptions.builder().retryBaseBackoffMs(5000).retryMaxBackoffMs(5000).build());
        try {
            Message m = message("p", 0, 3);
            m.setKey("k");
            Map<String, Object> data = new HashMap<>();
            invoke(slow, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "7-0", 0, m, data);

            verify(dataStream).ack(eq("g"), any());
            verify(client, atLeastOnce()).getMap(anyString(), eq(StringCodec.INSTANCE));
            verify(client).getScoredSortedSet(eq(StreamKeys.retryBucket("t")), eq(StringCodec.INSTANCE));
            assertEquals("7-0", m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID));
            verify(dlqStream, never()).add(any());
        } finally {
            invoke(slow, "close", new Class<?>[]{});
        }
    }

    @Test
    void requeueKeepsExistingOriginalMessageIdHeader() throws Exception {
        Message m = message("p", 0, 3);
        m.getHeaders().put(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-1");
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, data);
        assertEquals("orig-1", m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    @Test
    void requeueCopiesNonHashMapHeaders() throws Exception {
        Message m = message("p", 0, 3);
        m.setHeaders(Map.of("a", "b")); // immutable map triggers the copy branch
        Map<String, Object> data = new HashMap<>();
        assertDoesNotThrow(() -> invoke(consumer, "requeueOrDeadLetter", REQUEUE, dataStream, "g", "5-0", 0, m, data));
        assertEquals("5-0", m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    // ===== dispatchResult =====

    @Test
    void dispatchSuccessAcks() throws Exception {
        Message m = message("p", 0, 3);
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0, m, MessageHandleResult.SUCCESS, data, dataStream);
        verify(dataStream).ack(eq("g"), any());
        verify(dlqStream, never()).add(any());
    }

    @Test
    void dispatchSuccessWithDeferAckHeaderSkipsAck() throws Exception {
        Message m = message("p", 0, 3);
        m.getHeaders().put(MqHeaders.DEFER_ACK, "true");
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0, m, MessageHandleResult.SUCCESS, data, dataStream);
        verify(dataStream, never()).ack(any(), any());
    }

    @Test
    void dispatchRetryRequeues() throws Exception {
        Message m = message("p", 0, 3);
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0, m, MessageHandleResult.RETRY, data, dataStream);
        verify(dataStream).ack(eq("g"), any());
    }

    @Test
    void dispatchFailAndDeadLetterSendToDlqAndAck() throws Exception {
        Message m1 = message("p", 0, 3);
        Message m2 = message("p", 0, 3);
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-0", 0, m1, MessageHandleResult.FAIL, data, dataStream);
        invoke(consumer, "dispatchResult", DISPATCH, "t", "g", "5-1", 0, m2, MessageHandleResult.DEAD_LETTER, data, dataStream);
        verify(dlqStream, times(2)).add(any());
        verify(dataStream, times(2)).ack(eq("g"), any());
    }

    // ===== ackViaBackend =====

    @Test
    void ackViaBackendFallbackFetchesStreamWhenNull() throws Exception {
        Map<String, Object> data = new HashMap<>();
        invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, null, "3-0", data);
        verify(dataStream).ack(eq("g"), eq(new StreamMessageId(3, 0)));
    }

    @Test
    void ackViaBackendWithBrokerDelegatesToBroker() throws Exception {
        io.github.cuihairu.redis.streaming.mq.broker.Broker broker =
                mock(io.github.cuihairu.redis.streaming.mq.broker.Broker.class);
        RedisMessageConsumer withBroker = new RedisMessageConsumer(client, "unit-broker",
                mock(TopicPartitionRegistry.class), MqOptions.builder().build(), broker);
        try {
            invoke(withBroker, "ackViaBackend", ACK, "t", "g", 0, null, "3-1", null);
            verify(broker).ack("t", "g", 0, "3-1");
        } finally {
            invoke(withBroker, "close", new Class<?>[]{});
        }
    }

    @Test
    void ackViaBackendCleansPayloadHashRef() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "payload:ref:9");
        data.put("headers", headers);
        invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, dataStream, "3-2", data);
        verify(bucket).delete();
    }

    @Test
    void ackViaBackendRethrowsAckFailure() throws Exception {
        doThrow(new IllegalStateException("ack boom")).when(dataStream).ack(any(), any());
        Exception e = assertThrows(Exception.class,
                () -> invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, dataStream, "3-3", null));
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    @Test
    void ackViaBackendUpdatesFrontierOnlyForNewerIds() throws Exception {
        Map<String, Object> data = new HashMap<>();
        when(frontier.get("g")).thenReturn("4-0");
        invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, dataStream, "5-0", data);
        verify(frontier).put("g", "5-0");

        when(frontier.get("g")).thenReturn("9-0");
        invoke(consumer, "ackViaBackend", ACK, "t", "g", 0, dataStream, "5-0", data);
        verify(frontier, times(1)).put(eq("g"), eq("5-0"));
    }
}
