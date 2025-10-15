package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.streaming.config.ConfigService;
import io.github.cuihairu.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.starter.processor.ServiceChangeListenerProcessor;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;
import io.github.cuihairu.redis.streaming.registry.client.*;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis Streaming框架自动配置
 * 根据配置按需加载服务注册发现和配置管理功能
 */
@Slf4j
@Configuration
@ConditionalOnClass({RedissonClient.class})
@EnableConfigurationProperties(RedisStreamingProperties.class)
public class RedisStreamingAutoConfiguration {

    /**
     * 创建RedissonClient
     *
     * 注意：如果项目中已经配置了 redisson-spring-boot-starter，
     * 该Bean会被跳过（@ConditionalOnMissingBean），使用项目的RedissonClient配置。
     *
     * 这里提供的是简化的单机配置，适合快速开发和测试。
     * 生产环境建议使用 redisson-spring-boot-starter 提供完整的集群/哨兵/SSL等配置。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(RedisStreamingProperties properties) {
        Config config = new Config();
        RedisStreamingProperties.RedisProperties redis = properties.getRedis();

        // 简化配置，仅支持单机模式
        // 完整配置请使用 redisson-spring-boot-starter
        config.useSingleServer()
                .setAddress(redis.getAddress())
                .setPassword(redis.getPassword())
                .setDatabase(redis.getDatabase())
                .setConnectTimeout(redis.getConnectTimeout())
                .setTimeout(redis.getTimeout())
                .setConnectionPoolSize(redis.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize());

        log.info("Initializing RedissonClient with address: {} (Simple single-server mode)", redis.getAddress());
        log.info("For production with cluster/sentinel, use redisson-spring-boot-starter");
        return Redisson.create(config);
    }

    /**
     * 服务注册配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.registry", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RegistryConfiguration {

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
         * 注册 ServiceChangeListener 注解处理器
         * 自动扫描并注册带有 @ServiceChangeListener 注解的方法
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(NamingService.class)
        public ServiceChangeListenerProcessor serviceChangeListenerProcessor(NamingService namingService) {
            log.info("Initializing ServiceChangeListenerProcessor for @ServiceChangeListener annotation");
            // NamingService 继承了 ServiceConsumer，ServiceConsumer 继承了 ServiceDiscovery
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
        public ClientSelector clientSelector(NamingService namingService) {
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
        public ClientInvoker clientInvoker(NamingService namingService,
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

    /**
     * 服务发现配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class DiscoveryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ServiceDiscovery serviceDiscovery(RedissonClient redissonClient, RedisStreamingProperties properties) {
            log.info("Initializing ServiceDiscovery with healthy-only: {}",
                    properties.getDiscovery().isHealthyOnly());

            ServiceDiscovery discovery = new RedisNamingService(redissonClient);
            discovery.start();
            return discovery;
        }
    }

    /**
     * 配置服务配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.config", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ConfigServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ConfigService configService(RedissonClient redissonClient, RedisStreamingProperties properties) {
            log.info("Initializing ConfigService with default group: {}",
                    properties.getConfig().getDefaultGroup());

            ConfigService configService = new RedisConfigService(redissonClient);
            configService.start();
            return configService;
        }
    }

    /**
     * MQ 自动配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MqConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MqOptions mqOptions(RedisStreamingProperties props) {
            var p = props.getMq();
            return MqOptions.builder()
                    .defaultPartitionCount(p.getDefaultPartitionCount())
                    .workerThreads(p.getWorkerThreads())
                    .schedulerThreads(p.getSchedulerThreads())
                    .consumerBatchCount(p.getConsumerBatchCount())
                    .consumerPollTimeoutMs(p.getConsumerPollTimeoutMs())
                    .leaseTtlSeconds(p.getLeaseTtlSeconds())
                    .rebalanceIntervalSec(p.getRebalanceIntervalSec())
                    .renewIntervalSec(p.getRenewIntervalSec())
                    .pendingScanIntervalSec(p.getPendingScanIntervalSec())
                    .claimIdleMs(p.getClaimIdleMs())
                    .claimBatchSize(p.getClaimBatchSize())
                    .retryMaxAttempts(p.getRetryMaxAttempts())
                    .retryBaseBackoffMs(p.getRetryBaseBackoffMs())
                    .retryMaxBackoffMs(p.getRetryMaxBackoffMs())
                    .retryMoverBatch(p.getRetryMoverBatch())
                    .retryMoverIntervalSec(p.getRetryMoverIntervalSec())
                    .retryLockWaitMs(p.getRetryLockWaitMs())
                    .retryLockLeaseMs(p.getRetryLockLeaseMs())
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean
        public MessageQueueFactory messageQueueFactory(RedissonClient redissonClient, MqOptions mqOptions) {
            return new MessageQueueFactory(redissonClient, mqOptions);
        }

        @Bean
        @ConditionalOnMissingBean
        public MessageQueueAdmin messageQueueAdmin(RedissonClient redissonClient) {
            return new RedisMessageQueueAdmin(redissonClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeadLetterQueueManager deadLetterQueueManager(RedissonClient redissonClient) {
            return new DeadLetterQueueManager(redissonClient);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.MqMetricsBinder mqMetricsBinder(
                MessageQueueAdmin admin,
                DeadLetterQueueManager dlq) {
            return new io.github.cuihairu.redis.streaming.starter.metrics.MqMetricsBinder(admin, dlq);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.MqMicrometerCollector mqMicrometerCollector(
                io.micrometer.core.instrument.MeterRegistry registry) {
            return new io.github.cuihairu.redis.streaming.starter.metrics.MqMicrometerCollector(registry);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public Object installMqCollector(io.github.cuihairu.redis.streaming.starter.metrics.MqMicrometerCollector collector) {
            // Bridge mq module metrics to Micrometer
            io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics.setCollector(collector);
            return new Object();
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
        public io.github.cuihairu.redis.streaming.starter.health.MqHealthIndicator mqHealthIndicator(MessageQueueAdmin admin) {
            return new io.github.cuihairu.redis.streaming.starter.health.MqHealthIndicator(admin);
        }
    }
}
