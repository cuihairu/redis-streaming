package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.NamedRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.metrics.MqMicrometerCollector;
import io.github.cuihairu.redis.streaming.starter.metrics.RateLimitMicrometerCollector;
import io.github.cuihairu.redis.streaming.starter.metrics.ReliabilityMicrometerCollector;
import io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring context auto-configuration loading tests for {@link RedisStreamingAutoConfiguration}
 * and the five feature-scoped configs (MQ / Registry / ConfigService / Discovery / RateLimit):
 * default loading, per-feature {@code @ConditionalOnProperty} gating, property binding into
 * the emitted beans, and user-bean override priorities.
 *
 * <p>No Redis is contacted in the default scenarios: a mocked {@link RedissonClient} plays
 * the project's own client, and mock {@code NamingService}/{@code ConfigService} beans
 * (declared in {@link UserCoreBeans}) suppress the real Redis-backed services through
 * {@code @ConditionalOnMissingBean}. The real {@code redissonClient} factory method connects
 * eagerly, so that scenario is assumption-guarded on a reachable local Redis and skipped
 * cleanly otherwise.
 */
class RedisStreamingAutoConfigurationLoadingTest {

    /** The per-test "user" client; {@link #base()} refreshes it for every new runner. */
    private RedissonClient userClient;

    /** Runner with the core auto-config + a user-provided (mock) RedissonClient. */
    private ApplicationContextRunner base() {
        userClient = mock(RedissonClient.class);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisStreamingAutoConfiguration.class))
                .withBean(RedissonClient.class, () -> userClient);
    }

    // ---------------------------------------------------------------- default loading

    @Test
    void allFeaturesLoadByDefaultWithAUserProvidedClient() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedissonClient.class);
                    // the user-provided client must win over the auto-config one
                    assertThat(context.getBean(RedissonClient.class)).isSameAs(userClient);

                    // MQ feature (matchIfMissing default)
                    assertThat(context).hasSingleBean(MqOptions.class);
                    assertThat(context).hasSingleBean(MessageQueueAdmin.class);
                    assertThat(context).hasSingleBean(DeadLetterService.class);
                    assertThat(context).hasSingleBean(MessageProducer.class);
                    assertThat(context).hasBean("dlqReplayProducer");
                    assertThat(context).hasSingleBean(ReplayHandler.class);

                    // Registry / Discovery / Config meta-beans are the user's mocks
                    assertThat(context.getBean(NamingService.class).getClass().getSimpleName())
                            .contains("MockitoMock");
                    // getBean(ServiceDiscovery.class) is ambiguous here: the NamingService
                    // interface extends ServiceDiscovery, so the naming mock matches both
                    assertThat(context.getBean("userServiceDiscovery").getClass().getSimpleName())
                            .contains("MockitoMock");
                    assertThat(context.getBean(ConfigService.class).getClass().getSimpleName())
                            .contains("MockitoMock");
                    // …but the registry feature still wires its own lower-level beans
                    // (the default load-balancer strategy is "scored", not WRR)
                    assertThat(context).hasSingleBean(ClientInvoker.class);
                    assertThat(context).hasSingleBean(LoadBalancer.class);

                    // RateLimit must be explicitly enabled (no matchIfMissing)
                    assertThat(context).hasSingleBean(RateLimiterRegistry.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                });
    }

    // ---------------------------------------------------------------- conditional gating

    @Test
    void mqCanBeDisabledWhileOtherFeaturesStayUp() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.mq.enabled=false",
                        "redis-streaming.registry.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MqOptions.class);
                    assertThat(context).doesNotHaveBean(MessageQueueAdmin.class);
                    assertThat(context).doesNotHaveBean(MessageProducer.class);
                    // by name: the user mock bean is also of this type and stays
                    assertThat(context).doesNotHaveBean("streamRetentionHousekeeper");
                    // the other features are untouched
                    assertThat(context).hasSingleBean(ClientInvoker.class);
                    assertThat(context).hasSingleBean(ConfigService.class);
                });
    }

    @Test
    void registryCanBeDisabledIndependentlyOfMq() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.registry.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientInvoker.class);
                    assertThat(context).doesNotHaveBean(LoadBalancer.class);
                    assertThat(context).doesNotHaveBean("clientSelector");
                    // MQ is untouched
                    assertThat(context).hasSingleBean(MqOptions.class);
                });
    }

    @Test
    void discoveryFeatureGatingSuppressesItsOwnBeanOnly() {
        // enabled (default): the auto bean named "serviceDiscovery" is present (alongside
        // the user's mock) because nothing of the tuple {NamingService, ServiceDiscovery}
        // was missing at condition time… the user mocks suppress it, so flip the flag:
        base().withUserConfiguration(UserCoreBeans.class)
                .run(context -> assertThat(context).doesNotHaveBean("serviceDiscovery"));

        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.discovery.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("serviceDiscovery"));
    }

    @Test
    void configFeatureCanBeDisabledIndependently() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.config.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("configService"));

        // enabled: the feature defers to the user's mock (single ConfigService bean)
        base().withUserConfiguration(UserCoreBeans.class)
                .run(context -> assertThat(context).hasSingleBean(ConfigService.class));
    }

    @Test
    void rateLimitIsDisabledByDefaultAndOptInViaProperty() {
        // no matchIfMissing on the rate-limit configuration: absent property == absent beans
        base().withUserConfiguration(UserCoreBeans.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RateLimiterRegistry.class);
                    assertThat(context).doesNotHaveBean(RateLimiter.class);
                });

        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.limit=5")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiterRegistry.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                });
    }

    // ---------------------------------------------------------------- property binding

    @Test
    void mqPropertiesBindIntoTheEmittedMqOptionsBean() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.mq.worker-threads=7",
                        "redis-streaming.mq.default-partition-count=3",
                        "redis-streaming.mq.key-prefix=rs:",
                        "redis-streaming.mq.retry-max-attempts=5",
                        "redis-streaming.mq.lease-ttl-seconds=42")
                .run(context -> {
                    MqOptions opts = context.getBean(MqOptions.class);
                    assertThat(opts.getWorkerThreads()).isEqualTo(7);
                    assertThat(opts.getDefaultPartitionCount()).isEqualTo(3);
                    assertThat(opts.getKeyPrefix()).isEqualTo("rs:");
                    assertThat(opts.getRetryMaxAttempts()).isEqualTo(5);
                    assertThat(opts.getLeaseTtlSeconds()).isEqualTo(42);
                });
    }

    @Test
    void registryPropertiesSelectTheStrategyAndBindHeartbeatInterval() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.registry.enabled=true",
                        "redis-streaming.registry.heartbeat-interval=11",
                        "redis-streaming.load-balancer.strategy=wrr")
                .run(context -> {
                    assertThat(context.getBean(LoadBalancer.class))
                            .isInstanceOf(WeightedRoundRobinLoadBalancer.class);
                    assertThat(context).hasSingleBean(ClientInvoker.class);
                });
    }

    @Test
    void jdbcBrokerRequestUsesTheUserDataSource() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("redis-streaming.mq.broker.type=jdbc")
                .run(context -> {
                    Object factory = context.getBean("brokerFactory");
                    assertThat(factory.getClass().getSimpleName()).isEqualTo("JdbcBrokerFactory");
                });
    }

    @Test
    void rateLimitPoliciesBindIntoTheRegistryWithNamedLimiters() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true",
                        "redis-streaming.ratelimit.backend=memory",
                        "redis-streaming.ratelimit.default-name=default",
                        "redis-streaming.ratelimit.policies.default.limit=2",
                        "redis-streaming.ratelimit.policies.one.limit=7",
                        "redis-streaming.ratelimit.policies.one.window-ms=500",
                        "redis-streaming.ratelimit.policies.leaky.algorithm=leaky-bucket",
                        "redis-streaming.ratelimit.policies.unknown-algo.algorithm=quantum",
                        "redis-streaming.ratelimit.policies.unknown-algo.limit=9")
                .run(context -> {
                    RateLimiterRegistry registry = context.getBean(RateLimiterRegistry.class);
                    // every declared policy is registered by name (unknown algorithms fall
                    // back to a sliding limiter instead of failing startup), plus the
                    // default entry the putIfAbsent guarantees
                    assertThat(registry.all()).containsKeys("default", "one", "leaky", "unknown-algo");
                    assertThat(registry.get("default")).isInstanceOf(NamedRateLimiter.class);
                    assertThat(registry.get("leaky")).isInstanceOf(NamedRateLimiter.class);
                    // @Primary rateLimiter resolves the defaultName entry
                    assertThat(context.getBean(RateLimiter.class)).isSameAs(registry.get("default"));
                });
    }

    // ---------------------------------------------------------------- bean override

    @Test
    void userProvidedBeansOverrideEveryFeatureDefault() {
        base().withUserConfiguration(OverrideUserConfig.class)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true")
                .run(context -> {
                    assertThat(context.getBean(MqOptions.class).getRetryMaxAttempts()).isEqualTo(99);
                    assertThat(context.getBean(NamingService.class).getClass().getSimpleName())
                            .contains("MockitoMock");
                    // getBean(ServiceDiscovery.class) is ambiguous here: the NamingService
                    // interface extends ServiceDiscovery, so the naming mock matches both
                    assertThat(context.getBean("userServiceDiscovery").getClass().getSimpleName())
                            .contains("MockitoMock");
                    assertThat(context.getBean(ConfigService.class).getClass().getSimpleName())
                            .contains("MockitoMock");
                    // the user RateLimiter suppresses the @Primary auto one but not the registry
                    assertThat(context.getBean(RateLimiter.class).getClass().getSimpleName())
                            .contains("MockitoMock");
                    assertThat(context).hasSingleBean(RateLimiterRegistry.class);
                    // NamingService present ⇒ the discovery auto bean must be skipped
                    // (@ConditionalOnMissingBean lists both NamingService and ServiceDiscovery)
                    assertThat(context).doesNotHaveBean("serviceDiscovery");
                });
    }

    @Test
    void aUserServiceDiscoveryAloneSuppressesTheDiscoveryDefault() {
        // no NamingService mock here: the registry's own namingService comes up (its start()
        // only schedules daemon executors), yet its presence in the tuple still suppresses
        // the discovery-scoped bean
        base().withUserConfiguration(ServiceDiscoveryOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(NamingService.class);
                    assertThat(context).doesNotHaveBean("serviceDiscovery");
                    // namingService (NamingService extends ServiceDiscovery) + the user mock
                    assertThat(context.getBeansOfType(ServiceDiscovery.class)).hasSize(2);
                });
    }

    // ---------------------------------------------------------------- real client

    @Test
    void theAutoConfiguredRedissonClientIsBuiltFromBoundProperties() {
        // Redisson.create() connects eagerly, so this only runs against a live local Redis;
        // it is skipped (not failed) on environments without one
        Assumptions.assumeTrue(redisReachable(), "no local Redis on 127.0.0.1:6379 to build the real client against");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisStreamingAutoConfiguration.class))
                .withPropertyValues("redis-streaming.redis.address=redis://127.0.0.1:6379",
                        "redis-streaming.redis.database=2",
                        "redis-streaming.mq.enabled=false",
                        "redis-streaming.registry.enabled=false",
                        "redis-streaming.discovery.enabled=false",
                        "redis-streaming.config.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedissonClient.class);
                    RedissonClient client = context.getBean(RedissonClient.class);
                    assertThat(client.getClass().getSimpleName()).isEqualTo("Redisson");
                    client.shutdown();
                });
    }

    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- micrometer

    @Test
    void micrometerBeansAreCreatedWhenARegistryBeanIsPresent() {
        base().withUserConfiguration(UserCoreBeans.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimitMicrometerCollector.class);
                    assertThat(context).hasSingleBean(MqMicrometerCollector.class);
                    assertThat(context).hasSingleBean(ReliabilityMicrometerCollector.class);
                });
    }

    @Test
    void micrometerBeansAreAbsentWithoutAMeterRegistryBean() {
        // micrometer-core is on the classpath (@ConditionalOnClass passes) but no
        // MeterRegistry bean exists, so the @ConditionalOnBean collectors must stay away
        base().withUserConfiguration(UserCoreBeans.class)
                .withPropertyValues("redis-streaming.ratelimit.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RateLimitMicrometerCollector.class);
                    assertThat(context).doesNotHaveBean(MqMicrometerCollector.class);
                    assertThat(context).doesNotHaveBean(ReliabilityMicrometerCollector.class);
                });
    }

    // ---------------------------------------------------------------- user configs

    /**
     * The user side of the context: mock services that would otherwise start Redis-backed
     * schedulers, proving the {@code @ConditionalOnMissingBean} overrides take precedence.
     * The naming mock is deliberately named {@code namingService} — the registry config's
     * {@code clientSelector}/{@code clientInvoker} inject it via {@code @Qualifier}.
     */
    @Configuration(proxyBeanMethods = false)
    static class UserCoreBeans {

        @Bean("namingService")
        static NamingService userNamingService() {
            return mock(NamingService.class);
        }

        @Bean
        static ServiceDiscovery userServiceDiscovery() {
            return mock(ServiceDiscovery.class);
        }

        @Bean
        static ConfigService userConfigService() {
            return mock(ConfigService.class);
        }

        @Bean
        static StreamRetentionHousekeeper userRetentionHousekeeper() {
            return mock(StreamRetentionHousekeeper.class);
        }
    }

    /** Extends the core overrides with MQ options and a {@code @Primary} rate limiter. */
    @Configuration(proxyBeanMethods = false)
    @Import(UserCoreBeans.class)
    static class OverrideUserConfig {

        @Bean("mqOptions")
        static MqOptions mqOptions() {
            return MqOptions.builder().retryMaxAttempts(99).build();
        }

        @Bean
        static RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }
    }

    /** Only a discovery mock — exercises the {@code NamingService} leg of the tuple condition. */
    @Configuration(proxyBeanMethods = false)
    static class ServiceDiscoveryOnlyConfig {

        @Bean
        static ServiceDiscovery userServiceDiscovery() {
            return mock(ServiceDiscovery.class);
        }
    }
}