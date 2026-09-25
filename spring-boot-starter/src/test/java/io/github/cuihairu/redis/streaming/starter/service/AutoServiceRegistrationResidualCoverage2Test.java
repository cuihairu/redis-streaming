package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.core.utils.InstanceIdGenerator;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.lang.reflect.Field;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@link AutoServiceRegistration} branches when driven as a plain object:
 * explicit vs. generated instance attributes and the destroy() deregistration guard.
 */
@Timeout(30)
class AutoServiceRegistrationResidualCoverage2Test {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AutoServiceRegistration.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static AutoServiceRegistration registration(NamingService namingService,
                                                        RedisStreamingProperties props) throws Exception {
        AutoServiceRegistration registration = new AutoServiceRegistration();
        setField(registration, "namingService", namingService);
        setField(registration, "properties", props);
        setField(registration, "serverPort", 8080);
        setField(registration, "applicationName", "app");
        return registration;
    }

    private static RedisStreamingProperties props() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRegistry().getInstance().setServiceName("svc");
        props.getRegistry().getInstance().setEphemeral(false);
        return props;
    }

    @Test
    void explicitInstanceAttributesWin() throws Exception {
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = props();
        props.getRegistry().getInstance().setInstanceId("id-1");
        props.getRegistry().getInstance().setHost("1.2.3.4");
        props.getRegistry().getInstance().setPort(1234);

        AutoServiceRegistration registration = registration(namingService, props);
        registration.onApplicationEvent(mock(ApplicationReadyEvent.class));

        ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
        verify(namingService).register(captor.capture());
        ServiceInstance instance = captor.getValue();
        assertEquals("id-1", instance.getInstanceId());
        assertEquals("1.2.3.4", instance.getHost());
        assertEquals(1234, instance.getPort());
    }

    @Test
    void blankInstanceAttributesFallBackToDefaults() throws Exception {
        NamingService namingService = mock(NamingService.class);
        RedisStreamingProperties props = props();
        props.getRegistry().getInstance().setInstanceId("  ");
        props.getRegistry().getInstance().setHost(" ");
        props.getRegistry().getInstance().setPort(0);

        try (MockedStatic<InetAddress> inet = mockStatic(InetAddress.class)) {
            InetAddress localHost = mock(InetAddress.class);
            when(localHost.getHostAddress()).thenReturn("10.0.0.9");
            inet.when(InetAddress::getLocalHost).thenReturn(localHost);

            AutoServiceRegistration registration = registration(namingService, props);
            registration.onApplicationEvent(mock(ApplicationReadyEvent.class));

            ArgumentCaptor<ServiceInstance> captor = ArgumentCaptor.forClass(ServiceInstance.class);
            verify(namingService).register(captor.capture());
            ServiceInstance instance = captor.getValue();
            assertEquals(InstanceIdGenerator.generateInstanceId("svc", 8080), instance.getInstanceId());
            assertEquals("10.0.0.9", instance.getHost());
            assertEquals(8080, instance.getPort());
        }
    }

    @Test
    void destroyDeregistersRegisteredInstance() throws Exception {
        NamingService namingService = mock(NamingService.class);
        AutoServiceRegistration registration = registration(namingService, props());
        registration.onApplicationEvent(mock(ApplicationReadyEvent.class));

        registration.destroy();

        verify(namingService).deregister(any(ServiceInstance.class));
    }

    @Test
    void destroyWithoutRegistrationIsQuiet() throws Exception {
        NamingService namingService = mock(NamingService.class);
        AutoServiceRegistration registration = registration(namingService, props());

        registration.destroy();

        verify(namingService, never()).deregister(any(ServiceInstance.class));
    }
}
