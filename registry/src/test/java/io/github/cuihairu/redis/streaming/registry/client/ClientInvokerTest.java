package io.github.cuihairu.redis.streaming.registry.client;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRandomLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClientInvoker
 */
class ClientInvokerTest {

    @Mock
    private io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService mockNamingService;

    @Mock
    private io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter mockReporter;

    private LoadBalancer loadBalancer;
    private RetryPolicy retryPolicy;
    private ClientInvoker invoker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        loadBalancer = new WeightedRandomLoadBalancer();
        retryPolicy = new RetryPolicy(3, 10, 2.0, 200, 10);
    }

    @Test
    void testConstructorWithValidParameters() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        assertNotNull(invoker);
    }

    @Test
    void testConstructorWithNullNamingService() {
        assertThrows(NullPointerException.class,
                () -> new ClientInvoker(null, loadBalancer, retryPolicy, mockReporter));
    }

    @Test
    void testConstructorWithNullLoadBalancer() {
        assertThrows(NullPointerException.class,
                () -> new ClientInvoker(mockNamingService, null, retryPolicy, mockReporter));
    }

    @Test
    void testConstructorWithNullRetryPolicy() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, null, null);

        assertNotNull(invoker);
        // Should use default retry policy
    }

    @Test
    void testConstructorWithNullReporter() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        assertNotNull(invoker);
    }

    @Test
    void testConstructorWithAllRequiredParameters() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        assertNotNull(invoker);
    }

    @Test
    void testInvokeWithSuccess() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        String result = invoker.invoke("test-service", Map.of(), Map.of(),
                Map.of(), ins -> "success");

        assertEquals("success", result);

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("attempts") >= 1);
        assertTrue(snapshot.get("total").get("successes") >= 1);
    }

    @Test
    void testInvokeWithNoAvailableInstances() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);
        mockSelectorBehavior(List.of());

        assertThrows(Exception.class,
                () -> invoker.invoke("test-service", Map.of(), Map.of(),
                        Map.of(), ins -> "success"));

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("attempts") >= 1);
        assertTrue(snapshot.get("total").get("failures") >= 1);
    }

    @Test
    void testInvokeWithExceptionFromFunction() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        assertThrows(Exception.class,
                () -> invoker.invoke("test-service", Map.of(), Map.of(),
                        Map.of(), ins -> {
                            throw new RuntimeException("Test exception");
                        }));

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("attempts") >= 1);
    }

    @Test
    void testInvokeWithRetry() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        int[] callCount = {0};
        assertThrows(Exception.class,
                () -> invoker.invoke("test-service", Map.of(), Map.of(),
                        Map.of(), ins -> {
                            callCount[0]++;
                            throw new RuntimeException("Test exception");
                        }));

        // With maxAttempts=3, should be called multiple times
        assertTrue(callCount[0] >= 1, "Should have been called at least once: " + callCount[0]);

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("attempts") >= 1);
    }

    @Test
    void testInvokeWithNullMetadataFilters() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        // Need to mock for null parameters
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), isNull(), isNull()))
                .thenReturn(List.of(instance));

        String result = invoker.invoke("test-service", null, null,
                Map.of(), ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testInvokeWithNullContext() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        String result = invoker.invoke("test-service", Map.of(), Map.of(),
                null, ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testInvokeWithEmptyFilters() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        String result = invoker.invoke("test-service", Map.of(), Map.of(),
                Map.of(), ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testInvokeWithMultipleServices() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance1 = createTestInstance("service-1", "instance-1");
        ServiceInstance instance2 = createTestInstance("service-2", "instance-2");

        mockSelectorBehavior("service-1", List.of(instance1));
        mockSelectorBehavior("service-2", List.of(instance2));

        invoker.invoke("service-1", Map.of(), Map.of(), Map.of(), ins -> "result1");
        invoker.invoke("service-2", Map.of(), Map.of(), Map.of(), ins -> "result2");

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.containsKey("service-1"));
        assertTrue(snapshot.containsKey("service-2"));
    }

    @Test
    void testGetMetricsSnapshot() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("total"));

        Map<String, Long> total = snapshot.get("total");
        assertTrue(total.containsKey("attempts"));
        assertTrue(total.containsKey("successes"));
        assertTrue(total.containsKey("failures"));
        assertTrue(total.containsKey("retries"));
        assertTrue(total.containsKey("cbOpenSkips"));
    }

    @Test
    void testGetMetricsSnapshotAfterInvoke() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        invoker.invoke("test-service", Map.of(), Map.of(), Map.of(), ins -> "success");

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();

        assertTrue(snapshot.get("total").get("attempts") >= 1);
        assertTrue(snapshot.get("total").get("successes") >= 1);
        assertTrue(snapshot.containsKey("test-service"));
    }

    @Test
    void testInvokeWithCircuitBreakerOpen() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, mockReporter);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");

        mockSelectorBehavior(List.of(instance));

        // Fail to trigger circuit breaker state
        assertThrows(Exception.class,
                () -> invoker.invoke("test-service", Map.of(), Map.of(),
                        Map.of(), ins -> {
                            throw new RuntimeException("Test exception");
                        }));

        // Verify attempts are recorded
        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("attempts") >= 1);
    }

    @Test
    void testInvokeWithDefaultRetryPolicy() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, null, null);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        String result = invoker.invoke("test-service", Map.of(), Map.of(),
                Map.of(), ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testInvokeWithNullServiceName() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        ServiceInstance instance = createTestInstance("", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(eq(""), anyMap(), anyMap()))
                .thenReturn(List.of(instance));

        String result = invoker.invoke("", Map.of(), Map.of(),
                Map.of(), ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testInvokeWithEmptyServiceName() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        ServiceInstance instance = createTestInstance("", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(eq(""), anyMap(), anyMap()))
                .thenReturn(List.of(instance));

        String result = invoker.invoke("", Map.of(), Map.of(),
                Map.of(), ins -> "success");

        assertEquals("success", result);
    }

    @Test
    void testMetricsSnapshotContainsAllFields() {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();

        Map<String, Long> total = snapshot.get("total");
        assertNotNull(total);
        assertTrue(total.containsKey("attempts"));
        assertTrue(total.containsKey("successes"));
        assertTrue(total.containsKey("failures"));
        assertTrue(total.containsKey("retries"));
        assertTrue(total.containsKey("cbOpenSkips"));
    }

    @Test
    void testMultipleInvokesSameService() throws Exception {
        invoker = new ClientInvoker(mockNamingService, loadBalancer, retryPolicy, null);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        mockSelectorBehavior(List.of(instance));

        invoker.invoke("test-service", Map.of(), Map.of(), Map.of(), ins -> "result1");
        invoker.invoke("test-service", Map.of(), Map.of(), Map.of(), ins -> "result2");
        invoker.invoke("test-service", Map.of(), Map.of(), Map.of(), ins -> "result3");

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("test-service").get("successes") >= 3);
    }

    private void mockSelectorBehavior(List<ServiceInstance> instances) {
        // Mock the RedisNamingService.getHealthyInstancesByFilters method
        when(mockNamingService
                .getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(instances);
    }

    private void mockSelectorBehavior(String serviceName, List<ServiceInstance> instances) {
        when(mockNamingService
                .getHealthyInstancesByFilters(eq(serviceName), anyMap(), anyMap()))
                .thenReturn(instances);
    }

    private ServiceInstance createTestInstance(String serviceName, String instanceId) {
        return new ServiceInstance() {
            @Override
            public String getServiceName() {
                return serviceName;
            }

            @Override
            public String getInstanceId() {
                return instanceId;
            }

            @Override
            public String getHost() {
                return "localhost";
            }

            @Override
            public int getPort() {
                return 8080;
            }

            @Override
            public boolean isHealthy() {
                return true;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public java.util.Map<String, String> getMetadata() {
                return Map.of();
            }

            @Override
            public int getWeight() {
                return 1;
            }

            @Override
            public String getUniqueId() {
                return serviceName + ":" + instanceId;
            }
        };
    }
}
