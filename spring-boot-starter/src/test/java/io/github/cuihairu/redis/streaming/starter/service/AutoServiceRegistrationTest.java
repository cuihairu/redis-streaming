package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.lang.reflect.Field;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AutoServiceRegistrationTest {

    @Test
    void skipsWhenNamingServiceMissing() {
        AutoServiceRegistration registration = new AutoServiceRegistration();
        setField(registration, "properties", enabledProps());
        assertDoesNotThrow(() -> registration.onApplicationEvent(null));
    }

    @Test
    void skipsWhenRegistryDisabled() {
        NamingService namingService = mock(NamingService.class);
        AutoServiceRegistration registration = new AutoServiceRegistration();
        RedisStreamingProperties props = enabledProps();
        props.getRegistry().setEnabled(false);

        setField(registration, "namingService", namingService);
        setField(registration, "properties", props);

        registration.onApplicationEvent(null);
        verifyNoInteractions(namingService);
    }

    @Test
    void registersAndDeregistersPersistentInstance() {
        NamingService namingService = mock(NamingService.class);

        AutoServiceRegistration registration = new AutoServiceRegistration();
        RedisStreamingProperties props = enabledProps();
        props.getRegistry().getInstance().setEphemeral(false);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setServiceName("orders");
        props.getRegistry().getInstance().setInstanceId(null);
        props.getRegistry().getInstance().setPort(null);
        props.getRegistry().getInstance().setProtocol("unknown"); // exercise fallback

        setField(registration, "namingService", namingService);
        setField(registration, "properties", props);
        setField(registration, "serverPort", 18080);
        setField(registration, "applicationName", "app");

        registration.onApplicationEvent(null);

        ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(namingService).register(captor.capture());

        ServiceInstance instance = captor.getValue();
        assertEquals("orders", instance.getServiceName());
        assertEquals("127.0.0.1", instance.getHost());
        assertEquals(18080, instance.getPort());
        assertNotNull(instance.getInstanceId());
        assertTrue(instance.getInstanceId().contains("orders"));
        assertTrue(instance.getInstanceId().contains("18080"));

        assertNotNull(instance.getMetadata().get("application.name"));
        assertNotNull(instance.getMetadata().get("server.port"));
        assertNotNull(instance.getMetadata().get("startup.time"));

        registration.destroy();
        verify(namingService).deregister(instance);
        verify(namingService, never()).sendHeartbeat(instance);
    }

    private static RedisStreamingProperties enabledProps() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        props.getRegistry().setAutoRegister(true);
        props.getRegistry().setHeartbeatInterval(30);
        props.getRegistry().getInstance().setServiceName("test-service");
        props.getRegistry().getInstance().setPort(8080);
        props.getRegistry().getInstance().setProtocol("http");
        props.getRegistry().getInstance().setEphemeral(true);
        props.getRegistry().getInstance().setEnabled(true);
        props.getRegistry().getInstance().setWeight(1);
        props.getRegistry().getInstance().setMetadata(new HashMap<>());
        return props;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
