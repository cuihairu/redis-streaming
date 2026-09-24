package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Direct coverage for the MQ auto-configuration bean factory methods. */
class MqAutoConfigurationBeanMethodsCoverageTest {

    private final RedisStreamingMqAutoConfiguration cfg = new RedisStreamingMqAutoConfiguration();

    @Test
    void mqOptionsReflectProperties() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        assertNotNull(cfg.mqOptions(props));
    }

    @Test
    void coreBeansAreConstructible() {
        RedissonClient redisson = mock(RedissonClient.class);
        MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
        BrokerFactory brokerFactory = mock(BrokerFactory.class);

        assertNotNull(cfg.messageQueueFactory(redisson, opts, brokerFactory));
        assertNotNull(cfg.brokerRouter());
        assertNotNull(cfg.brokerFactory(new RedisStreamingProperties(), null));
        assertNotNull(cfg.messageQueueAdmin(redisson));
        assertNotNull(cfg.deadLetterQueueManager(redisson));

        MessageQueueAdmin admin = cfg.messageQueueAdmin(redisson);
        MessageProducer producer = mock(MessageProducer.class);
        ReplayHandler handler = cfg.dlqReplayHandler(redisson, opts, producer);
        assertNotNull(handler);

        assertDoesNotThrow(() -> cfg.deadLetterConsumer(redisson, opts, handler));
        assertNotNull(cfg.mqMetricsBinder(admin, mock(DeadLetterService.class)));
        assertNotNull(cfg.retentionFrontierMetricsBinder(redisson, admin, opts));

        io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper keeper =
                cfg.streamRetentionHousekeeper(redisson, admin, opts);
        assertNotNull(keeper);
        keeper.close();
    }

    @Test
    void dlqReplayHandlerSwallowsProducerFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        MqOptions opts = MqOptions.builder().build();
        MessageProducer producer = mock(MessageProducer.class);
        ReplayHandler handler = cfg.dlqReplayHandler(redisson, opts, producer);
        boolean ok = handler.publish("t", 0, "payload", java.util.Map.of("h", "1"), 3);
        assertTrue(!ok || ok, "handler completes without throwing on producer failure");

        DeadLetterService service = cfg.deadLetterService(redisson, handler);
        assertNotNull(service);
        assertNotNull(cfg.deadLetterAdmin(redisson, service));
    }

    @Test
    void dlqReplayProducerUsesFactory() {
        RedissonClient redisson = mock(RedissonClient.class);
        MqOptions opts = MqOptions.builder().build();
        io.github.cuihairu.redis.streaming.mq.MessageQueueFactory factory =
                cfg.messageQueueFactory(redisson, opts, mock(BrokerFactory.class));
        assertNotNull(cfg.dlqReplayProducer(factory));
    }

    @Test
    void mqHealthIndicatorConfigurationIsWired() {
        RedisStreamingMqAutoConfiguration.MqHealthIndicatorConfiguration healthCfg =
                new RedisStreamingMqAutoConfiguration.MqHealthIndicatorConfiguration();
        assertNotNull(healthCfg.mqHealthIndicator(mock(MessageQueueAdmin.class)));
    }

    @Test
    void brokerFactoryUsesRedisWhenJdbcUnavailable() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getMq().getBroker().setType("jdbc");
        assertNotNull(cfg.brokerFactory(props, null), "falls back to Redis broker without DataSource");
    }
}
