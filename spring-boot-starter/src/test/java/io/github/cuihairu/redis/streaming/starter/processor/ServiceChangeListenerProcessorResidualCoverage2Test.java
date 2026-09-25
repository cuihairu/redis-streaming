package io.github.cuihairu.redis.streaming.starter.processor;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers the remaining {@link ServiceChangeListenerProcessor} branches: service/action filter
 * drops and the flexible String-parameter mapping of listener methods.
 */
class ServiceChangeListenerProcessorResidualCoverage2Test {

    static class Bean {
        String hits = "";

        @io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener(services = "svc-a", actions = {"added"})
        public void onFull(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> all) {
            hits += "full:" + serviceName + ":" + action + ";";
        }

        @io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener(services = "svc-a", actions = {"added"})
        public void onMixed(ServiceInstance instance, String extra) {
            hits += "mixed:" + extra + ";";
        }
    }

    @SuppressWarnings("unchecked")
    private static ServiceChangeListener captureListener(ServiceDiscovery discovery) {
        ArgumentCaptor<ServiceChangeListener> captor = ArgumentCaptor.forClass(ServiceChangeListener.class);
        verify(discovery, times(2)).subscribe(eq("svc-a"), captor.capture());
        List<ServiceChangeListener> listeners = captor.getAllValues();
        assertEquals(2, listeners.size());
        // fire both: each listener maps to one annotated method
        return (ServiceChangeListener) (serviceName, action, instance, all) -> {
            for (ServiceChangeListener l : listeners) {
                l.onServiceChange(serviceName, action, instance, all);
            }
        };
    }

    @Test
    void listenerMethodsReceiveMappedArguments() {
        Bean bean = new Bean();
        ServiceDiscovery discovery = mock(ServiceDiscovery.class);
        new ServiceChangeListenerProcessor(discovery).postProcessAfterInitialization(bean, "bean");

        ServiceChangeListener both = captureListener(discovery);
        ServiceInstance instance = mock(ServiceInstance.class);
        both.onServiceChange("svc-a", ServiceChangeAction.ADDED, instance, List.of(instance));

        assertTrue(bean.hits.contains("full:svc-a:added;"), "second String parameter maps to the action name");
        assertTrue(bean.hits.contains("mixed:svc-a;"), "String after a non-String parameter maps to the service name");
    }

    @Test
    void foreignServiceNameIsFilteredOut() {
        Bean bean = new Bean();
        ServiceDiscovery discovery = mock(ServiceDiscovery.class);
        new ServiceChangeListenerProcessor(discovery).postProcessAfterInitialization(bean, "bean");

        ServiceChangeListener both = captureListener(discovery);
        both.onServiceChange("svc-b", ServiceChangeAction.ADDED, mock(ServiceInstance.class), List.of());

        assertEquals("", bean.hits);
    }

    @Test
    void unlistedActionIsFilteredOut() {
        Bean bean = new Bean();
        ServiceDiscovery discovery = mock(ServiceDiscovery.class);
        new ServiceChangeListenerProcessor(discovery).postProcessAfterInitialization(bean, "bean");

        ServiceChangeListener both = captureListener(discovery);
        both.onServiceChange("svc-a", ServiceChangeAction.REMOVED, mock(ServiceInstance.class), List.of());

        assertEquals("", bean.hits);
    }
}
