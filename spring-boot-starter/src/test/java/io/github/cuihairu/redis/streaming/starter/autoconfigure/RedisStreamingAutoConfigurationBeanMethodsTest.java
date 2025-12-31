package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.reliability.dlq.ReplayHandler;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ConsistentHashLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ScoredLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRandomLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisStreamingAutoConfigurationBeanMethodsTest {

    @Test
    void registryLoadBalancerSupportsMultipleStrategies() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamingAutoConfiguration.RegistryConfiguration cfg = new RedisStreamingAutoConfiguration.RegistryConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();

        props.getLoadBalancer().setStrategy("wrr");
        LoadBalancer wrr = cfg.loadBalancer(redisson, props);
        assertInstanceOf(WeightedRoundRobinLoadBalancer.class, wrr);

        props.getLoadBalancer().setStrategy("weighted-random");
        LoadBalancer wr = cfg.loadBalancer(redisson, props);
        assertInstanceOf(WeightedRandomLoadBalancer.class, wr);

        props.getLoadBalancer().setStrategy("consistent-hash");
        LoadBalancer ch = cfg.loadBalancer(redisson, props);
        assertInstanceOf(ConsistentHashLoadBalancer.class, ch);

        props.getLoadBalancer().setStrategy("scored");
        LoadBalancer scored = cfg.loadBalancer(redisson, props);
        assertInstanceOf(ScoredLoadBalancer.class, scored);

        props.getLoadBalancer().setStrategy("unknown");
        LoadBalancer fallback = cfg.loadBalancer(redisson, props);
        assertInstanceOf(ScoredLoadBalancer.class, fallback);
    }

    @Test
    void brokerFactoryUsesJdbcWhenRequestedAndDataSourceProvided() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getMq().getBroker().setType("jdbc");

        DataSource ds = mock(DataSource.class);
        Object factory = cfg.brokerFactory(props, ds);
        assertEquals("JdbcBrokerFactory", factory.getClass().getSimpleName());
    }

    @Test
    void brokerFactoryFallsBackToRedisWhenJdbcRequestedButNoDataSource() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getMq().getBroker().setType("jdbc");

        Object factory = cfg.brokerFactory(props, null);
        assertEquals("RedisBrokerFactory", factory.getClass().getSimpleName());
    }

    @Test
    void dlqReplayHandlerPublishesWithForcedPartitionHeader() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();

        RedissonClient redisson = mock(RedissonClient.class);
        MqOptions opts = MqOptions.builder().streamKeyPrefix("stream:topic").build();
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture("1-0"));

        ReplayHandler handler = cfg.dlqReplayHandler(redisson, opts, producer);

        Map<String, String> headers = new HashMap<>();
        headers.put("x", "y");

        boolean ok = handler.publish("topicA", 3, "payload", headers, 7);
        assertTrue(ok);

        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message sent = captor.getValue();
        assertEquals("topicA", sent.getTopic());
        assertEquals("payload", sent.getPayload());
        assertNotNull(sent.getHeaders());
        assertEquals("y", sent.getHeaders().get("x"));
        assertEquals("3", sent.getHeaders().get(MqHeaders.FORCE_PARTITION_ID));
        assertEquals(7, sent.getMaxRetries());
    }

    @Test
    void dlqReplayHandlerReturnsFalseOnPublishFailure() {
        RedisStreamingAutoConfiguration.MqConfiguration cfg = new RedisStreamingAutoConfiguration.MqConfiguration();

        RedissonClient redisson = mock(RedissonClient.class);
        MqOptions opts = MqOptions.builder().streamKeyPrefix("stream:topic").build();
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(any(Message.class))).thenThrow(new RuntimeException("boom"));

        ReplayHandler handler = cfg.dlqReplayHandler(redisson, opts, producer);

        assertFalse(handler.publish("topicA", 1, "payload", Map.of(), 1));
    }
}
