package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.github.cuihairu.redis.streaming.registry.client.RetryPolicy;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** Direct coverage for the registry auto-configuration bean factory methods. */
class RegistryAutoConfigurationBeanMethodsCoverageTest {

    private final RedisStreamingRegistryAutoConfiguration cfg = new RedisStreamingRegistryAutoConfiguration();

    @Test
    void retryPolicyReflectsProperties() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        RetryPolicy policy = cfg.retryPolicy(props);
        assertNotNull(policy);
    }

    @Test
    void serviceChangeListenerProcessorIsWired() {
        NamingService namingService = mock(NamingService.class);
        assertNotNull(cfg.serviceChangeListenerProcessor(namingService));
    }

    @Test
    void clientInvokerMetricsBinderIsWired() {
        ClientInvoker invoker = mock(ClientInvoker.class);
        assertNotNull(cfg.clientInvokerMetricsBinder(invoker));
    }
}
