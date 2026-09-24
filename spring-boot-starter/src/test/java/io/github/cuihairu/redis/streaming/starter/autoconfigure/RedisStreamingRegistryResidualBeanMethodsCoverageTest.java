package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.github.cuihairu.redis.streaming.registry.client.ClientSelector;
import io.github.cuihairu.redis.streaming.registry.client.RetryPolicy;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Residual coverage for RedisStreamingRegistryAutoConfiguration bean factory methods:
 * clientSelector, redisClientMetricsReporter and clientInvoker wiring.
 */
class RedisStreamingRegistryResidualBeanMethodsCoverageTest {

    @Test
    void clientSelectorWiresNamingService() {
        RedisStreamingRegistryAutoConfiguration cfg = new RedisStreamingRegistryAutoConfiguration();
        ClientSelector selector = cfg.clientSelector(mock(NamingService.class));
        assertThat(selector).isNotNull();
    }

    @Test
    void redisClientMetricsReporterIsBuiltFromClient() {
        RedisStreamingRegistryAutoConfiguration cfg = new RedisStreamingRegistryAutoConfiguration();
        RedisClientMetricsReporter reporter = cfg.redisClientMetricsReporter(mock(RedissonClient.class));
        assertThat(reporter).isNotNull();
    }

    @Test
    void clientInvokerWiresAllCollaborators() {
        RedisStreamingRegistryAutoConfiguration cfg = new RedisStreamingRegistryAutoConfiguration();
        RetryPolicy retryPolicy = cfg.retryPolicy(new RedisStreamingProperties());
        ClientInvoker invoker = cfg.clientInvoker(
                mock(NamingService.class),
                mock(LoadBalancer.class),
                retryPolicy,
                mock(RedisClientMetricsReporter.class));
        assertThat(invoker).isNotNull();
    }
}
