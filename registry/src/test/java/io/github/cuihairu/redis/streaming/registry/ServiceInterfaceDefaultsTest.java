package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers ServiceInstance and NamingService default methods that implementations
 * (DefaultServiceInstance, RedisNamingService) always override.
 */
class ServiceInterfaceDefaultsTest {

    /** Minimal instance that keeps every ServiceInstance default method intact. */
    private static final class MinimalInstance implements ServiceInstance {
        @Override
        public String getServiceName() {
            return "svc";
        }

        @Override
        public String getHost() {
            return "127.0.0.1";
        }

        @Override
        public Map<String, String> getMetadata() {
            return Collections.emptyMap();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    @Test
    void serviceInstanceDefaultMethods() {
        ServiceInstance ins = new MinimalInstance();

        assertEquals(StandardProtocol.HTTP, ins.getProtocol());
        assertEquals(StandardProtocol.HTTP.getDefaultPort(), ins.getPort());
        assertEquals(1, ins.getWeight());
        assertNull(ins.getLastHeartbeatTime());
        ins.setLastHeartbeatTime(LocalDateTime.now()); // default no-op
        assertNull(ins.getRegistrationTime());
        assertTrue(ins.isEphemeral());
    }

    @Test
    void namingServiceDefaultGetHealthyInstancesDelegatesToGetInstances() {
        NamingService naming = mock(NamingService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ServiceInstance ins = new MinimalInstance();
        when(naming.getInstances("svc", true)).thenReturn(List.of(ins));

        List<ServiceInstance> out = naming.getHealthyInstances("svc");

        assertEquals(List.of(ins), out);
        verify(naming).getInstances("svc", true);
    }
}
