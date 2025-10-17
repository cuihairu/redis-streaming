package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.partition.Partitioner;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams based message producer implementation
 */
@Slf4j
public class RedisMessageProducer implements MessageProducer {

    private final RedissonClient redissonClient;
    private final TopicRegistry topicRegistry;
    private final TopicPartitionRegistry partitionRegistry;
    private final Partitioner partitioner;
    private final MqOptions options;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final PayloadLifecycleManager payloadLifecycleManager;

    public RedisMessageProducer(RedissonClient redissonClient,
                                Partitioner partitioner,
                                TopicPartitionRegistry partitionRegistry,
                                MqOptions options) {
        this.redissonClient = redissonClient;
        this.partitioner = partitioner;
        this.partitionRegistry = partitionRegistry;
        this.options = options;
        this.topicRegistry = new TopicRegistry(redissonClient, this.options.getKeyPrefix());
        this.payloadLifecycleManager = new PayloadLifecycleManager(redissonClient, this.options);
    }

    @Override
    public CompletableFuture<String> send(Message message) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Ensure topic is registered and meta exists (default to 1 partition)
                topicRegistry.registerTopic(message.getTopic());
                partitionRegistry.ensureTopic(message.getTopic(), options.getDefaultPartitionCount());

                int partitions = partitionRegistry.getPartitionCount(message.getTopic());
                int partitionId;
                // Allow callers (e.g. DLQ replay) to force a specific partition via header
                try {
                    String forced = message.getHeaders() != null ? message.getHeaders().getOrDefault(io.github.cuihairu.redis.streaming.mq.MqHeaders.FORCE_PARTITION_ID, null) : null;
                    if (forced != null) {
                        int fp = Integer.parseInt(forced);
                        partitionId = (fp >= 0 && partitions > 0) ? (fp % partitions) : 0;
                    } else {
                        partitionId = partitioner.partition(message.getKey(), partitions);
                    }
                } catch (Exception ignore) {
                    partitionId = partitioner.partition(message.getKey(), partitions);
                }
                String streamKey = StreamKeys.partitionStream(message.getTopic(), partitionId);
                RStream<String, Object> stream = redissonClient.getStream(streamKey);

                Map<String, Object> data = StreamEntryCodec.buildPartitionEntry(message, partitionId, payloadLifecycleManager);

                // Add message to the selected partition stream
                StreamMessageId messageId = stream.add(StreamAddArgs.entries(data));

                String id = messageId.toString();
                message.setId(id);

                // metrics
                MqMetrics.get().incProduced(message.getTopic(), partitionId);
                log.debug("Message sent to topic '{}' partition {} with ID: {}", message.getTopic(), partitionId, id);
                return id;

            } catch (Exception e) {
                log.error("Failed to send message to topic '{}'", message.getTopic(), e);
                throw new RuntimeException("Failed to send message", e);
            }
        });
    }

    @Override
    public CompletableFuture<String> send(String topic, String key, Object payload) {
        Message message = new Message(topic, key, payload);
        return send(message);
    }

    @Override
    public CompletableFuture<String> send(String topic, Object payload) {
        Message message = new Message(topic, payload);
        return send(message);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Redis message producer closed");
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }
}
