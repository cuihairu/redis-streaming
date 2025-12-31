package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AutoServiceRegistrationTest {

    @Test
    void skipsWhenNamingServiceMissing() {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        reg.onApplicationEvent(mock(ApplicationReadyEvent.class));
    }

    @Test
    void skipsWhenRegistryDisabled() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(false);

        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);

        reg.onApplicationEvent(mock(ApplicationReadyEvent.class));
        verify(namingService, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registersEphemeralInstanceAndUsesResolvedPortForInstanceId() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getRegistry().setEnabled(true);
        props.getRegistry().setHeartbeatInterval(1);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setPort(12345);
        props.getRegistry().getInstance().setProtocol("tcp");
        props.getRegistry().getInstance().setInstanceId(null);
        props.getRegistry().getInstance().setMetadata(Map.of("k", "v"));

        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);
        setField(reg, "serverPort", 8080);
        setField(reg, "applicationName", "app");

        reg.onApplicationEvent(mock(ApplicationReadyEvent.class));

        ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(namingService).register(captor.capture());
        ServiceInstance instance = captor.getValue();

        assertEquals("app", instance.getServiceName());
        assertEquals("127.0.0.1", instance.getHost());
        assertEquals(12345, instance.getPort());
        assertEquals(StandardProtocol.TCP, instance.getProtocol());
        assertTrue(instance.isEphemeral());
        assertTrue(instance.getInstanceId().endsWith(":12345"));
        assertEquals("v", instance.getMetadata().get("k"));
        assertEquals("app", instance.getMetadata().get("application.name"));
        assertEquals("8080", instance.getMetadata().get("server.port"));
        assertNotNull(instance.getMetadata().get("startup.time"));

        reg.destroy();
        verify(namingService).deregister(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownProtocolFallsBackToHttpAndPersistentInstanceSkipsHeartbeat() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getRegistry().setEnabled(true);
        props.getRegistry().setHeartbeatInterval(1);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setPort(10086);
        props.getRegistry().getInstance().setProtocol("smtp");
        props.getRegistry().getInstance().setEphemeral(false);

        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);
        setField(reg, "serverPort", 8080);
        setField(reg, "applicationName", "app");

        reg.onApplicationEvent(mock(ApplicationReadyEvent.class));

        ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(namingService).register(captor.capture());
        ServiceInstance instance = captor.getValue();
        Protocol protocol = instance.getProtocol();
        assertEquals(StandardProtocol.HTTP, protocol);
        assertEquals(false, instance.isEphemeral());

        reg.destroy();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}

