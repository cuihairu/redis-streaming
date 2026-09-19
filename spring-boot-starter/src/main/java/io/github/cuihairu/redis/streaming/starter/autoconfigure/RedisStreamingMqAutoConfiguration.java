package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterAdmin;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterConsumer;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterAdmin;
import io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterConsumer;
import io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService;
import io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Message-queue scoped auto-configuration beans (extracted from {@link RedisStreamingAutoConfiguration}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "redis-streaming.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamingMqAutoConfiguration {

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
                .maxInFlight(p.getMaxInFlight())
                .maxLeasedPartitionsPerConsumer(p.getMaxLeasedPartitionsPerConsumer())
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
    public io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler dlqReplayHandler(
            RedissonClient redissonClient,
            MqOptions opts,
            @org.springframework.beans.factory.annotation.Qualifier("dlqReplayProducer") MessageProducer dlqReplayProducer) {
        // Keep DLQ key space consistent with MQ stream prefix (idempotent)
        io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys.configure(opts.getStreamKeyPrefix());
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
                                               io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler dlqReplayHandler) {
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
                                                 io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler dlqReplayHandler) {
        // Ensure DLQ keys prefix aligns even if DeadLetterService bean was not requested
        io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys.configure(opts.getStreamKeyPrefix());
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
    @ConditionalOnClass(name = {
            "io.micrometer.core.instrument.MeterRegistry",
            "io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics"
    })
    public io.github.cuihairu.redis.streaming.starter.metrics.RedisRuntimeMicrometerCollector redisRuntimeMicrometerCollector(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new io.github.cuihairu.redis.streaming.starter.metrics.RedisRuntimeMicrometerCollector(registry);
    }

    @Bean
    @ConditionalOnClass(name = {
            "io.micrometer.core.instrument.MeterRegistry",
            "io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics"
    })
    public Object installRedisRuntimeCollector(io.github.cuihairu.redis.streaming.starter.metrics.RedisRuntimeMicrometerCollector collector) {
        io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics.setCollector(collector);
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
