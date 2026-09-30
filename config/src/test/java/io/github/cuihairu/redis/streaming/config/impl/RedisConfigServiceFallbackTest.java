package io.github.cuihairu.redis.streaming.config.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-44 regression for the publishConfig degradation path. The old fallback
 * regenerated a fresh version and rewrote unconditionally, so a Lua script that had
 * actually applied (lost response) got rewritten under a second version with a
 * duplicate history record; its hash writes were also non-atomic. Now the fallback
 * reuses the Lua attempt's version, skips the rewrite when that version is already
 * stored, and writes the hash fields in one REDIS_WRITE_ATOMIC batch. Uses only the
 * public API, so it reproduces on the pre-fix code.
 */
class RedisConfigServiceFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RedissonClient client;
    private RScript script;
    @SuppressWarnings("unchecked")
    private RMap<String, String> map;
    private RTopic topic;
    private final AtomicReference<String> entryJson = new AtomicReference<>();

    @SuppressWarnings("unchecked")
    private RedisConfigService newService() {
        client = mock(RedissonClient.class);
        script = mock(RScript.class);
        map = mock(RMap.class);
        topic = mock(RTopic.class);

        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.<String, String>getMap(any(String.class), any(StringCodec.class))).thenReturn(map);
        when(client.getTopic(anyString(), any(Codec.class))).thenReturn(topic);
        when(topic.publish(any())).thenReturn(1L);

        // capture the entry the Lua path was given, then fail like a lost response
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any(Object.class)))
                .thenAnswer(inv -> {
                    entryJson.set((String) inv.getArguments()[6]);
                    throw new RuntimeException("lua response lost");
                });

        RedisConfigService svc = new RedisConfigService(client, new ConfigServiceConfig());
        svc.start();
        return svc;
    }

    private String storedVersion() throws Exception {
        return MAPPER.readValue(entryJson.get(),
                new TypeReference<Map<String, String>>() {}).get("version");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackReusesTheLuaVersionAndWritesAtomically() throws Exception {
        RedisConfigService svc = newService();
        RBatch batch = mock(RBatch.class);
        RMapAsync<String, String> entry = mock(RMapAsync.class);
        when(map.readAllMap()).thenReturn(new HashMap<>()); // Lua never applied
        when(client.createBatch(any(BatchOptions.class))).thenReturn(batch);
        when(batch.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(entry);

        assertTrue(svc.publishConfig("d1", "g1", "content-v1", "desc"));

        ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
        verify(entry).putAsync(eq("version"), version.capture());
        assertEquals(storedVersion(), version.getValue(),
                "the fallback must reuse the Lua attempt's version, not issue a second one");
        verify(entry).putAsync("content", "content-v1");
        verify(entry).putAsync(eq("updateTime"), anyString());
        verify(batch).execute();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackSkipsRewriteWhenLuaVersionAlreadyApplied() throws Exception {
        RedisConfigService svc = newService();

        // the stored hash already carries the version AND content of this instance's
        // (lost-response) Lua write — the skip condition requires both to match
        when(map.readAllMap()).thenAnswer(inv -> {
            Map<String, String> m = new HashMap<>();
            m.put("version", storedVersion());
            m.put("content", "content-v2");
            return m;
        });

        assertTrue(svc.publishConfig("d2", "g2", "content-v2", null));

        verify(map, never()).fastPut(anyString(), anyString());
        verify(client, never()).createBatch(any(BatchOptions.class));
        verify(topic).publish(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackRewritesWhenSameVersionHoldsDifferentContent() throws Exception {
        RedisConfigService svc = newService();
        RBatch batch = mock(RBatch.class);
        RMapAsync<String, String> entry = mock(RMapAsync.class);
        when(client.createBatch(any(BatchOptions.class))).thenReturn(batch);
        when(batch.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(entry);

        // another instance minted the identical version string in the same millisecond
        // but stored different content: this is NOT "my write already applied" — the
        // fallback must rewrite with the caller's content instead of skipping
        when(map.readAllMap()).thenAnswer(inv -> {
            Map<String, String> m = new HashMap<>();
            m.put("version", storedVersion());
            m.put("content", "older");
            return m;
        });

        assertTrue(svc.publishConfig("d2", "g2", "content-v2", null));

        verify(batch).execute();
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(entry, atLeastOnce()).putAsync(eq("content"), content.capture());
        assertEquals("content-v2", content.getValue());
        verify(topic).publish(any());
    }
}
