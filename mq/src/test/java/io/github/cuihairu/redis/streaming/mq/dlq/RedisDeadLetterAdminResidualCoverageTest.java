package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for RedisDeadLetterAdmin: replayAll per-entry failure, listTopics null
 * keys, and extractTopicFromDlqKey guard branches (invoked reflectively with hostile keys).
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterAdminResidualCoverageTest {

    private RedissonClient client;
    private DeadLetterService service;
    private RedisDeadLetterAdmin admin;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        service = mock(DeadLetterService.class);
        admin = new RedisDeadLetterAdmin(client, service);
    }

    @Test
    void replayAllCountsSuccessesAndSwallowsPerEntryFailures() {
        StreamMessageId ok = new StreamMessageId(1, 0);
        StreamMessageId bad = new StreamMessageId(2, 0);
        Map<StreamMessageId, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put(ok, Map.of());
        raw.put(bad, Map.of());
        when(service.range(eq("t"), anyInt())).thenReturn(raw);
        when(service.replay("t", ok)).thenReturn(true);
        when(service.replay("t", bad)).thenThrow(new IllegalStateException("replay boom"));

        assertEquals(1, admin.replayAll("t", 5));
    }

    @Test
    void listTopicsSkipsNullKeysAndDeduplicates() {
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(Arrays.asList(
                null,
                "other:thing:dlq",
                "stream:topic:t1:dlq",
                "stream:topic:t1:dlq",
                "stream:topic:t2:dlq"));

        List<String> topics = admin.listTopics();
        assertEquals(2, topics.size());
        assertEquals(List.of("t1", "t2"), topics);
    }

    @Test
    void extractTopicFromDlqKeyHandlesNullAndForeignPrefix() throws Exception {
        Method m = RedisDeadLetterAdmin.class.getDeclaredMethod("extractTopicFromDlqKey", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(admin, (Object) null));
        assertNull(m.invoke(admin, "xx:dlq"));
        assertEquals("t9", m.invoke(admin, "stream:topic:t9:dlq"));
    }
}
