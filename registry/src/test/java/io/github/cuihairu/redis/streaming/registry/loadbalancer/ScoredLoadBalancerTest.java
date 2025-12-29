package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScoredLoadBalancer
 */
class ScoredLoadBalancerTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    private ScoredLoadBalancer loadBalancer;
    private LoadBalancerConfig config;
    private MetricsProvider metricsProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new LoadBalancerConfig();
        metricsProvider = new MetricsProvider() {
            @Override
            public Map<String, Object> getMetrics(String serviceName, String instanceId) {
                return Map.of();
            }
        };
    }

    @Test
    void testConstructorWithValidParameters() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        assertNotNull(loadBalancer);
    }

    @Test
    void testConstructorWithNullConfig() {
        loadBalancer = new ScoredLoadBalancer(null, metricsProvider);

        assertNotNull(loadBalancer);
    }

    @Test
    void testConstructorWithNullMetricsProvider() {
        // ScoredLoadBalancer requires non-null metricsProvider
        assertThrows(NullPointerException.class, () -> new ScoredLoadBalancer(config, null));
    }

    @Test
    void testChooseWithEmptyCandidates() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        List<ServiceInstance> candidates = List.of();
        Map<String, Object> context = Map.of();

        ServiceInstance result = loadBalancer.choose("test-service", candidates, context);

        assertNull(result);
    }

    @Test
    void testChooseWithSingleCandidate() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance = createTestInstance("test-service", "instance-1", 1);
        List<ServiceInstance> candidates = List.of(instance);
        Map<String, Object> context = Map.of();

        ServiceInstance result = loadBalancer.choose("test-service", candidates, context);

        assertNotNull(result);
        assertEquals("instance-1", result.getInstanceId());
    }

    @Test
    void testChooseWithMultipleCandidates() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance1 = createTestInstance("test-service", "instance-1", 1);
        ServiceInstance instance2 = createTestInstance("test-service", "instance-2", 1);
        List<ServiceInstance> candidates = List.of(instance1, instance2);
        Map<String, Object> context = Map.of();

        ServiceInstance result = loadBalancer.choose("test-service", candidates, context);

        assertNotNull(result);
        // With mock MetricsProvider, should return one of the candidates
    }

    @Test
    void testChooseWithNullCandidates() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance result = loadBalancer.choose("test-service", null, Map.of());

        assertNull(result);
    }

    @Test
    void testChooseWithNullContext() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance = createTestInstance("test-service", "instance-1", 1);
        List<ServiceInstance> candidates = List.of(instance);

        ServiceInstance result = loadBalancer.choose("test-service", candidates, null);

        assertNotNull(result);
    }

    @Test
    void testChooseWithNullServiceName() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance = createTestInstance(null, "instance-1", 1);
        List<ServiceInstance> candidates = List.of(instance);

        ServiceInstance result = loadBalancer.choose(null, candidates, Map.of());

        assertNotNull(result);
    }

    @Test
    void testMultipleChooseCalls() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance1 = createTestInstance("test-service", "instance-1", 1);
        ServiceInstance instance2 = createTestInstance("test-service", "instance-2", 1);
        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Multiple calls should work
        ServiceInstance result1 = loadBalancer.choose("test-service", candidates, Map.of());
        ServiceInstance result2 = loadBalancer.choose("test-service", candidates, Map.of());
        ServiceInstance result3 = loadBalancer.choose("test-service", candidates, Map.of());

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
    }

    @Test
    void testChooseWithEmptyServiceName() {
        loadBalancer = new ScoredLoadBalancer(config, metricsProvider);

        ServiceInstance instance = createTestInstance("", "instance-1", 1);
        List<ServiceInstance> candidates = List.of(instance);

        ServiceInstance result = loadBalancer.choose("", candidates, Map.of());

        assertNotNull(result);
    }

    private ServiceInstance createTestInstance(String serviceName, String instanceId, int weight) {
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
                return weight;
            }
        };
    }
}
