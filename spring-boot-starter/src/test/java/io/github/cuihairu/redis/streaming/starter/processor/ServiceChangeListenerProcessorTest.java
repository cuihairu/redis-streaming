package io.github.cuihairu.redis.streaming.starter.processor;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ServiceChangeListenerProcessor
 */
class ServiceChangeListenerProcessorTest {

    private ServiceDiscovery mockServiceDiscovery;
    private ServiceChangeListenerProcessor processor;

    @BeforeEach
    void setUp() {
        mockServiceDiscovery = mock(ServiceDiscovery.class);
        processor = new ServiceChangeListenerProcessor(mockServiceDiscovery);
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidServiceDiscovery() {
        assertNotNull(processor);
    }

    @Test
    void testConstructorWithNullServiceDiscovery() {
        assertDoesNotThrow(() -> new ServiceChangeListenerProcessor(null));
    }

    // ===== postProcessAfterInitialization Tests =====

    @Test
    void testPostProcessAfterInitializationWithNoAnnotation() {
        TestBeanWithoutAnnotation bean = new TestBeanWithoutAnnotation();

        Object result = processor.postProcessAfterInitialization(bean, "testBean");

        assertSame(bean, result);
        verifyNoInteractions(mockServiceDiscovery);
    }

    @Test
    void testPostProcessAfterInitializationWithAnnotation() {
        TestBeanWithAnnotation bean = new TestBeanWithAnnotation();

        Object result = processor.postProcessAfterInitialization(bean, "testBean");

        assertSame(bean, result);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), any());
    }

    @Test
    void testPostProcessAfterInitializationWithMultipleServices() {
        TestBeanWithMultipleServices bean = new TestBeanWithMultipleServices();

        Object result = processor.postProcessAfterInitialization(bean, "testBean");

        assertSame(bean, result);
        verify(mockServiceDiscovery, times(2)).subscribe(any(), any());
    }

    @Test
    void testPostProcessAfterInitializationWithActions() {
        TestBeanWithActions bean = new TestBeanWithActions();

        Object result = processor.postProcessAfterInitialization(bean, "testBean");

        assertSame(bean, result);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), any());
    }

    @Test
    void testPostProcessAfterInitializationWithNullBean() {
        // AopProxyUtils.ultimateTargetClass throws IllegalArgumentException for null
        assertThrows(IllegalArgumentException.class, () -> {
            processor.postProcessAfterInitialization(null, "nullBean");
        });
    }

    @Test
    void testPostProcessAfterInitializationWithEmptyBeanName() {
        TestBeanWithAnnotation bean = new TestBeanWithAnnotation();

        Object result = processor.postProcessAfterInitialization(bean, "");

        assertSame(bean, result);
    }

    // ===== Listener Invocation Tests =====

    @Test
    void testListenerInvocationWithFullParameters() throws Exception {
        TestBeanWithFullParameters bean = new TestBeanWithFullParameters();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        // Simulate service change
        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();
        allInstances.add(mockInstance);

        captor.getValue().onServiceChange("test-service", ServiceChangeAction.ADDED, mockInstance, allInstances);

        assertTrue(bean.invoked);
        assertEquals("test-service", bean.lastServiceName);
        assertEquals(ServiceChangeAction.ADDED, bean.lastAction);
        assertSame(mockInstance, bean.lastInstance);
        assertSame(allInstances, bean.lastAllInstances);
    }

    @Test
    void testListenerInvocationWithSimplifiedParameters() throws Exception {
        TestBeanWithSimplifiedParameters bean = new TestBeanWithSimplifiedParameters();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        // Simulate service change
        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        captor.getValue().onServiceChange("test-service", ServiceChangeAction.ADDED, mockInstance, allInstances);

        assertTrue(bean.invoked);
        assertSame(mockInstance, bean.lastInstance);
    }

    @Test
    void testListenerInvocationWithActionAndInstance() throws Exception {
        TestBeanWithActionAndInstance bean = new TestBeanWithActionAndInstance();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        // Simulate service change
        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        captor.getValue().onServiceChange("test-service", ServiceChangeAction.REMOVED, mockInstance, allInstances);

        assertTrue(bean.invoked);
        assertEquals(ServiceChangeAction.REMOVED, bean.lastAction);
        assertSame(mockInstance, bean.lastInstance);
    }

    @Test
    void testListenerFiltersByServiceName() throws Exception {
        TestBeanWithServiceFilter bean = new TestBeanWithServiceFilter();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("specific-service"), captor.capture());

        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        // Wrong service name - should not invoke
        captor.getValue().onServiceChange("other-service", ServiceChangeAction.ADDED, mockInstance, allInstances);
        assertFalse(bean.invoked);

        // Correct service name - should invoke
        captor.getValue().onServiceChange("specific-service", ServiceChangeAction.ADDED, mockInstance, allInstances);
        assertTrue(bean.invoked);
    }

    @Test
    void testListenerFiltersByAction() throws Exception {
        TestBeanWithActionFilter bean = new TestBeanWithActionFilter();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        // Wrong action - should not invoke
        captor.getValue().onServiceChange("test-service", ServiceChangeAction.REMOVED, mockInstance, allInstances);
        assertFalse(bean.invoked);

        // Correct action - should invoke
        captor.getValue().onServiceChange("test-service", ServiceChangeAction.ADDED, mockInstance, allInstances);
        assertTrue(bean.invoked);
    }

    @Test
    void testListenerWithActionCaseInsensitive() throws Exception {
        TestBeanWithActionFilter bean = new TestBeanWithActionFilter();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        // Lowercase action should match
        captor.getValue().onServiceChange("test-service", ServiceChangeAction.ADDED, mockInstance, allInstances);
        assertTrue(bean.invoked);
    }

    @Test
    void testListenerWithStringActionParameter() throws Exception {
        TestBeanWithStringAction bean = new TestBeanWithStringAction();
        processor.postProcessAfterInitialization(bean, "testBean");

        ArgumentCaptor<io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener> captor =
                ArgumentCaptor.forClass(io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener.class);
        verify(mockServiceDiscovery).subscribe(eq("test-service"), captor.capture());

        ServiceInstance mockInstance = mock(ServiceInstance.class);
        List<ServiceInstance> allInstances = new ArrayList<>();

        captor.getValue().onServiceChange("test-service", ServiceChangeAction.ADDED, mockInstance, allInstances);

        assertTrue(bean.invoked);
        assertEquals("test-service", bean.lastServiceName);
        assertEquals("added", bean.lastActionString);
    }

    // ===== Test Bean Classes =====

    static class TestBeanWithoutAnnotation {
        // No @ServiceChangeListener annotation
    }

    static class TestBeanWithAnnotation {
        @ServiceChangeListener(services = "test-service")
        void onServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
            // Listener method
        }
    }

    static class TestBeanWithMultipleServices {
        @ServiceChangeListener(services = {"service1", "service2"})
        void onServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
            // Listener method
        }
    }

    static class TestBeanWithActions {
        @ServiceChangeListener(services = "test-service", actions = {"added", "updated"})
        void onServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
            // Listener method
        }
    }

    static class TestBeanWithFullParameters {
        boolean invoked = false;
        String lastServiceName;
        ServiceChangeAction lastAction;
        ServiceInstance lastInstance;
        List<ServiceInstance> lastAllInstances;

        @ServiceChangeListener(services = "test-service")
        void onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances) {
            invoked = true;
            lastServiceName = serviceName;
            lastAction = action;
            lastInstance = instance;
            lastAllInstances = allInstances;
        }
    }

    static class TestBeanWithSimplifiedParameters {
        boolean invoked = false;
        ServiceInstance lastInstance;

        @ServiceChangeListener(services = "test-service")
        void onServiceChange(ServiceInstance instance) {
            invoked = true;
            lastInstance = instance;
        }
    }

    static class TestBeanWithActionAndInstance {
        boolean invoked = false;
        ServiceChangeAction lastAction;
        ServiceInstance lastInstance;

        @ServiceChangeListener(services = "test-service")
        void onServiceChange(ServiceChangeAction action, ServiceInstance instance) {
            invoked = true;
            lastAction = action;
            lastInstance = instance;
        }
    }

    static class TestBeanWithServiceFilter {
        boolean invoked = false;

        @ServiceChangeListener(services = "specific-service")
        void onServiceChange(ServiceInstance instance) {
            invoked = true;
        }
    }

    static class TestBeanWithActionFilter {
        boolean invoked = false;

        @ServiceChangeListener(services = "test-service", actions = {"added", "updated"})
        void onServiceChange(ServiceInstance instance) {
            invoked = true;
        }
    }

    static class TestBeanWithStringAction {
        boolean invoked = false;
        String lastServiceName;
        String lastActionString;

        @ServiceChangeListener(services = "test-service")
        void onServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
            invoked = true;
            lastServiceName = serviceName;
            lastActionString = action;
        }
    }
}
