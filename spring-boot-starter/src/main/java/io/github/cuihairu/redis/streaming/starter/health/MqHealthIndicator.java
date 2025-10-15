package io.github.cuihairu.redis.streaming.starter.health;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Simple MQ HealthIndicator: checks admin list and reports topic count.
 */
public class MqHealthIndicator implements HealthIndicator {

    private final MessageQueueAdmin admin;

    public MqHealthIndicator(MessageQueueAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        try {
            int topics = admin.listAllTopics().size();
            return Health.up().withDetail("topics", topics).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}

