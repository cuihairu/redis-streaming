package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisStreamBuilder;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed execution environment.
 *
 * <p>This environment composes the existing modules (mq/state/checkpoint/etc) into a runnable job.
 * Execution is driven by Redis Streams consumer groups (MQ module).</p>
 *
 * <p>Delivery semantics: at-least-once (ack on successful end-to-end processing).</p>
 */
public final class RedisStreamExecutionEnvironment {

    private final RedissonClient redissonClient;
    private final RedisRuntimeConfig config;
    private final MessageQueueFactory mqFactory;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final List<RedisPipelineDefinition> pipelineDefinitions = new ArrayList<>();
    private int pipelineSeq = 0;

    private RedisStreamExecutionEnvironment(RedissonClient redissonClient, RedisRuntimeConfig config) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = config == null ? RedisRuntimeConfig.builder().build() : config;
        this.mqFactory = new MessageQueueFactory(this.redissonClient, this.config.getMqOptions());
    }

    public static RedisStreamExecutionEnvironment create(RedissonClient redissonClient) {
        return new RedisStreamExecutionEnvironment(redissonClient, RedisRuntimeConfig.builder().build());
    }

    public static RedisStreamExecutionEnvironment create(RedissonClient redissonClient, RedisRuntimeConfig config) {
        return new RedisStreamExecutionEnvironment(redissonClient, config);
    }

    /**
     * Create a streaming source backed by MQ (Redis Streams) returning raw {@link Message}.
     */
    public DataStream<Message> fromMqTopic(String topic, String consumerGroup) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        return RedisStreamBuilder.forMqSource(this, config, redissonClient, objectMapper, topic, consumerGroup);
    }

    public void registerPipelineDefinition(RedisPipelineDefinition pipelineDefinition) {
        pipelineDefinitions.add(Objects.requireNonNull(pipelineDefinition, "pipelineDefinition"));
    }

    /**
     * Start all registered pipelines asynchronously.
     */
    public RedisJobClient executeAsync() {
        List<MessageConsumer> consumers = new ArrayList<>();
        List<RedisPipelineRunner<?>> runners = new ArrayList<>();

        for (RedisPipelineDefinition def : pipelineDefinitions) {
            RedisPipeline<?> p = def.freeze();
            RedisPipelineRunner<?> runner = p.buildRunner();
            runners.add(runner);

            String consumerName = config.getJobName() + "-" + (++pipelineSeq);
            MessageConsumer consumer = mqFactory.createConsumer(consumerName);
            consumers.add(consumer);

            MessageHandler handler = (Message m) -> {
                try {
                    boolean ok = runner.handle(m);
                    return ok ? MessageHandleResult.SUCCESS : MessageHandleResult.RETRY;
                } catch (Exception e) {
                    return MessageHandleResult.RETRY;
                }
            };

            consumer.subscribe(p.topic(), p.consumerGroup(), handler, p.subscriptionOptions());
            consumer.start();
        }

        return new RedisJobClient() {
            private final CountDownLatch stopped = new CountDownLatch(1);
            private volatile boolean canceled = false;

            @Override
            public void cancel() {
                if (canceled) return;
                canceled = true;
                try {
                    for (MessageConsumer c : consumers) {
                        try {
                            c.stop();
                        } catch (Exception ignore) {
                        }
                        try {
                            c.close();
                        } catch (Exception ignore) {
                        }
                    }
                    for (RedisPipelineRunner<?> r : runners) {
                        try {
                            r.close();
                        } catch (Exception ignore) {
                        }
                    }
                } finally {
                    stopped.countDown();
                }
            }

            @Override
            public boolean awaitTermination(Duration timeout) throws InterruptedException {
                Duration t = timeout == null ? Duration.ZERO : timeout;
                return stopped.await(Math.max(0, t.toMillis()), TimeUnit.MILLISECONDS);
            }
        };
    }
}
