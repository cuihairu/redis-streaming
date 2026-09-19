package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.cuihairu.redis.streaming.registry.client.ClientSelector;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.github.cuihairu.redis.streaming.registry.client.RetryPolicy;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ConsistentHashLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancerConfig;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.MetricsProvider;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.RedisMetricsProvider;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ScoredLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRandomLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer;
import io.github.cuihairu.redis.streaming.starter.processor.ServiceChangeListenerProcessor;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Service-registry scoped auto-configuration beans (extracted from {@link RedisStreamingAutoConfiguration}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "redis-streaming.registry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamingRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NamingService namingService(RedissonClient redissonClient, RedisStreamingProperties properties) {
        log.info("Initializing NamingService with heartbeat interval: {}s",
                properties.getRegistry().getHeartbeatInterval());

        // Wire provider metrics global config
        var mp = properties.getRegistry().getMetrics();
        io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig mc = new io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig();
        if (mp.getEnabled() != null && !mp.getEnabled().isEmpty()) {
            mc.setEnabledMetrics(mp.getEnabled());
        }
        if (mp.getIntervals() != null && !mp.getIntervals().isEmpty()) {
            mc.setCollectionIntervals(mp.getIntervals());
        }
        if (mp.getDefaultInterval() != null) {
            mc.setDefaultCollectionInterval(mp.getDefaultInterval());
        }
        mc.setImmediateUpdateOnSignificantChange(mp.isImmediateUpdateOnSignificantChange());
        if (mp.getTimeout() != null) {
            mc.setCollectionTimeout(mp.getTimeout());
        }
        io.github.cuihairu.redis.streaming.registry.metrics.MetricsGlobal.setDefaultConfig(mc);

        NamingService registry = new RedisNamingService(redissonClient);
        registry.start();
        return registry;
    }

    /**
     * Register ServiceChangeListener annotation processor
     * Automatically scans and registers methods annotated with @ServiceChangeListener
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NamingService.class)
    public ServiceChangeListenerProcessor serviceChangeListenerProcessor(NamingService namingService) {
        log.info("Initializing ServiceChangeListenerProcessor for @ServiceChangeListener annotation");
        // NamingService extends ServiceConsumer, which extends ServiceDiscovery
        return new ServiceChangeListenerProcessor((ServiceDiscovery) namingService);
    }

    // ===== LoadBalancer & Selector & Invoker =====

    @Bean
    @ConditionalOnMissingBean
    public LoadBalancer loadBalancer(RedissonClient redissonClient, RedisStreamingProperties props) {
        String strategy = props.getLoadBalancer().getStrategy();
        if ("wrr".equalsIgnoreCase(strategy)) {
            return new WeightedRoundRobinLoadBalancer();
        } else if ("weighted-random".equalsIgnoreCase(strategy)) {
            return new WeightedRandomLoadBalancer();
        } else if ("consistent-hash".equalsIgnoreCase(strategy)) {
            return new ConsistentHashLoadBalancer();
        } else {
            // default scored
            LoadBalancerConfig cfg = new LoadBalancerConfig();
            var lb = props.getLoadBalancer();
            cfg.setPreferredRegion(lb.getPreferredRegion());
            cfg.setPreferredZone(lb.getPreferredZone());
            cfg.setCpuWeight(lb.getCpuWeight());
            cfg.setLatencyWeight(lb.getLatencyWeight());
            cfg.setMemoryWeight(lb.getMemoryWeight());
            cfg.setInflightWeight(lb.getInflightWeight());
            cfg.setQueueWeight(lb.getQueueWeight());
            cfg.setErrorRateWeight(lb.getErrorRateWeight());
            cfg.setTargetLatencyMs(lb.getTargetLatencyMs());
            cfg.setMaxCpuPercent(lb.getMaxCpuPercent());
            cfg.setMaxLatencyMs(lb.getMaxLatencyMs());
            cfg.setMaxMemoryPercent(lb.getMaxMemoryPercent());
            cfg.setMaxInflight(lb.getMaxInflight());
            cfg.setMaxQueue(lb.getMaxQueue());
            cfg.setMaxErrorRatePercent(lb.getMaxErrorRatePercent());
            MetricsProvider mp = new RedisMetricsProvider(redissonClient, new ServiceConsumerConfig());
            return new ScoredLoadBalancer(cfg, mp);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientSelector clientSelector(@org.springframework.beans.factory.annotation.Qualifier("namingService") NamingService namingService) {
        return new ClientSelector(namingService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy retryPolicy(RedisStreamingProperties props) {
        var p = props.getInvoker();
        return new RetryPolicy(p.getMaxAttempts(), p.getInitialDelayMs(), p.getBackoffFactor(), p.getMaxDelayMs(), p.getJitterMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisClientMetricsReporter redisClientMetricsReporter(RedissonClient redissonClient) {
        // use default consumer config for key prefix
        return new RedisClientMetricsReporter(redissonClient, new ServiceConsumerConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientInvoker clientInvoker(@org.springframework.beans.factory.annotation.Qualifier("namingService") NamingService namingService,
                                       LoadBalancer loadBalancer,
                                       RetryPolicy retryPolicy,
                                       RedisClientMetricsReporter reporter) {
        return new ClientInvoker(namingService, loadBalancer, retryPolicy, reporter);
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(ClientInvoker.class)
    public io.github.cuihairu.redis.streaming.starter.metrics.ClientInvokerMetricsBinder clientInvokerMetricsBinder(ClientInvoker clientInvoker) {
        return new io.github.cuihairu.redis.streaming.starter.metrics.ClientInvokerMetricsBinder(clientInvoker);
    }
}
