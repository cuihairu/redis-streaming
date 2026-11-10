package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;

/**
 * Subtask wiring for the Redis runtime: builds each subtask's message handler (metrics,
 * MDC sampling, defer-ack marking, failure annotation) and starts its partition-pinned
 * consumer. Shared by the initial start loop and {@code scaleParallelism} scale-up.
 */
final class RedisSubtaskLauncher {
    private static final Logger log = LoggerFactory.getLogger(RedisSubtaskLauncher.class);

    private final RedisRuntimeConfig config;

    RedisSubtaskLauncher(RedisRuntimeConfig config) {
        this.config = config;
    }

    MessageHandler createMessageHandler(RedisPipelineRunner<?> runner, RedisPipeline<?> p,
                                                 String consumerName, DeferredAcks deferredAcks) {
        return (Message m) -> {
            long startNs = System.nanoTime();
            // the arriving record's source: a Message carries its topic (RedisMessageConsumer
            // fills it), and the source decides the watermark contribution and dedup scope
            int sourceIndex = p.sourceCount() > 1 ? p.sourceIndex(m == null ? null : m.getTopic()) : 0;
            if (sourceIndex < 0) {
                // unknown topic: never subscribed by us, but resolve defensively to source 0
                sourceIndex = 0;
            }
            String topic = p.topicAt(sourceIndex);
            String group = p.consumerGroupAt(sourceIndex);
            boolean installMdc = config.isMdcEnabled() && shouldInstallMdc(m);
            if (installMdc) {
                try {
                    MDC.put("rs.job", config.getJobName());
                    MDC.put("rs.topic", topic);
                    MDC.put("rs.group", group);
                    MDC.put("rs.consumer", consumerName);
                    if (m != null) {
                        try { MDC.put("rs.id", m.getId()); } catch (Exception ignore) {}
                        try { MDC.put("rs.key", m.getKey()); } catch (Exception ignore) {}
                        try {
                            String pid = m.getHeaders() == null ? null : m.getHeaders().get(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID);
                            if (pid != null) {
                                MDC.put("rs.partition", pid);
                            }
                        } catch (Exception ignore) {
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            try {
                boolean ok = runner.handle(m, sourceIndex);
                try {
                    RedisRuntimeMetrics.get().recordHandleLatency(config.getJobName(), topic, group,
                            (System.nanoTime() - startNs) / 1_000_000);
                    if (ok) {
                        RedisRuntimeMetrics.get().incHandleSuccess(config.getJobName(), topic, group);
                    } else {
                        // this arm returns MessageHandleResult.RETRY below — pin the dimension to it
                        RedisRuntimeMetrics.get().incHandleError(config.getJobName(), topic, group, "retry");
                    }
                } catch (Exception ignore) {
                }
                if (!ok) {
                    return MessageHandleResult.RETRY;
                }
                if (config.isDeferAckUntilCheckpoint()) {
                    markDeferAck(m, deferredAcks, topic, group);
                }
                return MessageHandleResult.SUCCESS;
            } catch (Exception e) {
                annotateRuntimeError(m, group, e);
                try {
                    RedisRuntimeMetrics.get().recordHandleLatency(config.getJobName(), topic, group,
                            (System.nanoTime() - startNs) / 1_000_000);
                    RedisRuntimeMetrics.get().incHandleError(config.getJobName(), topic, group,
                            config.getProcessingErrorResult().name().toLowerCase(java.util.Locale.ROOT));
                } catch (Exception ignore) {
                }
                try {
                    String id = m == null ? null : m.getId();
                    String key = m == null ? null : m.getKey();
                    log.error("Redis runtime pipeline failed (jobName={}, topic={}, group={}, consumer={}, id={}, key={})",
                            config.getJobName(), topic, group, consumerName, id, key, e);
                } catch (Exception ignore) {
                    // best-effort logging only
                }
                return config.getProcessingErrorResult();
            } finally {
                if (installMdc) {
                    try {
                        MDC.remove("rs.id");
                        MDC.remove("rs.key");
                        MDC.remove("rs.partition");
                        MDC.remove("rs.consumer");
                        MDC.remove("rs.group");
                        MDC.remove("rs.topic");
                        MDC.remove("rs.job");
                    } catch (Exception ignore) {
                    }
                }
            }
        };
    }

    /**
     * Subscribe and start one subtask's consumer (handler wiring, metrics, failure
     * surfacing). A dual-source pipeline subscribes one consumer instance to both topics:
     * the handler resolves the source from the Message's topic, so each source keeps its
     * own offsets, defer-ack scope and partition pinning ({@code RedisMessageConsumer} keys
     * its workers by (topic, group, partition)).
     */
    void startSubtaskConsumer(MessageConsumer consumer, String consumerName, RedisPipeline<?> p,
                              int subtask, int parallelism, RedisPipelineRunner<?> runner,
                              DeferredAcks deferredAcks) {
        MessageHandler handler = createMessageHandler(runner, p, consumerName, deferredAcks);
        try {
            // both (all) sources before start(): the consumer's initial rebalance then covers
            // every subscription in one pass
            for (int s = 0; s < p.sourceCount(); s++) {
                io.github.cuihairu.redis.streaming.mq.SubscriptionOptions opts =
                        optionsForSubtask(p.subscriptionOptionsAt(s), parallelism, subtask);
                consumer.subscribe(p.topicAt(s), p.consumerGroupAt(s), handler, opts);
            }
            consumer.start();
            for (int s = 0; s < p.sourceCount(); s++) {
                try {
                    RedisRuntimeMetrics.get().incPipelineStarted(config.getJobName(), p.topicAt(s), p.consumerGroupAt(s));
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            for (int s = 0; s < p.sourceCount(); s++) {
                try {
                    RedisRuntimeMetrics.get().incPipelineStartFailed(config.getJobName(), p.topicAt(s), p.consumerGroupAt(s));
                } catch (Exception ignore) {
                }
            }
            throw e;
        }
    }

    private static io.github.cuihairu.redis.streaming.mq.SubscriptionOptions optionsForSubtask(
            io.github.cuihairu.redis.streaming.mq.SubscriptionOptions opts, int parallelism, int subtask) {
        if (parallelism <= 1) {
            return opts;
        }
        io.github.cuihairu.redis.streaming.mq.SubscriptionOptions.Builder b =
                io.github.cuihairu.redis.streaming.mq.SubscriptionOptions.builder();
        if (opts != null) {
            try {
                Integer bc = opts.getBatchCount();
                if (bc != null) b.batchCount(bc);
            } catch (Exception ignore) {
            }
            try {
                Long to = opts.getPollTimeoutMs();
                if (to != null) b.pollTimeoutMs(to);
            } catch (Exception ignore) {
            }
        }
        b.partitionModulo(parallelism).partitionRemainder(subtask);
        return b.build();
    }

    private void markDeferAck(Message message, DeferredAcks deferredAcks, String topic, String consumerGroup) {
        if (message == null) {
            return;
        }
        try {
            String id = message.getId();
            if (id == null || id.isBlank()) {
                return;
            }
            int pid = -1;
            try {
                if (message.getHeaders() != null) {
                    String s = message.getHeaders().get(MqHeaders.PARTITION_ID);
                    if (s != null && !s.isBlank()) {
                        pid = Integer.parseInt(s);
                    }
                }
            } catch (Exception ignore) {
            }
            if (pid < 0) {
                return;
            }
            java.util.Map<String, String> headers = message.getHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                message.setHeaders(headers);
            } else if (!(headers instanceof java.util.HashMap)) {
                headers = new java.util.HashMap<>(headers);
                message.setHeaders(headers);
            }
            headers.put(MqHeaders.DEFER_ACK, "true");
            deferredAcks.record(topic, consumerGroup, pid, id);
        } catch (Exception ignore) {
        }
    }

    private void annotateRuntimeError(Message message, String consumerGroup, Throwable error) {
        if (message == null) {
            return;
        }
        try {
            java.util.Map<String, String> headers = message.getHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                message.setHeaders(headers);
            } else if (!(headers instanceof java.util.HashMap)) {
                headers = new java.util.HashMap<>(headers);
                message.setHeaders(headers);
            }
            headers.put(RedisRuntimeHeaders.JOB_NAME, config.getJobName());
            headers.put(RedisRuntimeHeaders.CONSUMER_GROUP, consumerGroup);
            if (error != null) {
                headers.put(RedisRuntimeHeaders.ERROR_TYPE, error.getClass().getName());
                String msg = error.getMessage();
                if (msg != null) {
                    headers.put(RedisRuntimeHeaders.ERROR_MESSAGE, StreamIds.truncate(msg, 512));
                }
            }
        } catch (Exception ignore) {
        }
    }

    private boolean shouldInstallMdc(Message m) {
        double r;
        try {
            r = config.getMdcSampleRate();
        } catch (Exception ignore) {
            r = 1.0d;
        }
        if (r >= 1.0d) {
            return true;
        }
        if (r <= 0.0d) {
            return false;
        }
        String id = null;
        try {
            if (m != null && m.getHeaders() != null) {
                id = m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID);
            }
        } catch (Exception ignore) {
        }
        try {
            if ((id == null || id.isBlank()) && m != null) {
                id = m.getId();
            }
        } catch (Exception ignore) {
        }
        int h = id == null ? System.identityHashCode(m) : id.hashCode();
        int bucket = Math.floorMod(h, 10_000);
        int threshold = (int) Math.floor(r * 10_000.0d);
        return bucket < threshold;
    }

    /**
     * Startup-time sanity warnings for the defer-ack configuration: warn when deferring is
     * enabled without periodic checkpointing, and when the drain/interval windows can exceed
     * the MQ claim idle time (messages could be reclaimed while still deferred).
     */
    void warnDeferAckConfiguration() {
        if (!config.isDeferAckUntilCheckpoint()) {
            return;
        }
        Duration interval = config.getCheckpointInterval();
        boolean periodicCheckpoint = interval != null && !interval.isZero() && !interval.isNegative();
        if (!periodicCheckpoint) {
            log.warn("deferAckUntilCheckpoint is enabled but periodic checkpointing is disabled; messages will remain pending until triggerCheckpointNow() is called (jobName={})",
                    config.getJobName());
        }

        long claimIdleMs = 0L;
        try {
            if (config.getMqOptions() != null) {
                claimIdleMs = Math.max(0L, config.getMqOptions().getClaimIdleMs());
            }
        } catch (Exception ignore) {
        }
        if (claimIdleMs <= 0) {
            return;
        }

        Duration drainTimeout = config.getCheckpointDrainTimeout();
        long drainMs = drainTimeout == null ? 0L : Math.max(0L, drainTimeout.toMillis());
        long intervalMs = periodicCheckpoint ? Math.max(0L, interval.toMillis()) : 0L;

        if (drainMs > 0 && drainMs >= claimIdleMs) {
            log.warn("deferAckUntilCheckpoint may be unsafe: checkpointDrainTimeout >= mq.claimIdleMs (jobName={}, checkpointDrainTimeoutMs={}, claimIdleMs={})",
                    config.getJobName(), drainMs, claimIdleMs);
        } else if (intervalMs > 0 && intervalMs + drainMs >= claimIdleMs) {
            log.warn("deferAckUntilCheckpoint may be unsafe: mq.claimIdleMs is smaller than checkpoint interval + drain timeout (jobName={}, checkpointIntervalMs={}, checkpointDrainTimeoutMs={}, claimIdleMs={})",
                    config.getJobName(), intervalMs, drainMs, claimIdleMs);
        }
    }
}
