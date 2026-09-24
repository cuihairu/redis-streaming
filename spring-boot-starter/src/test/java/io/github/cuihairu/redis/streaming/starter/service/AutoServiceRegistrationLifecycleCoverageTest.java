package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers AutoServiceRegistration resolve* helpers, heartbeat lambda and destroy()
 * beyond what AutoServiceRegistrationTest already exercises.
 */
class AutoServiceRegistrationLifecycleCoverageTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void resolveFallsBackToDefaultsAndGeneratesIds() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().setEnabled(true);
        props.getRegistry().setHeartbeatInterval(1);
        props.getRegistry().getInstance().setServiceName(null);
        props.getRegistry().getInstance().setInstanceId(null);
        props.getRegistry().getInstance().setHost("127.0.0.1");
        props.getRegistry().getInstance().setPort(null);
        props.getRegistry().getInstance().setEphemeral(null);

        setField(reg, "namingService", namingService);
        setField(reg, "properties", props);
        setField(reg, "serverPort", 61234);
        setField(reg, "applicationName", "demo-app");

        reg.onApplicationEvent(mock(ApplicationReadyEvent.class));

        ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(namingService, atLeastOnce()).register(captor.capture());
        ServiceInstance instance = captor.getValue();
        assertEquals("demo-app", instance.getServiceName());
        assertEquals(61234, instance.getPort());
        assertNotNull(instance.getInstanceId());
        assertTrue(instance.getInstanceId().contains("demo-app") || instance.getInstanceId().contains("61234"),
                instance.getInstanceId());

        // let the heartbeat fire at least once, then tear down
        Thread.sleep(1500);
        reg.destroy();
    }

    @Test
    void destroyWithoutHeartbeatIsSafe() {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        reg.destroy();
    }

    @Test
    void resolveHostUsesConfiguredValue() throws Exception {
        AutoServiceRegistration reg = new AutoServiceRegistration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setHost("10.1.2.3");
        setField(reg, "properties", props);

        java.lang.reflect.Method m = AutoServiceRegistration.class
                .getDeclaredMethod("resolveHost", String.class);
        m.setAccessible(true);
        assertEquals("10.1.2.3", m.invoke(reg, "10.1.2.3"));
    }
}
