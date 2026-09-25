package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterConsumer;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterEntry;
import io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys;
import io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link DlqConsumerAdapter}: the proactive replay lambda's
 * header/visibility combinations and the result-mapping bridge. The RETRY mapping is
 * asserted only on its return value (the internal re-publish there is a known defect and
 * its side effect is deliberately not asserted).
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DlqConsumerAdapterSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> partitionStream;
    private DlqConsumerAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        DlqKeys.configure("stream:topic");
        client = mock(RedissonClient.class);
        partitionStream = mock(RStream.class);
        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) partitionStream);
        adapter = new DlqConsumerAdapter(client, "dlq-adapter", MqOptions.builder().build());
    }

    @AfterEach
    void tearDown() throws Exception {
        org.mockito.Mockito.reset(client, partitionStream);
    }

    private ReplayHandler replayHandler() throws Exception {
        Field delegateField = DlqConsumerAdapter.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(adapter);
        Field rh = delegate.getClass().getDeclaredField("replayHandler");
        rh.setAccessible(true);
        return (ReplayHandler) rh.get(delegate);
    }

    private DeadLetterConsumer.HandleResult bridge(MessageHandler handler, DeadLetterEntry entry) throws Exception {
        Field delegateField = DlqConsumerAdapter.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(adapter);
        adapter.subscribe("t", handler);
        Field subs = delegate.getClass().getDeclaredField("subs");
        subs.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) subs.get(delegate);
        Object sub = map.get("t");
        Field handlerField = sub.getClass().getDeclaredField("handler");
        handlerField.setAccessible(true);
        DeadLetterConsumer.DeadLetterHandler wrapped = (DeadLetterConsumer.DeadLetterHandler) handlerField.get(sub);
        return wrapped.handle(entry);
    }

    private static DeadLetterEntry entry(Map<String, String> headers) {
        return new DeadLetterEntry("id-1", "orig", 0, "payload", headers, Instant.now(), 0, 3);
    }

    // ===== replay lambda header/visibility combinations =====

    @Test
    void replayWithNullAndEmptyHeadersOmitsHeadersField() throws Exception {
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(5L);
        ReplayHandler replay = replayHandler();

        assertTrue(replay.publish("t", 0, "p", null, 3));
        assertTrue(replay.publish("t", 0, "p", new HashMap<>(), 3));

        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(partitionStream, times(2)).add(captor.capture());
        for (Object arg : captor.getAllValues()) {
            Map<String, Object> entries = extractEntries(arg);
            assertFalse(entries.containsKey("headers"));
            assertEquals("p", entries.get("payload"));
        }
    }

    @Test
    void replayWithHeadersIncludesJsonAndNonStringPayload() throws Exception {
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(5L);
        ReplayHandler replay = replayHandler();

        Map<String, String> headers = new HashMap<>();
        headers.put("k", "v");
        assertTrue(replay.publish("t", 0, Map.of("o", "1"), headers, 3));

        var captor = org.mockito.ArgumentCaptor.forClass(org.redisson.api.stream.StreamAddArgs.class);
        verify(partitionStream).add(captor.capture());
        Map<String, Object> entries = extractEntries(captor.getValue());
        assertEquals("{\"o\":\"1\"}", entries.get("payload"));
        assertEquals("{\"k\":\"v\"}", entries.get("headers"));
    }

    @Test
    void replayReturnsFalseWhenAddYieldsNull() throws Exception {
        when(partitionStream.add(any())).thenReturn(null);
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(5L);
        ReplayHandler replay = replayHandler();

        assertFalse(replay.publish("t", 0, "p", null, 3));
    }

    @Test
    void replayReAddsWhenNotVisibleByExistenceOrSize() throws Exception {
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(false);
        ReplayHandler replay = replayHandler();
        assertTrue(replay.publish("t", 0, "p", null, 3));
        verify(partitionStream, times(2)).add(any());

        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(0L);
        assertTrue(replay.publish("t", 0, "p", null, 3));
        verify(partitionStream, times(4)).add(any());
    }

    @Test
    void replayVisibilityCheckFailureIsSwallowed() throws Exception {
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenThrow(new IllegalStateException("exists boom"));
        ReplayHandler replay = replayHandler();

        assertTrue(replay.publish("t", 0, "p", null, 3));
        verify(partitionStream, times(1)).add(any());
    }

    @Test
    void replayReturnsFalseWhenStreamLookupFails() throws Exception {
        when(client.getStream(anyString(), any(Codec.class))).thenThrow(new IllegalStateException("lookup boom"));
        ReplayHandler replay = replayHandler();

        assertFalse(replay.publish("t", 0, "p", null, 3));
    }

    // ===== toResult mapping bridge =====

    @Test
    void toResultMapsAllHandlerOutcomes() throws Exception {
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(5L);

        assertEquals(DeadLetterConsumer.HandleResult.SUCCESS,
                bridge(m -> MessageHandleResult.SUCCESS, entry(null)));
        assertEquals(DeadLetterConsumer.HandleResult.FAIL,
                bridge(m -> MessageHandleResult.FAIL, entry(null)));
        assertEquals(DeadLetterConsumer.HandleResult.FAIL,
                bridge(m -> MessageHandleResult.DEAD_LETTER, entry(new HashMap<>())));
        assertEquals(DeadLetterConsumer.HandleResult.RETRY,
                bridge(m -> MessageHandleResult.RETRY, entry(new HashMap<>())));
        assertEquals(DeadLetterConsumer.HandleResult.RETRY,
                bridge(m -> MessageHandleResult.RETRY, entry(headers("k", "v"))));
    }

    private static Map<String, String> headers(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> extractEntries(Object addArgs) throws Exception {
        for (Class<?> c = addArgs.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Map<String, Object>) f.get(addArgs);
                }
            }
        }
        throw new IllegalStateException("no entries map found on " + addArgs.getClass());
    }
}
