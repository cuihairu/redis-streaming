package io.github.cuihairu.redis.streaming.registry.client;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers ClientInvoker backoff interrupt path and circuit-breaker-open branch.
 */
class ClientInvokerCoverageTest {

    private static ServiceInstance instance(String id) {
        return new ServiceInstance() {
            @Override
            public String getServiceName() {
                return "invoke-cov";
            }

            @Override
            public String getInstanceId() {
                return id;
            }

            @Override
            public String getHost() {
                return "127.0.0.1";
            }

            @Override
            public int getPort() {
                return 1;
            }

            @Override
            public Map<String, String> getMetadata() {
                return Map.of();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public boolean isHealthy() {
                return true;
            }
        };
    }

    @Test
    void backoffHonoursThreadInterrupt() throws Exception {
        RedisNamingService naming = mock(RedisNamingService.class);
        when(naming.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of());
        LoadBalancer lb = (svc, candidates, ctx) -> candidates.isEmpty() ? null : candidates.get(0);
        ClientInvoker invoker = new ClientInvoker(naming, lb, new RetryPolicy(1, 0, 1.0, 0, 0), null);

        Thread.currentThread().interrupt();
        try {
            assertThrows(Exception.class,
                    () -> invoker.invoke("invoke-cov", Map.of(), Map.of(), Map.of(), ins -> "x"));
            assertTrue(Thread.interrupted(), "backoff catch should re-raise the interrupt flag");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invokeSkipsWhenCircuitBreakerIsOpen() throws Exception {
        RedisNamingService naming = mock(RedisNamingService.class);
        ServiceInstance ins = instance("breaker");
        when(naming.getHealthyInstancesByFilters(anyString(), anyMap(), anyMap()))
                .thenReturn(List.of(ins));
        LoadBalancer lb = (svc, candidates, ctx) -> candidates.get(0);
        ClientInvoker invoker = new ClientInvoker(naming, lb, new RetryPolicy(1, 0, 1.0, 0, 0), null);

        // a single failure trips the breaker (window rate 1.0 >= 0.5)
        assertThrows(Exception.class,
                () -> invoker.invoke("invoke-cov", Map.of(), Map.of(), Map.of(), i -> {
                    throw new IllegalStateException("downstream");
                }));

        // breaker now open -> cbOpenSkips path
        Exception open = assertThrows(Exception.class,
                () -> invoker.invoke("invoke-cov", Map.of(), Map.of(), Map.of(), i -> "x"));
        assertTrue(open.getMessage().contains("Circuit breaker open"));

        Map<String, Map<String, Long>> snapshot = invoker.getMetricsSnapshot();
        assertTrue(snapshot.get("total").get("cbOpenSkips") >= 1);
    }
}
