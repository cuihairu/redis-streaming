package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link DeadLetterQueueManager}: empty/null DLQ reads and
 * replayMessage header normalization variants.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DeadLetterQueueManagerSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> dlqDefault;
    private RStream<String, Object> dlqString;
    private RStream<String, Object> orig;

    private static final StreamMessageId ID = new StreamMessageId(4, 0);
    private static final String DLQ_KEY = "stream:topic:t:dlq";
    private static final String ORIG_KEY = "stream:topic:t:p:0";

    @BeforeEach
    void setUp() throws Exception {
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure("streaming:mq", "stream:topic");
        client = mock(RedissonClient.class);
        dlqDefault = mock(RStream.class);
        dlqString = mock(RStream.class);
        orig = mock(RStream.class);
        when(client.getStream(DLQ_KEY)).thenReturn((RStream) dlqDefault);
        when(client.getStream(eq(DLQ_KEY), any(Codec.class))).thenReturn((RStream) dlqString);
        when(client.getStream(eq(ORIG_KEY), any(Codec.class))).thenReturn((RStream) orig);
    }

    private static Map<StreamMessageId, Map<String, Object>> one(Map<String, Object> data) {
        Map<StreamMessageId, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(ID, data);
        return out;
    }

    // ===== getDeadLetterMessages empty/null handling =====

    @Test
    void getDeadLetterMessagesReturnsEmptyWhenBothCodecsYieldNothing() throws Exception {
        when(dlqDefault.range(org.mockito.ArgumentMatchers.anyInt(), any(), any())).thenReturn(null);
        when(dlqString.range(org.mockito.ArgumentMatchers.anyInt(), any(), any())).thenReturn(null);
        assertTrue(new DeadLetterQueueManager(client).getDeadLetterMessages("t", 5).isEmpty());

        when(dlqDefault.range(org.mockito.ArgumentMatchers.anyInt(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(dlqString.range(org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("string codec boom"));
        assertTrue(new DeadLetterQueueManager(client).getDeadLetterMessages("t", 5).isEmpty());
    }

    // ===== replayMessage: missing entries =====

    @Test
    void replayMessageReturnsFalseWhenBothCodecsMissTheEntry() throws Exception {
        when(dlqDefault.range(1, ID, ID)).thenReturn(new LinkedHashMap<>());
        when(dlqString.range(1, ID, ID)).thenReturn(null);
        assertFalse(new DeadLetterQueueManager(client).replayMessage("t", ID));

        when(dlqDefault.range(1, ID, ID)).thenReturn(null);
        when(dlqString.range(1, ID, ID)).thenReturn(new LinkedHashMap<>());
        assertFalse(new DeadLetterQueueManager(client).replayMessage("t", ID));
    }

    // ===== replayMessage: header normalization =====

    @Test
    void replayMessageMapHeadersDropNullEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<Object, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        data.put("headers", headers);
        when(dlqDefault.range(1, ID, ID)).thenReturn(one(data));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(new DeadLetterQueueManager(client).replayMessage("t", ID));

        var captor = org.mockito.ArgumentCaptor.forClass(StreamAddArgs.class);
        verify(orig).add(captor.capture());
        Map<String, Object> entries = extractEntries(captor.getValue());
        Map<String, String> replayed = (Map<String, String>) entries.get("headers");
        assertEquals("b", replayed.get("a"));
        assertEquals(2, replayed.size(), "null key/value header entries must be dropped (plus storage marker)");
    }

    @Test
    void replayMessageStringHeadersAreParsedOrIgnored() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("headers", "{\"a\":\"b\"}");
        when(dlqString.range(1, ID, ID)).thenReturn(one(data));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(new DeadLetterQueueManager(client).replayMessage("t", ID));

        Map<String, Object> broken = new HashMap<>();
        broken.put("partitionId", 0);
        broken.put("headers", "{not json");
        when(dlqString.range(1, ID, ID)).thenReturn(one(broken));

        assertTrue(new DeadLetterQueueManager(client).replayMessage("t", ID));
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
