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
 * Unit tests for ClientSelector
 */
class ClientSelectorTest {

    @Mock
    private io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService mockNamingService;

    private LoadBalancer loadBalancer;
    private ClientSelector selector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        loadBalancer = new WeightedRandomLoadBalancer();
    }

    @Test
    void testConstructorWithNamingServiceOnly() {
        selector = new ClientSelector(mockNamingService);

        assertNotNull(selector);
    }

    @Test
    void testConstructorWithNamingServiceAndConfig() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        selector = new ClientSelector(mockNamingService, config);

        assertNotNull(selector);
    }

    @Test
    void testConstructorWithNullNamingService() {
        assertThrows(NullPointerException.class, () -> new ClientSelector(null));
    }

    @Test
    void testConstructorWithNullConfig() {
        selector = new ClientSelector(mockNamingService, null);

        assertNotNull(selector);
    }

    @Test
    void testSelectWithMatchingInstances() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), anyMap(), anyMap()))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithNoMatchingInstancesAndNoFallback() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        config.setEnableFallback(false);
        selector = new ClientSelector(mockNamingService, config);

        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of());

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNull(result);
    }

    @Test
    void testSelectWithFallbackDropMetricsFilters() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(true);
        selector = new ClientSelector(mockNamingService, config);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of()); // First call returns empty
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), anyMap(), isNull()))
                .thenReturn(List.of(instance)); // Second call (without metrics filters) returns instance

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithFallbackDropMetadataFilters() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(true);
        selector = new ClientSelector(mockNamingService, config);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of()); // First call returns empty
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), anyMap(), isNull()))
                .thenReturn(List.of()); // Second call returns empty
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), eq(Map.of()), anyMap()))
                .thenReturn(List.of(instance)); // Third call (without metadata filters) returns instance

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithFallbackUseAllHealthy() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(true);
        config.setFallbackDropMetadataFiltersNext(true);
        config.setFallbackUseAllHealthyLast(true);
        selector = new ClientSelector(mockNamingService, config);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), any(), any()))
                .thenReturn(List.of());
        when(mockNamingService.getHealthyInstances("test-service"))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithNoInstancesFound() {
        selector = new ClientSelector(mockNamingService);

        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of());
        when(mockNamingService.getHealthyInstances(anyString()))
                .thenReturn(List.of());

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNull(result);
    }

    @Test
    void testSelectWithEmptyFilters() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), eq(Map.of()), eq(Map.of())))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", Map.of(),
                Map.of(), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithNullFilters() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(eq("test-service"), isNull(), isNull()))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", null,
                null, loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithNullLoadBalancerContext() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, null);

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithMultipleCandidates() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance1 = createTestInstance("test-service", "instance-1");
        ServiceInstance instance2 = createTestInstance("test-service", "instance-2");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of(instance1, instance2));

        ServiceInstance result = selector.select("test-service", Map.of(),
                Map.of(), loadBalancer, Map.of());

        assertNotNull(result);
        // Should return one of the two instances
        assertTrue(result.getInstanceId().equals("instance-1") || result.getInstanceId().equals("instance-2"));
    }

    @Test
    void testSelectWithNullResultFromNamingService() {
        selector = new ClientSelector(mockNamingService);

        ServiceInstance instance = createTestInstance("test-service", "instance-1");
        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(null);
        when(mockNamingService.getHealthyInstances("test-service"))
                .thenReturn(List.of(instance));

        ServiceInstance result = selector.select("test-service", Map.of(),
                Map.of(), loadBalancer, Map.of());

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testSelectWithDisabledFallbackSteps() {
        ClientSelectorConfig config = new ClientSelectorConfig();
        config.setEnableFallback(true);
        config.setFallbackDropMetricsFiltersFirst(false);
        config.setFallbackDropMetadataFiltersNext(false);
        config.setFallbackUseAllHealthyLast(false);
        selector = new ClientSelector(mockNamingService, config);

        when(mockNamingService.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of());

        ServiceInstance result = selector.select("test-service", Map.of("key", "value"),
                Map.of("metric", "value"), loadBalancer, Map.of());

        assertNull(result);
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
        };
    }
}
