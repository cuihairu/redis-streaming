package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.storm.Storms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;

/** Storm coverage for the retention-frontier gauge binder (happy and failing Redis). */
class RetentionFrontierBinderStormTest {

    @Test
    void binderRegistersAndSurvivesFailures() {
        RetentionFrontierMetricsBinder happy = Storms.constructing(
                () -> new RetentionFrontierMetricsBinder(Storms.deep(RedissonClient.class),
                        Storms.deep(MessageQueueAdmin.class), MqOptions.builder().build()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        happy.bindTo(registry);
        assertTrue(Storms.storm(happy, null) >= 0);

        RetentionFrontierMetricsBinder failing = Storms.constructing(
                () -> new RetentionFrontierMetricsBinder(Storms.exploding(RedissonClient.class),
                        Storms.exploding(MessageQueueAdmin.class), MqOptions.builder().build()));
        SimpleMeterRegistry reg2 = new SimpleMeterRegistry();
        failing.bindTo(reg2);
        Storms.storm(failing, null);
    }
}
