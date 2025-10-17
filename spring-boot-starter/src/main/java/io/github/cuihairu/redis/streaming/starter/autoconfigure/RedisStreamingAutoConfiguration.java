package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.starter.processor.ServiceChangeListenerProcessor;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;
import io.github.cuihairu.redis.streaming.registry.client.*;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterAdmin;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterAdmin;
import io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterService;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterConsumer;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterConsumer;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemorySlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RedisSlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemoryLeakyBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RedisTokenBucketRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.NamedRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetrics;
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

    /**
     * 服务发现配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class DiscoveryConfiguration {

        @Bean
        @ConditionalOnMissingBean({NamingService.class, ServiceDiscovery.class})
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

            var cfgProps = properties.getConfig();
            io.github.cuihairu.redis.streaming.config.ConfigServiceConfig cfg =
                    new io.github.cuihairu.redis.streaming.config.ConfigServiceConfig(
                            cfgProps.getKeyPrefix(), cfgProps.isEnableKeyPrefix());
            cfg.setHistorySize(cfgProps.getHistorySize());
            ConfigService configService = new RedisConfigService(redissonClient, cfg);
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
                    .keyPrefix(p.getKeyPrefix())
                    .streamKeyPrefix(p.getStreamKeyPrefix())
                    .consumerNamePrefix(p.getConsumerNamePrefix())
                    .dlqConsumerSuffix(p.getDlqConsumerSuffix())
                    .defaultConsumerGroup(p.getDefaultConsumerGroup())
                    .defaultDlqGroup(p.getDefaultDlqGroup())
                    .retentionMaxLenPerPartition(p.getRetentionMaxLenPerPartition())
                    .retentionMs(p.getRetentionMs())
                    .trimIntervalSec(p.getTrimIntervalSec())
                    .ackDeletePolicy(p.getAckDeletePolicy())
                    .acksetTtlSec(p.getAcksetTtlSec())
                    .dlqRetentionMaxLen(p.getDlqRetentionMaxLen())
                    .dlqRetentionMs(p.getDlqRetentionMs())
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean
        public MessageQueueFactory messageQueueFactory(RedissonClient redissonClient, MqOptions mqOptions,
                                                       io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory brokerFactory) {
            return new MessageQueueFactory(redissonClient, mqOptions, brokerFactory);
        }

        @Bean
        @ConditionalOnMissingBean
        public io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter brokerRouter() {
            return new io.github.cuihairu.redis.streaming.mq.broker.impl.HashBrokerRouter();
        }

        @Bean
        @ConditionalOnMissingBean
        public io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory brokerFactory(
                RedisStreamingProperties props,
                @org.springframework.beans.factory.annotation.Autowired(required = false) javax.sql.DataSource dataSource) {
            String type = props.getMq().getBroker().getType();
            if ("jdbc".equalsIgnoreCase(type)) {
                if (dataSource != null) {
                    log.info("BrokerFactory: using JDBC persistence (DataSource provided)");
                    return new io.github.cuihairu.redis.streaming.mq.broker.jdbc.JdbcBrokerFactory(dataSource);
                } else {
                    log.warn("BrokerFactory: type=jdbc but no DataSource bean found; falling back to Redis persistence");
                }
            }
            return new io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerFactory();
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        public io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper streamRetentionHousekeeper(
                RedissonClient redissonClient,
                MessageQueueAdmin admin,
                MqOptions opts) {
            // Ensure stream key prefix is configured for consistency
            io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(opts.getKeyPrefix(), opts.getStreamKeyPrefix());
            // Always install with safe defaults; can be tuned via properties
            return new io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper(redissonClient, admin, opts);
        }

        @Bean
        @ConditionalOnMissingBean
        public MessageQueueAdmin messageQueueAdmin(RedissonClient redissonClient) {
            return new RedisMessageQueueAdmin(redissonClient);
        }

        @Bean(name = "dlqReplayProducer", destroyMethod = "close")
        @ConditionalOnMissingBean(name = "dlqReplayProducer")
        public MessageProducer dlqReplayProducer(MessageQueueFactory mqFactory) {
            // Singleton producer dedicated for DLQ replay; closed on context shutdown
            return mqFactory.createProducer();
        }

        @Bean
        @ConditionalOnMissingBean
        public io.github.cuihairu.redis.streaming.reliability.dlq.ReplayHandler dlqReplayHandler(
                RedissonClient redissonClient,
                MqOptions opts,
                @org.springframework.beans.factory.annotation.Qualifier("dlqReplayProducer") MessageProducer dlqReplayProducer) {
            // Keep DLQ key space consistent with MQ stream prefix (idempotent)
            io.github.cuihairu.redis.streaming.reliability.dlq.DlqKeys.configure(opts.getStreamKeyPrefix());
            return (topic, partitionId, payload, headers, maxRetries) -> {
                try {
                    java.util.Map<String,String> hdr = new java.util.HashMap<>();
                    if (headers != null) hdr.putAll(headers);
                    hdr.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.FORCE_PARTITION_ID, Integer.toString(partitionId));
                    io.github.cuihairu.redis.streaming.mq.Message m = new io.github.cuihairu.redis.streaming.mq.Message(topic, payload, hdr);
                    m.setMaxRetries(maxRetries);
                    dlqReplayProducer.send(m).join();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            };
        }

        @Bean
        @ConditionalOnMissingBean
        public DeadLetterService deadLetterService(RedissonClient redissonClient,
                                                   io.github.cuihairu.redis.streaming.reliability.dlq.ReplayHandler dlqReplayHandler) {
            return new RedisDeadLetterService(redissonClient, dlqReplayHandler);
        }

        /**
         * DeadLetterAdmin provides operational utilities over DLQ without exposing any Web endpoint.
         */
        @Bean
        @ConditionalOnMissingBean
        public DeadLetterAdmin deadLetterAdmin(RedissonClient redissonClient, DeadLetterService deadLetterService) {
            return new RedisDeadLetterAdmin(redissonClient, deadLetterService);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeadLetterConsumer deadLetterConsumer(RedissonClient redissonClient, MqOptions opts,
                                                     io.github.cuihairu.redis.streaming.reliability.dlq.ReplayHandler dlqReplayHandler) {
            // Ensure DLQ keys prefix aligns even if DeadLetterService bean was not requested
            io.github.cuihairu.redis.streaming.reliability.dlq.DlqKeys.configure(opts.getStreamKeyPrefix());
            return new RedisDeadLetterConsumer(redissonClient, opts.getConsumerNamePrefix() + "dlq", opts.getDefaultDlqGroup(), dlqReplayHandler);
        }

        @Bean
        @SuppressWarnings("deprecation")
        @ConditionalOnMissingBean
        public DeadLetterQueueManager deadLetterQueueManager(RedissonClient redissonClient) {
            // Backwards-compatible bean; internally delegates to reliability DLQ service
            return new DeadLetterQueueManager(redissonClient);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.MqMetricsBinder mqMetricsBinder(
                MessageQueueAdmin admin,
                DeadLetterService dlq) {
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
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.RetentionMicrometerCollector retentionMicrometerCollector(
                io.micrometer.core.instrument.MeterRegistry registry) {
            return new io.github.cuihairu.redis.streaming.starter.metrics.RetentionMicrometerCollector(registry);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public Object installRetentionCollector(io.github.cuihairu.redis.streaming.starter.metrics.RetentionMicrometerCollector collector) {
            io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.setCollector(collector);
            return new Object();
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.RetentionFrontierMetricsBinder retentionFrontierMetricsBinder(
                RedissonClient redissonClient,
                MessageQueueAdmin admin,
                MqOptions opts) {
            return new io.github.cuihairu.redis.streaming.starter.metrics.RetentionFrontierMetricsBinder(redissonClient, admin, opts);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public io.github.cuihairu.redis.streaming.starter.metrics.ReliabilityMicrometerCollector reliabilityMicrometerCollector(
                io.micrometer.core.instrument.MeterRegistry registry) {
            return new io.github.cuihairu.redis.streaming.starter.metrics.ReliabilityMicrometerCollector(registry);
        }

        @Bean
        @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
        public Object installReliabilityCollector(io.github.cuihairu.redis.streaming.starter.metrics.ReliabilityMicrometerCollector collector) {
            ReliabilityMetrics.setCollector(collector);
            return new Object();
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
        public io.github.cuihairu.redis.streaming.starter.health.MqHealthIndicator mqHealthIndicator(MessageQueueAdmin admin) {
            return new io.github.cuihairu.redis.streaming.starter.health.MqHealthIndicator(admin);
        }
    }

    /**
     * Rate limiter auto configuration
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.ratelimit", name = "enabled", havingValue = "true")
    static class RateLimitConfiguration {

        private RateLimiter buildOneRaw(String name, RedisStreamingProperties.RateLimitProperties.Policy p, RedissonClient redissonClient) {
            String backend = p.getBackend();
            String algorithm = p.getAlgorithm();
            if (algorithm == null || algorithm.isBlank()) algorithm = "sliding";

            switch (algorithm.toLowerCase()) {
                case "sliding": {
                    if ("redis".equalsIgnoreCase(backend)) {
                        if (redissonClient != null) {
                            log.info("RateLimiter[{}]=RedisSlidingWindow(windowMs={}, limit={}, keyPrefix={})", name, p.getWindowMs(), p.getLimit(), p.getKeyPrefix());
                            return new RedisSlidingWindowRateLimiter(redissonClient, p.getKeyPrefix(), p.getWindowMs(), p.getLimit());
                        }
                        log.warn("RateLimiter[{}] redis backend requested but no RedissonClient; using in-memory sliding.", name);
                    }
                    log.info("RateLimiter[{}]=InMemorySlidingWindow(windowMs={}, limit={})", name, p.getWindowMs(), p.getLimit());
                    return new InMemorySlidingWindowRateLimiter(p.getWindowMs(), p.getLimit());
                }
                case "token-bucket": {
                    if ("redis".equalsIgnoreCase(backend)) {
                        if (redissonClient != null) {
                            log.info("RateLimiter[{}]=RedisTokenBucket(capacity={}, rate/s={}, keyPrefix={})", name, p.getCapacity(), p.getRatePerSecond(), p.getKeyPrefix());
                            return new RedisTokenBucketRateLimiter(redissonClient, p.getKeyPrefix(), p.getCapacity(), p.getRatePerSecond());
                        }
                        log.warn("RateLimiter[{}] redis backend requested but no RedissonClient; using in-memory token-bucket.", name);
                    }
                    log.info("RateLimiter[{}]=InMemoryTokenBucket(capacity={}, rate/s={})", name, p.getCapacity(), p.getRatePerSecond());
                    return new InMemoryTokenBucketRateLimiter(p.getCapacity(), p.getRatePerSecond());
                }
                case "leaky-bucket": {
                    log.info("RateLimiter[{}]=InMemoryLeakyBucket(capacity={}, leak/s={})", name, p.getCapacity(), p.getRatePerSecond());
                    return new InMemoryLeakyBucketRateLimiter(p.getCapacity(), p.getRatePerSecond());
                }
                default:
                    log.warn("RateLimiter[{}] unknown algorithm '{}', defaulting to sliding.", name, algorithm);
                    return new InMemorySlidingWindowRateLimiter(p.getWindowMs(), p.getLimit());
            }
        }

        private RateLimiter buildOne(String name, RedisStreamingProperties.RateLimitProperties.Policy p, RedissonClient redissonClient) {
            return new NamedRateLimiter(name, buildOneRaw(name, p, redissonClient));
        }

        @Bean
        @ConditionalOnMissingBean
        public io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry rateLimiterRegistry(
                RedisStreamingProperties props,
                @org.springframework.beans.factory.annotation.Autowired(required = false) RedissonClient redissonClient) {
            var p = props.getRatelimit();
            java.util.Map<String, RateLimiter> map = new java.util.HashMap<>();
            if (p.getPolicies() == null || p.getPolicies().isEmpty()) {
                // Build single default from top-level properties
                var single = new RedisStreamingProperties.RateLimitProperties.Policy();
                single.setBackend(p.getBackend());
                single.setWindowMs(p.getWindowMs());
                single.setLimit(p.getLimit());
                single.setKeyPrefix(p.getKeyPrefix());
                map.put(p.getDefaultName(), buildOne(p.getDefaultName(), single, redissonClient));
            } else {
                p.getPolicies().forEach((name, policy) -> map.put(name, buildOne(name, policy, redissonClient)));
                // Ensure default exists
                map.putIfAbsent(p.getDefaultName(), buildOne(p.getDefaultName(),
                        p.getPolicies().getOrDefault(p.getDefaultName(), new RedisStreamingProperties.RateLimitProperties.Policy()), redissonClient));
            }
            return new io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry(map);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        @ConditionalOnMissingBean(RateLimiter.class)
        public RateLimiter rateLimiter(io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry registry,
                                       RedisStreamingProperties props) {
            String name = props.getRatelimit().getDefaultName();
            RateLimiter rl = registry.get(name);
            if (rl == null) {
                // Fallback to any
                java.util.Map<String, RateLimiter> all = registry.all();
                rl = all.isEmpty() ? new InMemorySlidingWindowRateLimiter(1000, 100) : all.values().iterator().next();
                log.warn("RateLimiter defaultName='{}' not found; using {}", name, rl.getClass().getSimpleName());
            }
            return rl;
        }
    }

    // Wire RateLimit metrics to Micrometer if present
    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector rateLimitMicrometerCollector(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector(registry);
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public Object installRateLimitCollector(io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector collector) {
        RateLimitMetrics.setCollector(collector);
        return new Object();
    }
}
