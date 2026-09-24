package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Deterministic matrix for AutoServiceRegistration resolve* helper branches. */
class AutoServiceRegistrationResolveMatrixCoverageTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test
    void resolveMatrixCoversDefaultsAndOverrides() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        setField(reg, "properties", props);
        setField(reg, "serverPort", 7007);
        setField(reg, "applicationName", "matrix-app");

        // resolveServiceName: placeholder default resolves to applicationName; explicit wins
        Object nameDefault = invoke(reg, "resolveServiceName", new Class[]{String.class}, "${spring.application.name}");
        assertEquals("matrix-app", nameDefault);
        Object nameExplicit = invoke(reg, "resolveServiceName", new Class[]{String.class}, "explicit-svc");
        assertEquals("explicit-svc", nameExplicit);

        // resolveInstanceId: explicit id, generated fallback
        Object idExplicit = invoke(reg, "resolveInstanceId",
                new Class[]{String.class, String.class, int.class}, "id-1", "svc", 8080);
        assertEquals("id-1", idExplicit);
        Object idGenerated = invoke(reg, "resolveInstanceId",
                new Class[]{String.class, String.class, int.class}, null, "svc", 8080);
        assertNotNull(idGenerated);

        // resolveHost: configured value and fallback resolution
        Object hostConfigured = invoke(reg, "resolveHost", new Class[]{String.class}, "10.0.0.9");
        assertEquals("10.0.0.9", hostConfigured);
        Object hostFallback = invoke(reg, "resolveHost", new Class[]{String.class}, (Object) null);
        assertNotNull(hostFallback);

        // resolvePort: configured value and serverPort fallback
        Object portConfigured = invoke(reg, "resolvePort", new Class[]{Integer.class}, 9000);
        assertEquals(9000, portConfigured);
        Object portFallback = invoke(reg, "resolvePort", new Class[]{Integer.class}, (Object) null);
        assertEquals(7007, portFallback);

        // resolveProtocol: known names and unknown fallback
        Object tcp = invoke(reg, "resolveProtocol", new Class[]{String.class}, "tcp");
        assertTrue(tcp instanceof Protocol);
        Object http = invoke(reg, "resolveProtocol", new Class[]{String.class}, "http");
        assertTrue(http instanceof Protocol);
        Object unknown = invoke(reg, "resolveProtocol", new Class[]{String.class}, "weird-proto");
        assertTrue(unknown instanceof Protocol);

        // resolveEphemeral: null default true
        Object ephemeral = invoke(reg, "resolveEphemeral", new Class[]{Boolean.class}, (Object) null);
        assertEquals(Boolean.TRUE, ephemeral);
        Object notEphemeral = invoke(reg, "resolveEphemeral", new Class[]{Boolean.class}, Boolean.FALSE);
        assertEquals(Boolean.FALSE, notEphemeral);
    }

    @Test
    void destroyWithHeartbeatExecutorIsSafe() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        props.getRegistry().setHeartbeatInterval(1);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setPort(65500);
        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);
        setField(reg, "serverPort", 65500);
        setField(reg, "applicationName", "destroy-app");

        reg.onApplicationEvent(mock(org.springframework.boot.context.event.ApplicationReadyEvent.class));
        Thread.sleep(300);
        reg.destroy();
        reg.destroy(); // idempotent
    }
}
