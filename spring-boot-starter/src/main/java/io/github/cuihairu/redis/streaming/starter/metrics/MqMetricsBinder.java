package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterService;

import java.util.List;

/**
 * Micrometer binder for MQ high-level gauges. Designed to be low-impact and aggregated.
 * Gauges:
 * - redis_streaming_mq_topics_total
 * - redis_streaming_mq_messages_total (sum of lengths across topics)
 * - redis_streaming_mq_dlq_total (sum of dlq sizes across topics)
 */
public class MqMetricsBinder implements io.micrometer.core.instrument.binder.MeterBinder {

    private final MessageQueueAdmin admin;
    private final DeadLetterService dlqService;

    public MqMetricsBinder(MessageQueueAdmin admin, DeadLetterService dlqService) {
        this.admin = admin;
        this.dlqService = dlqService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Function-based gauges (compute on access). For heavy usage consider caching.
        Gauge.builder("redis_streaming_mq_topics_total", () -> {
                    return (double) admin.listAllTopics().size();
                })
                .description("Total number of MQ topics (from registry)")
                .register(registry);

        Gauge.builder("redis_streaming_mq_messages_total", () -> {
                    long sum = 0;
                    for (String t : admin.listAllTopics()) {
                        QueueInfo info = admin.getQueueInfo(t);
                        if (info != null && info.isExists()) sum += info.getLength();
                    }
                    return (double) sum;
                })
                .description("Total messages length across all topics")
                .register(registry);

        Gauge.builder("redis_streaming_mq_dlq_total", () -> {
                    long sum = 0;
                    for (String t : admin.listAllTopics()) {
                        sum += dlqService.size(t);
                    }
                    return (double) sum;
                })
                .description("Total dead-letter messages across all topics")
                .register(registry);
    }
}
