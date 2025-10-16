package io.github.cuihairu.redis.streaming.reliability.dlq;

public interface DeadLetterConsumer {
    enum HandleResult { SUCCESS, RETRY, FAIL }

    @FunctionalInterface
    interface DeadLetterHandler {
        HandleResult handle(DeadLetterEntry entry) throws Exception;
    }

    void subscribe(String topic, DeadLetterHandler handler);

    void subscribe(String topic, String group, DeadLetterHandler handler);

    void start();

    void stop();

    void close();

    boolean isRunning();

    boolean isClosed();
}

