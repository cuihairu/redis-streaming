package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterConsumer;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterEntry;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterConsumer;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Adapter to expose reliability DLQ consumer as MQ MessageConsumer. */
public class DlqConsumerAdapter implements MessageConsumer {
    private final DeadLetterConsumer delegate;

    public DlqConsumerAdapter(RedissonClient client, String consumerName, MqOptions options) {
        this.delegate = new RedisDeadLetterConsumer(client, consumerName,
                options != null ? options.getDefaultDlqGroup() : "dlq-group");
        // configure DLQ stream prefix to match MQ if needed
        io.github.cuihairu.redis.streaming.reliability.dlq.DlqKeys.configure(
                options != null ? options.getStreamKeyPrefix() : "stream:topic");
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        delegate.subscribe(topic, entry -> toResult(handler, entry));
    }

    @Override
    public void subscribe(String topic, String group, MessageHandler handler) {
        delegate.subscribe(topic, group, entry -> toResult(handler, entry));
    }

    private DeadLetterConsumer.HandleResult toResult(MessageHandler handler, DeadLetterEntry e) throws Exception {
        Message m = new Message();
        m.setId(e.getId());
        m.setTopic(e.getOriginalTopic());
        m.setPayload(e.getPayload());
        m.setTimestamp(e.getTimestamp()!=null?e.getTimestamp(): Instant.now());
        m.setRetryCount(e.getRetryCount());
        m.setMaxRetries(e.getMaxRetries());
        Map<String,String> hdr = new HashMap<>();
        if (e.getHeaders()!=null) hdr.putAll(e.getHeaders());
        hdr.put("partitionId", Integer.toString(e.getPartitionId()));
        m.setHeaders(hdr);
        MessageHandleResult r = handler.handle(m);
        switch (r) {
            case SUCCESS: return DeadLetterConsumer.HandleResult.SUCCESS;
            case RETRY: return DeadLetterConsumer.HandleResult.RETRY;
            case FAIL:
            case DEAD_LETTER: return DeadLetterConsumer.HandleResult.FAIL;
            default: return DeadLetterConsumer.HandleResult.FAIL;
        }
    }

    @Override public void unsubscribe(String topic) { /* not exposed by delegate; no-op */ }
    @Override public void start() { delegate.start(); }
    @Override public void stop() { delegate.stop(); }
    @Override public void close() { delegate.close(); }
    @Override public boolean isRunning() { return delegate.isRunning(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
}

