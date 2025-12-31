package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsGlobal;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetrics;
import io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.InMemorySlidingWindowRateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiter;
import io.github.cuihairu.redis.streaming.reliability.ratelimit.RateLimiterRegistry;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisStreamingAutoConfigurationMoreBeanMethodsTest {

    @Test
    void registryNamingServiceStartsAndAppliesMetricsGlobalConfig() {
        MetricsGlobal.setDefaultConfig(null);
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamingAutoConfiguration.RegistryConfiguration cfg = new RedisStreamingAutoConfiguration.RegistryConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getRegistry().getMetrics().setEnabled(Set.of("cpu"));
        props.getRegistry().getMetrics().setIntervals(Map.of("cpu", Duration.ofSeconds(3)));
        props.getRegistry().getMetrics().setDefaultInterval(Duration.ofSeconds(9));
        props.getRegistry().getMetrics().setImmediateUpdateOnSignificantChange(false);
        props.getRegistry().getMetrics().setTimeout(Duration.ofMillis(123));

        try {
            NamingService namingService = cfg.namingService(redisson, props);
            assertTrue(namingService.isRunning());

            MetricsConfig mc = MetricsGlobal.getOrDefault();
            assertEquals(Set.of("cpu"), mc.getEnabledMetrics());
            assertEquals(Duration.ofSeconds(3), mc.getCollectionIntervals().get("cpu"));
            assertEquals(Duration.ofSeconds(9), mc.getDefaultCollectionInterval());
            assertEquals(Duration.ofMillis(123), mc.getCollectionTimeout());
            assertEquals(false, mc.isImmediateUpdateOnSignificantChange());

            namingService.stop();
        } finally {
            MetricsGlobal.setDefaultConfig(null);
        }
    }

    @Test
    void discoveryServiceIsStartedByBeanMethod() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamingAutoConfiguration.DiscoveryConfiguration cfg = new RedisStreamingAutoConfiguration.DiscoveryConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getDiscovery().setHealthyOnly(true);

        ServiceDiscovery discovery = cfg.serviceDiscovery(redisson, props);
        assertTrue(discovery.isRunning());
        discovery.stop();
    }

    @Test
    void configServiceBeanUsesConfiguredHistorySizeAndPrefix() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamingAutoConfiguration.ConfigServiceConfiguration cfg = new RedisStreamingAutoConfiguration.ConfigServiceConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getConfig().setHistorySize(77);
        props.getConfig().setKeyPrefix("cfg:");
        props.getConfig().setEnableKeyPrefix(false);

        ConfigService configService = cfg.configService(redisson, props);
        assertTrue(configService.isRunning());
        assertInstanceOf(RedisConfigService.class, configService);

        Field maxHistorySize = RedisConfigService.class.getDeclaredField("maxHistorySize");
        maxHistorySize.setAccessible(true);
        assertEquals(77, maxHistorySize.getInt(configService));

        Field internalConfig = RedisConfigService.class.getDeclaredField("config");
        internalConfig.setAccessible(true);
        Object cfgObj = internalConfig.get(configService);
        assertEquals("cfg:", cfgObj.getClass().getMethod("getKeyPrefix").invoke(cfgObj));
        assertEquals(false, cfgObj.getClass().getMethod("isEnableKeyPrefix").invoke(cfgObj));
        assertEquals(77, cfgObj.getClass().getMethod("getHistorySize").invoke(cfgObj));

        configService.stop();
    }

    @Test
    void mqOptionsAreMappedFromProperties() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getMq().setDefaultPartitionCount(3);
        props.getMq().setWorkerThreads(9);
        props.getMq().setSchedulerThreads(4);
        props.getMq().setConsumerBatchCount(12);
        props.getMq().setConsumerPollTimeoutMs(250);
        props.getMq().setKeyPrefix("mq:");
        props.getMq().setStreamKeyPrefix("stream:");
        props.getMq().setDefaultConsumerGroup("g1");
        props.getMq().setRetentionMaxLenPerPartition(123);
        props.getMq().setRetentionMs(999);
        props.getMq().setTrimIntervalSec(7);
        props.getMq().setAckDeletePolicy("none");
        props.getMq().setDlqRetentionMaxLen(456);
        props.getMq().setDlqRetentionMs(888);

        MqOptions opts = cfg.mqOptions(props);
        assertEquals(3, opts.getDefaultPartitionCount());
        assertEquals(9, opts.getWorkerThreads());
        assertEquals(4, opts.getSchedulerThreads());
        assertEquals(12, opts.getConsumerBatchCount());
        assertEquals(250, opts.getConsumerPollTimeoutMs());
        assertEquals("mq:", opts.getKeyPrefix());
        assertEquals("stream:", opts.getStreamKeyPrefix());
        assertEquals("g1", opts.getDefaultConsumerGroup());
        assertEquals(123, opts.getRetentionMaxLenPerPartition());
        assertEquals(999, opts.getRetentionMs());
        assertEquals(7, opts.getTrimIntervalSec());
        assertEquals("none", opts.getAckDeletePolicy());
        assertEquals(456, opts.getDlqRetentionMaxLen());
        assertEquals(888, opts.getDlqRetentionMs());
    }

    @Test
    void mqBeansCanBeCreatedAndMetricsCollectorsInstalled() {
        var oldMq = MqMetrics.get();
        var oldRetention = RetentionMetrics.get();
        var oldReliability = ReliabilityMetrics.get();
        var oldRateLimit = RateLimitMetrics.get();

        try {
            RedisStreamingAutoConfiguration auto = new RedisStreamingAutoConfiguration();
            RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();

            io.micrometer.core.instrument.simple.SimpleMeterRegistry reg =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

            var mqCollector = cfg.mqMicrometerCollector(reg);
            cfg.installMqCollector(mqCollector);
            assertEquals(mqCollector, MqMetrics.get());

            var retentionCollector = cfg.retentionMicrometerCollector(reg);
            cfg.installRetentionCollector(retentionCollector);
            assertEquals(retentionCollector, RetentionMetrics.get());

            var reliabilityCollector = cfg.reliabilityMicrometerCollector(reg);
            cfg.installReliabilityCollector(reliabilityCollector);
            assertEquals(reliabilityCollector, ReliabilityMetrics.get());

            var rateLimitCollector = auto.rateLimitMicrometerCollector(reg);
            auto.installRateLimitCollector(rateLimitCollector);
            assertEquals(rateLimitCollector, RateLimitMetrics.get());
        } finally {
            MqMetrics.setCollector(oldMq);
            RetentionMetrics.setCollector(oldRetention);
            ReliabilityMetrics.setCollector(oldReliability);
            RateLimitMetrics.setCollector(oldRateLimit);
        }
    }

    @Test
    void dlqReplayProducerBeanDelegatesToFactory() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();
        MessageQueueFactory factory = mock(MessageQueueFactory.class);
        MessageProducer producer = mock(MessageProducer.class);
        when(factory.createProducer()).thenReturn(producer);

        MessageProducer got = cfg.dlqReplayProducer(factory);
        assertEquals(producer, got);
    }

    @Test
    void streamRetentionHousekeeperBeanCanBeCreatedAndClosed() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(Collections.emptyList());

        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(Collections.emptyList());

        MqOptions opts = MqOptions.builder().trimIntervalSec(1).build();
        try (var hk = cfg.streamRetentionHousekeeper(redisson, admin, opts)) {
            assertNotNull(hk);
        }
    }

    @Test
    void rateLimiterRegistryBuildsFromPoliciesAndDefaultFallbackWorks() {
        RedisStreamingAutoConfiguration.RateLimitConfiguration cfg = new RedisStreamingAutoConfiguration.RateLimitConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        var p1 = new RedisStreamingProperties.RateLimitProperties.Policy();
        p1.setAlgorithm("sliding");
        p1.setBackend("memory");
        p1.setWindowMs(1000);
        p1.setLimit(10);

        var p2 = new RedisStreamingProperties.RateLimitProperties.Policy();
        p2.setAlgorithm("token-bucket");
        p2.setBackend("redis");
        p2.setKeyPrefix("rl:");
        p2.setCapacity(5.0);
        p2.setRatePerSecond(1.0);

        var p3 = new RedisStreamingProperties.RateLimitProperties.Policy();
        p3.setAlgorithm("leaky-bucket");
        p3.setBackend("memory");
        p3.setCapacity(7.0);
        p3.setRatePerSecond(2.0);

        props.getRatelimit().setPolicies(Map.of("a", p1, "b", p2, "c", p3));
        props.getRatelimit().setDefaultName("missing");

        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getScript(eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenReturn(mock(RScript.class));

        RateLimiterRegistry registry = cfg.rateLimiterRegistry(props, redisson);
        assertNotNull(registry.get("a"));
        assertNotNull(registry.get("b"));
        assertNotNull(registry.get("c"));

        RateLimiter chosen = cfg.rateLimiter(registry, props);
        assertNotNull(chosen);
    }

    @Test
    void rateLimiterFallsBackToInMemoryWhenRegistryEmpty() {
        RedisStreamingAutoConfiguration.RateLimitConfiguration cfg = new RedisStreamingAutoConfiguration.RateLimitConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRatelimit().setDefaultName("missing");

        RateLimiter rl = cfg.rateLimiter(new RateLimiterRegistry(Collections.emptyMap()), props);
        assertInstanceOf(InMemorySlidingWindowRateLimiter.class, rl);
    }
}

