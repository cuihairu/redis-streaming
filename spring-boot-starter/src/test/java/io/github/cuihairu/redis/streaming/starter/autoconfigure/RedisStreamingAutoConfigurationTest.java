package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RedisStreamingAutoConfiguration}
 */
class RedisStreamingAutoConfigurationTest {

    // 测试 RedisStreamingProperties 配置绑定
    private final ApplicationContextRunner propertiesRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStreamingPropertiesConfig.class));

    @Test
    void testPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.redis.password=mypassword",
                        "redis-streaming.redis.database=2",
                        "redis-streaming.registry.heartbeat-interval=30",
                        "redis-streaming.mq.default-partition-count=16",
                        "redis-streaming.mq.worker-threads=8",
                        "redis-streaming.config.default-group=mygroup",
                        "redis-streaming.load-balancer.strategy=wrr",
                        "redis-streaming.ratelimit.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedisStreamingProperties.class);
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRedis().getAddress()).isEqualTo("redis://localhost:6379");
                    assertThat(props.getRedis().getPassword()).isEqualTo("mypassword");
                    assertThat(props.getRedis().getDatabase()).isEqualTo(2);
                    assertThat(props.getRegistry().getHeartbeatInterval()).isEqualTo(30);
                    assertThat(props.getMq().getDefaultPartitionCount()).isEqualTo(16);
                    assertThat(props.getMq().getWorkerThreads()).isEqualTo(8);
                    assertThat(props.getConfig().getDefaultGroup()).isEqualTo("mygroup");
                    assertThat(props.getLoadBalancer().getStrategy()).isEqualTo("wrr");
                    assertThat(props.getRatelimit().isEnabled()).isTrue();
                });
    }

    @Test
    void testPropertiesDefaults() {
        propertiesRunner.withPropertyValues("redis-streaming.redis.address=redis://localhost:6379")
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRedis().getDatabase()).isEqualTo(0);
                    assertThat(props.getRegistry().isEnabled()).isTrue();
                    assertThat(props.getDiscovery().isEnabled()).isTrue();
                    assertThat(props.getConfig().isEnabled()).isTrue();
                    assertThat(props.getMq().isEnabled()).isTrue();
                    assertThat(props.getRatelimit().isEnabled()).isFalse();
                });
    }

    @Test
    void testRedisPropertiesDefaults() {
        propertiesRunner.withPropertyValues("redis-streaming.redis.address=redis://localhost:6379")
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRedis().getPassword()).isNull();
                    assertThat(props.getRedis().getDatabase()).isEqualTo(0);
                    assertThat(props.getRedis().getConnectTimeout()).isEqualTo(3000);
                    assertThat(props.getRedis().getTimeout()).isEqualTo(3000);
                    assertThat(props.getRedis().getConnectionPoolSize()).isEqualTo(64);
                    assertThat(props.getRedis().getConnectionMinimumIdleSize()).isEqualTo(10);
                });
    }

    @Test
    void testLoadBalancerPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.load-balancer.strategy=consistent-hash",
                        "redis-streaming.load-balancer.preferred-region=us-east",
                        "redis-streaming.load-balancer.preferred-zone=zone-a",
                        "redis-streaming.load-balancer.cpu-weight=1.5",
                        "redis-streaming.load-balancer.latency-weight=2.0",
                        "redis-streaming.load-balancer.memory-weight=0.5",
                        "redis-streaming.load-balancer.inflight-weight=1.0",
                        "redis-streaming.load-balancer.queue-weight=0.8",
                        "redis-streaming.load-balancer.error-rate-weight=1.2",
                        "redis-streaming.load-balancer.target-latency-ms=100",
                        "redis-streaming.load-balancer.max-cpu-percent=80",
                        "redis-streaming.load-balancer.max-latency-ms=200",
                        "redis-streaming.load-balancer.max-memory-percent=85",
                        "redis-streaming.load-balancer.max-inflight=1000",
                        "redis-streaming.load-balancer.max-queue=500",
                        "redis-streaming.load-balancer.max-error-rate-percent=10"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getLoadBalancer().getStrategy()).isEqualTo("consistent-hash");
                    assertThat(props.getLoadBalancer().getPreferredRegion()).isEqualTo("us-east");
                    assertThat(props.getLoadBalancer().getPreferredZone()).isEqualTo("zone-a");
                    assertThat(props.getLoadBalancer().getCpuWeight()).isEqualTo(1.5);
                    assertThat(props.getLoadBalancer().getLatencyWeight()).isEqualTo(2.0);
                    assertThat(props.getLoadBalancer().getMemoryWeight()).isEqualTo(0.5);
                    assertThat(props.getLoadBalancer().getInflightWeight()).isEqualTo(1.0);
                    assertThat(props.getLoadBalancer().getQueueWeight()).isEqualTo(0.8);
                    assertThat(props.getLoadBalancer().getErrorRateWeight()).isEqualTo(1.2);
                    assertThat(props.getLoadBalancer().getTargetLatencyMs()).isEqualTo(100);
                    assertThat(props.getLoadBalancer().getMaxCpuPercent()).isEqualTo(80);
                    assertThat(props.getLoadBalancer().getMaxLatencyMs()).isEqualTo(200);
                    assertThat(props.getLoadBalancer().getMaxMemoryPercent()).isEqualTo(85);
                    assertThat(props.getLoadBalancer().getMaxInflight()).isEqualTo(1000);
                    assertThat(props.getLoadBalancer().getMaxQueue()).isEqualTo(500);
                    assertThat(props.getLoadBalancer().getMaxErrorRatePercent()).isEqualTo(10);
                });
    }

    @Test
    void testLoadBalancerPropertiesDefaults() {
        propertiesRunner.withPropertyValues("redis-streaming.redis.address=redis://localhost:6379")
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getLoadBalancer().getStrategy()).isEqualTo("scored");
                    assertThat(props.getLoadBalancer().getCpuWeight()).isEqualTo(1.0);
                    assertThat(props.getLoadBalancer().getLatencyWeight()).isEqualTo(1.0);
                    assertThat(props.getLoadBalancer().getMemoryWeight()).isEqualTo(0.0);
                    assertThat(props.getLoadBalancer().getInflightWeight()).isEqualTo(0.0);
                    assertThat(props.getLoadBalancer().getQueueWeight()).isEqualTo(0.0);
                    assertThat(props.getLoadBalancer().getErrorRateWeight()).isEqualTo(0.0);
                });
    }

    @Test
    void testRateLimitPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.window-ms=5000",
                        "redis-streaming.ratelimit.limit=100",
                        "redis-streaming.ratelimit.backend=redis",
                        "redis-streaming.ratelimit.default-name=my-limiter"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRatelimit().isEnabled()).isTrue();
                    assertThat(props.getRatelimit().getWindowMs()).isEqualTo(5000);
                    assertThat(props.getRatelimit().getLimit()).isEqualTo(100);
                    assertThat(props.getRatelimit().getBackend()).isEqualTo("redis");
                    assertThat(props.getRatelimit().getDefaultName()).isEqualTo("my-limiter");
                });
    }

    @Test
    void testRateLimitPoliciesPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.ratelimit.policies.api1.algorithm=sliding",
                        "redis-streaming.ratelimit.policies.api1.window-ms=1000",
                        "redis-streaming.ratelimit.policies.api1.limit=10",
                        "redis-streaming.ratelimit.policies.api1.backend=memory",
                        "redis-streaming.ratelimit.policies.api2.algorithm=token-bucket",
                        "redis-streaming.ratelimit.policies.api2.capacity=100",
                        "redis-streaming.ratelimit.policies.api2.rate-per-second=50",
                        "redis-streaming.ratelimit.policies.api2.algorithm=leaky-bucket",
                        "redis-streaming.ratelimit.policies.api2.capacity=50",
                        "redis-streaming.ratelimit.policies.api2.rate-per-second=25"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRatelimit().getPolicies()).hasSize(2);
                    assertThat(props.getRatelimit().getPolicies()).containsKey("api1");
                    assertThat(props.getRatelimit().getPolicies()).containsKey("api2");

                    var api1Policy = props.getRatelimit().getPolicies().get("api1");
                    assertThat(api1Policy.getAlgorithm()).isEqualTo("sliding");
                    assertThat(api1Policy.getWindowMs()).isEqualTo(1000);
                    assertThat(api1Policy.getLimit()).isEqualTo(10);
                    assertThat(api1Policy.getBackend()).isEqualTo("memory");

                    var api2Policy = props.getRatelimit().getPolicies().get("api2");
                    assertThat(api2Policy.getAlgorithm()).isEqualTo("leaky-bucket");
                    assertThat(api2Policy.getCapacity()).isEqualTo(50);
                    assertThat(api2Policy.getRatePerSecond()).isEqualTo(25);
                });
    }

    @Test
    void testInvokerPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.invoker.max-attempts=5",
                        "redis-streaming.invoker.initial-delay-ms=100",
                        "redis-streaming.invoker.backoff-factor=2.0",
                        "redis-streaming.invoker.max-delay-ms=5000",
                        "redis-streaming.invoker.jitter-ms=50"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getInvoker().getMaxAttempts()).isEqualTo(5);
                    assertThat(props.getInvoker().getInitialDelayMs()).isEqualTo(100);
                    assertThat(props.getInvoker().getBackoffFactor()).isEqualTo(2.0);
                    assertThat(props.getInvoker().getMaxDelayMs()).isEqualTo(5000);
                    assertThat(props.getInvoker().getJitterMs()).isEqualTo(50);
                });
    }

    @Test
    void testInvokerPropertiesDefaults() {
        propertiesRunner.withPropertyValues("redis-streaming.redis.address=redis://localhost:6379")
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getInvoker().getMaxAttempts()).isEqualTo(3);
                    assertThat(props.getInvoker().getInitialDelayMs()).isEqualTo(20);
                    assertThat(props.getInvoker().getBackoffFactor()).isEqualTo(2.0);
                    assertThat(props.getInvoker().getMaxDelayMs()).isEqualTo(200);
                    assertThat(props.getInvoker().getJitterMs()).isEqualTo(20);
                });
    }

    @Test
    void testConfigPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.config.default-group=mygroup",
                        "redis-streaming.config.key-prefix=config:",
                        "redis-streaming.config.enable-key-prefix=true",
                        "redis-streaming.config.history-size=10"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getConfig().getDefaultGroup()).isEqualTo("mygroup");
                    assertThat(props.getConfig().getKeyPrefix()).isEqualTo("config:");
                    assertThat(props.getConfig().isEnableKeyPrefix()).isTrue();
                    assertThat(props.getConfig().getHistorySize()).isEqualTo(10);
                });
    }

    @Test
    void testRegistryPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.registry.heartbeat-interval=60",
                        "redis-streaming.registry.enabled=false"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getRegistry().getHeartbeatInterval()).isEqualTo(60);
                    assertThat(props.getRegistry().isEnabled()).isFalse();
                });
    }

    @Test
    void testDiscoveryPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.discovery.healthy-only=true",
                        "redis-streaming.discovery.enabled=false"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getDiscovery().isHealthyOnly()).isTrue();
                    assertThat(props.getDiscovery().isEnabled()).isFalse();
                });
    }

    @Test
    void testMqPropertiesDefaults() {
        propertiesRunner.withPropertyValues("redis-streaming.redis.address=redis://localhost:6379")
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getDefaultPartitionCount()).isEqualTo(1);
                    assertThat(props.getMq().getWorkerThreads()).isEqualTo(8);
                    assertThat(props.getMq().getSchedulerThreads()).isEqualTo(2);
                    assertThat(props.getMq().getConsumerBatchCount()).isEqualTo(10);
                    assertThat(props.getMq().getConsumerPollTimeoutMs()).isEqualTo(1000);
                });
    }

    @Test
    void testMqPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.default-partition-count=32",
                        "redis-streaming.mq.worker-threads=16",
                        "redis-streaming.mq.scheduler-threads=8",
                        "redis-streaming.mq.consumer-batch-count=20",
                        "redis-streaming.mq.consumer-poll-timeout-ms=10000",
                        "redis-streaming.mq.lease-ttl-seconds=30",
                        "redis-streaming.mq.rebalance-interval-sec=60",
                        "redis-streaming.mq.renew-interval-sec=10",
                        "redis-streaming.mq.key-prefix=test:",
                        "redis-streaming.mq.stream-key-prefix=stream:",
                        "redis-streaming.mq.consumer-name-prefix=consumer-",
                        "redis-streaming.mq.dlq-consumer-suffix=-dlq"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getDefaultPartitionCount()).isEqualTo(32);
                    assertThat(props.getMq().getWorkerThreads()).isEqualTo(16);
                    assertThat(props.getMq().getSchedulerThreads()).isEqualTo(8);
                    assertThat(props.getMq().getConsumerBatchCount()).isEqualTo(20);
                    assertThat(props.getMq().getConsumerPollTimeoutMs()).isEqualTo(10000);
                    assertThat(props.getMq().getLeaseTtlSeconds()).isEqualTo(30);
                    assertThat(props.getMq().getRebalanceIntervalSec()).isEqualTo(60);
                    assertThat(props.getMq().getRenewIntervalSec()).isEqualTo(10);
                    assertThat(props.getMq().getKeyPrefix()).isEqualTo("test:");
                    assertThat(props.getMq().getStreamKeyPrefix()).isEqualTo("stream:");
                    assertThat(props.getMq().getConsumerNamePrefix()).isEqualTo("consumer-");
                    assertThat(props.getMq().getDlqConsumerSuffix()).isEqualTo("-dlq");
                });
    }

    @Test
    void testMqRetryPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.retry-max-attempts=5",
                        "redis-streaming.mq.retry-base-backoff-ms=200",
                        "redis-streaming.mq.retry-max-backoff-ms=10000",
                        "redis-streaming.mq.retry-mover-batch=50",
                        "redis-streaming.mq.retry-mover-interval-sec=30",
                        "redis-streaming.mq.retry-lock-wait-ms=1000",
                        "redis-streaming.mq.retry-lock-lease-ms=5000"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getRetryMaxAttempts()).isEqualTo(5);
                    assertThat(props.getMq().getRetryBaseBackoffMs()).isEqualTo(200);
                    assertThat(props.getMq().getRetryMaxBackoffMs()).isEqualTo(10000);
                    assertThat(props.getMq().getRetryMoverBatch()).isEqualTo(50);
                    assertThat(props.getMq().getRetryMoverIntervalSec()).isEqualTo(30);
                    assertThat(props.getMq().getRetryLockWaitMs()).isEqualTo(1000);
                    assertThat(props.getMq().getRetryLockLeaseMs()).isEqualTo(5000);
                });
    }

    @Test
    void testMqRetentionPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.retention-max-len-per-partition=10000",
                        "redis-streaming.mq.retention-ms=3600000",
                        "redis-streaming.mq.trim-interval-sec=300"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getRetentionMaxLenPerPartition()).isEqualTo(10000);
                    assertThat(props.getMq().getRetentionMs()).isEqualTo(3600000);
                    assertThat(props.getMq().getTrimIntervalSec()).isEqualTo(300);
                });
    }

    @Test
    void testMqDlqPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.dlq-retention-max-len=5000",
                        "redis-streaming.mq.dlq-retention-ms=1800000"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getDlqRetentionMaxLen()).isEqualTo(5000);
                    assertThat(props.getMq().getDlqRetentionMs()).isEqualTo(1800000);
                });
    }

    @Test
    void testMqAckPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.ack-delete-policy=never",
                        "redis-streaming.mq.ackset-ttl-sec=300"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getAckDeletePolicy()).isEqualTo("never");
                    assertThat(props.getMq().getAcksetTtlSec()).isEqualTo(300);
                });
    }

    @Test
    void testMqBrokerPropertiesBinding() {
        propertiesRunner.withPropertyValues(
                        "redis-streaming.redis.address=redis://localhost:6379",
                        "redis-streaming.mq.broker.type=jdbc"
                )
                .run(ctx -> {
                    RedisStreamingProperties props = ctx.getBean(RedisStreamingProperties.class);
                    assertThat(props.getMq().getBroker().getType()).isEqualTo("jdbc");
                });
    }

    // Test configuration classes

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisStreamingProperties.class)
    static class RedisStreamingPropertiesConfig {}
}
