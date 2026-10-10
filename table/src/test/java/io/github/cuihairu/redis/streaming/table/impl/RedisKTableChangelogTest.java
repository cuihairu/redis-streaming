package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit coverage for {@link RedisKTable} changelog mode: event codec round-trips,
 * the opt-in switch semantics and the static-snapshot fallback when disabled
 * (mocked Redisson — no server needed; the enabled path is covered against real
 * Redis in {@code RedisKTableChangelogIntegrationTest}).
 */
class RedisKTableChangelogTest {


    private RedisKTable<String, Integer> table() {
        return new RedisKTable<>(mock(RedissonClient.class), "unit-changelog", String.class, Integer.class);
    }

    @Test
    void changelogDisabledByDefault() {
        RedisKTable<String, Integer> t = table();
        assertFalse(t.isChangelogEnabled());
        assertEquals("table-changelog:unit-changelog", t.changelogTopic());
        assertEquals("table-changelog-group:unit-changelog", t.defaultChangelogGroup());
    }

    @Test
    void withChangelogIsIdempotentAndSetsFlag() {
        RedisKTable<String, Integer> t = table();
        assertSame(t, t.withChangelog());
        assertTrue(t.isChangelogEnabled());
        assertSame(t, t.withChangelog()); // idempotent, does not throw
        assertTrue(t.isChangelogEnabled());
    }

    @Test
    void toStreamEnvOverloadValidatesArguments() {
        RedisKTable<String, Integer> t = table();
        IllegalStateException disabled = assertThrows(IllegalStateException.class,
                () -> t.toStream(env(), "grp"));
        assertTrue(disabled.getMessage().contains("withChangelog"));
        t.withChangelog();
        // enabled: building the pipeline must not touch Redis (lazy execution)
        assertNotNull(t.toStream(env()));
        assertThrows(NullPointerException.class, () -> t.toStream(null, "grp"));
        assertThrows(NullPointerException.class, () -> t.toStream(env(), null));
        assertThrows(IllegalArgumentException.class, () -> t.toStream(env(), " "));
    }

    @Test
    void toStreamRejectsNoArgWhenChangelogEnabled() {
        RedisKTable<String, Integer> t = table().withChangelog();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.toStream());
        assertTrue(e.getMessage().contains("toStream(RedisStreamExecutionEnvironment)"));
    }

    private RedisStreamExecutionEnvironment env() {
        // real environment on a mock client: pipeline construction is lazy, no Redis touch
        return RedisStreamExecutionEnvironment.create(mock(RedissonClient.class));
    }

    @Test
    void changelogPutEventParsesToKeyValue() {
        RedisKTable<String, Integer> t = table().withChangelog();
        String put = "{\"op\":\"PUT\",\"k\":\"\\\"a\\\"\",\"v\":\"42\"}";
        var kv = t.parseChangelogForTest(new io.github.cuihairu.redis.streaming.mq.Message(
                t.changelogTopic(), put));
        assertEquals("a", kv.getKey());
        assertEquals(42, kv.getValue());
    }

    @Test
    void changelogDelEventParsesToNullValue() {
        RedisKTable<String, Integer> t = table().withChangelog();
        String del = "{\"op\":\"DEL\",\"k\":\"\\\"a\\\"\"}";
        var kv = t.parseChangelogForTest(new io.github.cuihairu.redis.streaming.mq.Message(
                t.changelogTopic(), del));
        assertEquals("a", kv.getKey());
        assertNull(kv.getValue());
    }

    @Test
    void unknownChangelogOpIsRejected() {
        RedisKTable<String, Integer> t = table().withChangelog();
        String bad = "{\"op\":\"CLEAR\"}";
        assertThrows(IllegalStateException.class, () -> t.parseChangelogForTest(
                new io.github.cuihairu.redis.streaming.mq.Message(t.changelogTopic(), bad)));
    }

    @Test
    void malformedChangelogPayloadIsWrapped() {
        RedisKTable<String, Integer> t = table().withChangelog();
        RuntimeException e = assertThrows(RuntimeException.class, () -> t.parseChangelogForTest(
                new io.github.cuihairu.redis.streaming.mq.Message(t.changelogTopic(), "not-json")));
        assertTrue(e.getMessage().contains("Failed to parse changelog event"));
        assertTrue(e.getCause() instanceof java.io.IOException);
    }

    @Test
    void putEmitsPutAndDelEventsToChangelogTopic() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        org.redisson.api.RMap map = mock(org.redisson.api.RMap.class);
        when(redis.getMap(org.mockito.ArgumentMatchers.eq("unit-changelog"),
                eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenReturn(map);
        io.github.cuihairu.redis.streaming.mq.MessageProducer producer =
                mock(io.github.cuihairu.redis.streaming.mq.MessageProducer.class);
        when(producer.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture("1-0"));

        RedisKTable<String, Integer> t = new RedisKTable<>(redis, "unit-changelog", String.class, Integer.class)
                .withChangelog(producer);
        t.put("a", 1);
        t.put("a", null);

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(producer, times(2)).send(eq(t.changelogTopic()), eq("unit-changelog"), payload.capture());
        // field order is not deterministic (HashMap), assert semantically
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode putEvent = om.readTree(payload.getAllValues().get(0));
        assertEquals("PUT", putEvent.path("op").asText());
        assertEquals("\"a\"", putEvent.path("k").asText());
        assertEquals("1", putEvent.path("v").asText());
        com.fasterxml.jackson.databind.JsonNode delEvent = om.readTree(payload.getAllValues().get(1));
        assertEquals("DEL", delEvent.path("op").asText());
        assertEquals("\"a\"", delEvent.path("k").asText());
        assertFalse(delEvent.has("v")); // DEL events carry no value
    }

    @Test
    void changelogEmitFailureDoesNotBreakPut() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        org.redisson.api.RMap map = mock(org.redisson.api.RMap.class);
        when(redis.getMap(org.mockito.ArgumentMatchers.eq("unit-changelog"),
                eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenReturn(map);
        io.github.cuihairu.redis.streaming.mq.MessageProducer producer =
                mock(io.github.cuihairu.redis.streaming.mq.MessageProducer.class);
        when(producer.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("boom")));

        RedisKTable<String, Integer> t = new RedisKTable<>(redis, "unit-changelog", String.class, Integer.class)
                .withChangelog(producer);
        assertDoesNotThrow(() -> t.put("a", 1));
        verify(map).put(eq("\"a\""), eq("1")); // primary store still updated
    }

    @Test
    void changelogSendThrowingIsSwallowed() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        org.redisson.api.RMap map = mock(org.redisson.api.RMap.class);
        when(redis.getMap(org.mockito.ArgumentMatchers.eq("unit-changelog"),
                eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenReturn(map);
        io.github.cuihairu.redis.streaming.mq.MessageProducer producer =
                mock(io.github.cuihairu.redis.streaming.mq.MessageProducer.class);
        when(producer.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        RedisKTable<String, Integer> t = new RedisKTable<>(redis, "unit-changelog", String.class, Integer.class)
                .withChangelog(producer);
        assertDoesNotThrow(() -> t.put("a", 1));
        assertDoesNotThrow(() -> t.put("a", null));
    }

    @Test
    void toStreamAttachesToEnvironmentWhenChangelogEnabled() {
        io.github.cuihairu.redis.streaming.mq.MessageProducer producer =
                mock(io.github.cuihairu.redis.streaming.mq.MessageProducer.class);
        RedisKTable<String, Integer> t = new RedisKTable<>(mock(RedissonClient.class), "unit-changelog",
                String.class, Integer.class).withChangelog(producer);
        // lazy pipeline construction must not touch Redis
        assertNotNull(t.toStream(env()));
    }
}
