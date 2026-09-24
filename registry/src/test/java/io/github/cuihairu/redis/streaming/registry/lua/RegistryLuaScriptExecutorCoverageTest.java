package io.github.cuihairu.redis.streaming.registry.lua;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers RegistryLuaScriptExecutor.executeGetInstancesByFilters (reload/fallback/error),
 * buildFiltersJson combinations and executeHeartbeatUpdate eval fallback.
 */
class RegistryLuaScriptExecutorCoverageTest {

    private static RScript mockScript(RedissonClient redisson) {
        RScript script = mock(RScript.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        return script;
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetInstancesByFiltersReloadsScriptOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        doThrow(new RedisException("NOSCRIPT No matching script. Please use EVAL."))
                .doReturn(List.of("i1"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                        any(), any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<String> out = exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, "{\"a\":\"1\"}", null);
        assertEquals(List.of("i1"), out);
        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetInstancesByFiltersFallsBackToEvalAndBuildsAllFilterJsonShapes() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        // script loading fails at init -> every call uses plain eval
        when(script.scriptLoad(anyString())).thenThrow(new RuntimeException("redis down"));
        when(script.eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenReturn(List.of("i1"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);

        // both empty -> ""
        assertEquals(List.of("i1"), exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, null, null));
        verify(script).eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"), eq(""));

        // metadata only -> legacy passthrough
        assertEquals(List.of("i1"), exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, "{\"a\":\"1\"}", ""));
        verify(script).eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"), eq("{\"a\":\"1\"}"));

        // metrics only -> {"metrics":...}
        assertEquals(List.of("i1"), exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, null, "{\"cpu\":\"1\"}"));
        verify(script).eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"), eq("{\"metrics\":{\"cpu\":\"1\"}}"));

        // both -> combined structure
        assertEquals(List.of("i1"), exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L,
                "{\"a\":\"1\"}", "{\"cpu\":\"1\"}"));
        verify(script).eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"), eq("{\"metadata\":{\"a\":\"1\"},\"metrics\":{\"cpu\":\"1\"}}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetInstancesByFiltersWrapsUnexpectedFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha");
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection reset"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, "{}", null));
        assertTrue(ex.getMessage().contains("Get instances by filters failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullMessageScriptErrorsAreWrapped() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha");
        doThrow(new RedisException((String) null))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any(), any());
        doThrow(new RedisException((String) null))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                        any(), any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        assertThrows(RuntimeException.class,
                () -> exec.executeHeartbeatUpdate("hb", "inst", "id", 1L, "heartbeat_only", null, null, 10));
        assertThrows(RuntimeException.class,
                () -> exec.executeGetInstancesByFilters("hb", "pfx", "svc", 1L, 2L, "{}", null));
    }

    @Test
    void executeHeartbeatUpdateFallsBackToEvalWhenInitFailed() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        when(script.scriptLoad(anyString())).thenThrow(new RuntimeException("redis down"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        exec.executeHeartbeatUpdate("hb", "inst", "id", 1L, "heartbeat_only", null, null, 10);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                eq("id"), eq("1"), eq("heartbeat_only"), eq(""), eq(""), eq("10"));
    }

    @Test
    void executeHeartbeatUpdateWrapsUnexpectedFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mockScript(redisson);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha");
        doThrow(new IllegalStateException("broken"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> exec.executeHeartbeatUpdate("hb", "inst", "id", 1L, "metrics_update", null, "{}", 10));
        assertTrue(ex.getMessage().contains("Heartbeat update failed"));
    }
}
