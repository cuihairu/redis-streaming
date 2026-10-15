package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Data-plane sink keys: a sink still writes to the caller-given name unless a key
 * prefix is supplied, and with one it becomes {@code prefix:tenant:name} so two
 * tenants can drive the same sink name without sharing one Redis key.
 */
class RedisSinkTenantTest {

    private final RedissonClient client = mock(RedissonClient.class);

    @Test
    void withoutAPrefixTheCallerGivenNameIsUsedVerbatim() {
        assertEquals("orders", new RedisListSink<String>(client, "orders").getListName());
        assertEquals("scores", new RedisHashSink<String, String>(client, "scores").getHashName());
        assertEquals("events", new RedisStreamSink<String>(client, "events").getStreamName());

        // an explicit null/blank prefix behaves the same as the legacy constructors
        assertEquals("orders",
                new RedisListSink<String>(client, "orders", new ObjectMapper(), null, null).getListName());
        assertEquals("orders",
                new RedisListSink<String>(client, "orders", new ObjectMapper(), "  ", "acme").getListName());
    }

    @Test
    void withAPrefixTheTenantSegmentSitsBetweenPrefixAndName() {
        assertEquals("app:acme:orders",
                new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", "acme").getListName());
        assertEquals("app:beta:scores",
                new RedisHashSink<String, String>(client, "scores", new ObjectMapper(), null, "beta").getHashName());
        assertEquals("app:team-a.1:events",
                new RedisStreamSink<String>(client, "events", "value", new ObjectMapper(), "app", "team-a.1")
                        .getStreamName());
    }

    @Test
    void theDefaultTenantAddsNoSegmentSoExistingDeploymentsAreUnchanged() {
        assertEquals("app:orders",
                new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", null).getListName());
        assertEquals("app:scores",
                new RedisHashSink<String, String>(client, "scores", new ObjectMapper(), null, "default").getHashName());
        assertEquals("app:events",
                new RedisStreamSink<String>(client, "events", "value", new ObjectMapper(), "app", "default")
                        .getStreamName());
    }

    @Test
    void twoTenantsNeverResolveToTheSameKey() {
        String acme = new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", "acme").getListName();
        String beta = new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", "beta").getListName();
        assertEquals("app:acme:orders", acme);
        assertEquals("app:beta:orders", beta);
    }

    @Test
    void theTenantNameGrammarIsTheSharedOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", "a:b"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisStreamSink<String>(client, "events", "value", new ObjectMapper(), "app", "has space"));
    }

    @Test
    void writesGoToTheScopedName() {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        @SuppressWarnings("unchecked")
        RStream<String, String> stream = mock(RStream.class);
        when(client.getList(any(String.class))).thenReturn(list);
        when(client.getMap(any(String.class))).thenReturn(map);
        when(client.getStream(any(String.class), any(org.redisson.client.codec.Codec.class))).thenReturn(stream);
        when(list.add(any(String.class))).thenReturn(true);
        when(map.put(any(String.class), any(String.class))).thenReturn(null);
        when(stream.add(any(org.redisson.api.stream.StreamAddArgs.class)))
                .thenReturn(new org.redisson.api.stream.StreamMessageId(1L, 0L));

        new RedisListSink<String>(client, "orders", new ObjectMapper(), "app", "acme").write("a");
        new RedisHashSink<String, String>(client, "scores", new ObjectMapper(), Duration.ofMinutes(1), null, "acme")
                .write("k", "v");
        new RedisStreamSink<String>(client, "events", "value", new ObjectMapper(), "app", "acme").write("a");

        verify(client).getList("app:acme:orders");
        verify(client).getMap("app:acme:scores");
        verify(client).getStream(eq("app:acme:events"), eq(StringCodec.INSTANCE));
    }
}
