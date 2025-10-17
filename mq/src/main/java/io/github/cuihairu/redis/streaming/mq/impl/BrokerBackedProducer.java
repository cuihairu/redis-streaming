package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Producer that delegates to a Broker (router + persistence).
 */
public class BrokerBackedProducer implements MessageProducer {
    private final Broker broker;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BrokerBackedProducer(Broker broker) {
        this.broker = broker;
    }

    @Override
    public CompletableFuture<String> send(Message message) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        return CompletableFuture.supplyAsync(() -> broker.produce(message));
    }

    @Override
    public CompletableFuture<String> send(String topic, String key, Object payload) {
        // Message doesn't have (topic,key,payload) ctor without publisher; set key explicitly
        Message msg = new Message(topic, payload);
        msg.setKey(key);
        return send(msg);
    }

    @Override
    public CompletableFuture<String> send(String topic, Object payload) {
        return send(new Message(topic, payload));
    }

    @Override
    public void close() { closed.set(true); }

    @Override
    public boolean isClosed() { return closed.get(); }
}
