package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The broker write path is a second producer entry (besides RedisMessageProducer), so its
 * produced counter must carry the configured tenant like every other spec'd emit
 * (docs/Metrics.md「tenant 维度规格」: 生产者一律调用 tenant-aware 变体).
 */
class DefaultBrokerTenantMetricsTest {

    private final MqMetricsCollector previous = MqMetrics.get();
    private final List<String> tenants = new CopyOnWriteArrayList<>();
    private final List<String> topics = new CopyOnWriteArrayList<>();

    @AfterEach
    void restoreCollector() {
        MqMetrics.setCollector(previous);
    }

    private MqMetricsCollector tenantRecordingCollector() {
        return new MqMetricsCollector() {
            @Override public void incProduced(String topic, int partitionId) { }
            @Override public void incConsumed(String topic, int partitionId) { }
            @Override public void incAcked(String topic, int partitionId) { }
            @Override public void incRetried(String topic, int partitionId) { }
            @Override public void incDeadLetter(String topic, int partitionId) { }
            @Override public void recordHandleLatency(String topic, int partitionId, long millis) { }

            @Override public void incProduced(String tenant, String topic, int partitionId) {
                tenants.add(tenant);
                topics.add(topic);
            }
        };
    }

    private DefaultBroker brokerWithTenant(MqOptions options) {
        BrokerRouter router = mock(BrokerRouter.class);
        BrokerPersistence persistence = mock(BrokerPersistence.class);
        when(router.routePartition(eq("t1"), eq("k1"), any(), eq(1))).thenReturn(0);
        when(persistence.append(eq("t1"), eq(0), any(Message.class))).thenReturn("10-0");
        return new DefaultBroker(mock(org.redisson.api.RedissonClient.class), options, router, persistence);
    }

    private Message message() {
        Message m = new Message();
        m.setTopic("t1");
        m.setKey("k1");
        m.setHeaders(Collections.singletonMap("h", "v"));
        m.setPayload("p1");
        return m;
    }

    @Test
    void producedCounterCarriesTheConfiguredTenant() {
        MqMetrics.setCollector(tenantRecordingCollector());
        DefaultBroker broker = brokerWithTenant(MqOptions.builder().tenant("acme").defaultPartitionCount(1).build());

        String id = broker.produce(message());

        assertEquals("10-0", id);
        assertEquals(List.of("acme"), tenants);
        assertEquals(List.of("t1"), topics);
    }

    @Test
    void defaultTenantIsNormalizedNotOmitted() {
        MqMetrics.setCollector(tenantRecordingCollector());
        DefaultBroker broker = brokerWithTenant(MqOptions.builder().defaultPartitionCount(1).build());

        broker.produce(message());

        assertEquals(List.of("default"), tenants);
        assertEquals(List.of("t1"), topics);
    }
}
