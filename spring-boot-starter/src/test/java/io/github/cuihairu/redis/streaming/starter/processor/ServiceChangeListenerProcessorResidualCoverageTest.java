package io.github.cuihairu.redis.streaming.starter.processor;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Residual coverage for ServiceChangeListenerProcessor: empty services/actions branches and
 * the invokeListenerMethod unsupported-parameter failure swallowed by the listener lambda.
 */
class ServiceChangeListenerProcessorResidualCoverageTest {

    static class EmptyServicesBean {
        @ServiceChangeListener
        public void onEverything(String serviceName, ServiceChangeAction action,
                                 ServiceInstance instance, List<ServiceInstance> allInstances) {
        }
    }

    static class EmptyActionsBean {
        @ServiceChangeListener(services = {"svc"}, actions = {})
        public void onEverything(String serviceName, ServiceChangeAction action,
                                 ServiceInstance instance, List<ServiceInstance> allInstances) {
        }
    }

    static class UnsupportedParamBean {
        @ServiceChangeListener(services = {"svc"})
        public void onBad(int unsupported) {
        }
    }

    private ServiceDiscovery discovery;
    private ServiceChangeListenerProcessor processor;

    @BeforeEach
    void setUp() {
        discovery = mock(ServiceDiscovery.class);
        processor = new ServiceChangeListenerProcessor(discovery);
    }

    @Test
    void emptyServicesLogsGlobalWarningWithoutSubscribing() {
        processor.postProcessAfterInitialization(new EmptyServicesBean(), "empty");
        verify(discovery, never()).subscribe(anyString(),
                any(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class));
    }

    @Test
    void emptyActionsSubscribesWithAllLabel() {
        processor.postProcessAfterInitialization(new EmptyActionsBean(), "emptyActions");
        verify(discovery).subscribe(eq("svc"),
                any(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class));
    }

    @Test
    void unsupportedListenerParameterFailuresAreSwallowed() {
        processor.postProcessAfterInitialization(new UnsupportedParamBean(), "bad");
        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(discovery).subscribe(eq("svc"), captor.capture());

        ServiceInstance instance = mock(ServiceInstance.class);
        assertDoesNotThrow(() -> captor.getValue().onServiceChange(
                "svc", ServiceChangeAction.ADDED, instance, List.of(instance)));
    }
}
