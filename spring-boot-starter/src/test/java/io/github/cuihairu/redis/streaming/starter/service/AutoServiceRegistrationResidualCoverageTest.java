package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for AutoServiceRegistration: registration/heartbeat failure branches,
 * destroy() shutdownNow + deregistration catch, and resolveProtocol null/https paths.
 */
class AutoServiceRegistrationResidualCoverageTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AutoServiceRegistration.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = AutoServiceRegistration.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = AutoServiceRegistration.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static AutoServiceRegistration registration(NamingService namingService) throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setPort(65510);
        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);
        setField(reg, "serverPort", 65510);
        setField(reg, "applicationName", "residual-app");
        return reg;
    }

    @Test
    void onApplicationEventSwallowsRegistrationFailure() throws Exception {
        NamingService namingService = mock(NamingService.class);
        doThrow(new IllegalStateException("register boom")).when(namingService).register(any(ServiceInstance.class));
        AutoServiceRegistration reg = registration(namingService);
        assertDoesNotThrow(() -> reg.onApplicationEvent(
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class)));
    }

    @Test
    void startHeartbeatIsNoopWithoutCurrentInstance() throws Exception {
        AutoServiceRegistration reg = registration(mock(NamingService.class));
        assertDoesNotThrow(() -> invoke(reg, "startHeartbeat", new Class<?>[]{}));
    }

    @Test
    void heartbeatTaskSwallowsSendFailures() throws Exception {
        NamingService namingService = mock(NamingService.class);
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            throw new IllegalStateException("heartbeat boom");
        }).when(namingService).sendHeartbeat(any(ServiceInstance.class));

        AutoServiceRegistration reg = registration(namingService);
        ((RedisStreamingProperties) getField(reg, "properties")).getRegistry().setHeartbeatInterval(1);
        reg.onApplicationEvent(mock(org.springframework.boot.context.event.ApplicationReadyEvent.class));
        assertTrue(sent.await(4, TimeUnit.SECONDS), "heartbeat task should run and fail silently");
        reg.destroy();
    }

    @Test
    void destroyForcesShutdownNowAndCatchesDeregisterFailure() throws Exception {
        NamingService namingService = mock(NamingService.class);
        doThrow(new IllegalStateException("deregister boom")).when(namingService).deregister(any(ServiceInstance.class));
        AutoServiceRegistration reg = registration(namingService);
        setField(reg, "currentInstance", mock(ServiceInstance.class));

        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        setField(reg, "heartbeatExecutor", executor);

        assertDoesNotThrow(reg::destroy);
        verify(executor).shutdownNow();
        verify(namingService).deregister(any(ServiceInstance.class));
    }

    @Test
    void resolveProtocolHandlesNullAndHttps() throws Exception {
        AutoServiceRegistration reg = registration(mock(NamingService.class));
        Object nullProto = invoke(reg, "resolveProtocol", new Class<?>[]{String.class}, (Object) null);
        assertEquals(StandardProtocol.HTTP, nullProto);
        Object https = invoke(reg, "resolveProtocol", new Class<?>[]{String.class}, "https");
        assertEquals(StandardProtocol.HTTPS, https);
        assertTrue(https instanceof Protocol);
    }
}
