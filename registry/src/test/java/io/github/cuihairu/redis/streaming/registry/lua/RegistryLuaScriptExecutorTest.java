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

class RegistryLuaScriptExecutorTest {

    @Test
    void executeHeartbeatUpdateReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        // Initial preload: 7 scripts, then one reload.
        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaHB2");

        doThrow(new RedisException("NOSCRIPT No matching script"))
                .doReturn(null)
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        exec.executeHeartbeatUpdate("hb", "inst", "id", 1L, "heartbeat_only", null, null, 10);

        verify(script, atLeast(8)).scriptLoad(anyString());
        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetActiveInstancesFallsBackToEvalWhenInitFailed() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        // initScripts catches and allows fallback to eval mode
        when(script.scriptLoad(anyString())).thenThrow(new RuntimeException("redis down"));
        when(script.eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(), any(), any()))
                .thenReturn(List.of("a", "b"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<Object> out = exec.executeGetActiveInstances("hb", 100L, 10L);
        assertEquals(List.of("a", "b"), out);

        verify(script).eval(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(), any(), any());
        verify(script, never()).evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetActiveInstancesReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaActive2");

        doThrow(new RedisException("NOSCRIPT"))
                .doReturn(List.of("id1", "100"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                        any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<Object> out = exec.executeGetActiveInstances("hb", 1L, 2L);
        assertEquals(List.of("id1", "100"), out);

        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                eq("1"), eq("2"));
    }

    @Test
    void executeRegisterInstanceReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaReg2");

        doThrow(new RedisException("NOSCRIPT"))
                .doReturn("OK")
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        String out = exec.executeRegisterInstance("svcKey", "hbKey", "instKey",
                "svc", "id", 10L, "{\"k\":\"v\"}", 30);
        assertEquals("OK", out);

        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                eq("svc"), eq("id"), eq("10"), eq("{\"k\":\"v\"}"), eq("30"));
    }

    @Test
    void executeDeregisterInstanceReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaDeReg2");

        doThrow(new RedisException("NOSCRIPT"))
                .doReturn("OK")
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                        any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        String out = exec.executeDeregisterInstance("svcKey", "hbKey", "instKey", "svc", "id");
        assertEquals("OK", out);

        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList(),
                eq("svc"), eq("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeCleanupExpiredInstancesReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaClean2");

        doThrow(new RedisException("NOSCRIPT"))
                .doReturn(List.of("id1", "id2"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                        any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<String> out = exec.executeCleanupExpiredInstances("hb", "svc", 1L, 2L, "pfx");
        assertEquals(List.of("id1", "id2"), out);

        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                eq("svc"), eq("1"), eq("2"), eq("pfx"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeCleanupExpiredInstancesWithSnapshotsReloadsOnNoScript() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7", "shaCleanSnap2");

        doThrow(new RedisException("NOSCRIPT"))
                .doReturn(List.of("id1", "{\"json\":1}"))
                .when(script)
                .evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                        any(), any(), any(), any());

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<Object> out = exec.executeCleanupExpiredInstancesWithSnapshots("hb", "svc", 1L, 2L, "pfx");
        assertEquals(List.of("id1", "{\"json\":1}"), out);

        verify(script, times(2)).evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                eq("svc"), eq("1"), eq("2"), eq("pfx"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetInstancesByFiltersBuildsCombinedJsonWhenMetricsPresent() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7");

        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of("i1"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<String> out = exec.executeGetInstancesByFilters(
                "hb", "pfx", "svc", 1L, 2L,
                "{\"region\":\"us\"}",
                "{\"cpu:\">\":\"0.5\"}"
        );
        assertEquals(List.of("i1"), out);

        // Verify last arg (filters json) is combined structure
        verify(script).evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"),
                eq("{\"metadata\":{\"region\":\"us\"},\"metrics\":{\"cpu:\">\":\"0.5\"}}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGetInstancesByFiltersKeepsLegacyFormatWhenOnlyMetadata() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7");

        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of("i1"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        String metadataJson = "{\"region\":\"us\"}";
        List<String> out = exec.executeGetInstancesByFilters(
                "hb", "pfx", "svc", 1L, 2L,
                metadataJson,
                ""
        );
        assertEquals(List.of("i1"), out);

        verify(script).evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                eq("pfx"), eq("svc"), eq("1"), eq("2"),
                eq(metadataJson));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedExecuteGetInstancesByMetadataDelegates() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redisson.getScript(StringCodec.INSTANCE)).thenReturn(script);

        when(script.scriptLoad(anyString()))
                .thenReturn("sha1", "sha2", "sha3", "sha4", "sha5", "sha6", "sha7");

        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.MULTI), anyList(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of("i1"));

        RegistryLuaScriptExecutor exec = new RegistryLuaScriptExecutor(redisson);
        List<String> out = exec.executeGetInstancesByMetadata("hb", "pfx", "svc", 1L, 2L, "{\"region\":\"us\"}");
        assertEquals(List.of("i1"), out);
    }
}
