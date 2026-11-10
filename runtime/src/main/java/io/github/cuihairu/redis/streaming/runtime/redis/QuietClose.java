package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;

import java.util.List;

/**
 * Best-effort teardown helpers shared by the job start/stop/scale paths. Every failure
 * is swallowed: shutdown runs during exception handling and cancellation, where a close
 * error must never mask the original failure.
 */
final class QuietClose {

    private QuietClose() {
    }

    static void stopConsumersQuietly(List<MessageConsumer> consumers) {
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
    }

    static void closeRunnersQuietly(List<RedisPipelineRunner<?>> runners) {
        for (RedisPipelineRunner<?> r : runners) {
            try {
                r.close();
            } catch (Exception ignore) {
            }
        }
    }

    static void shutdownExecutorQuietly(java.util.concurrent.ExecutorService executor) {
        if (executor == null) {
            return;
        }
        try {
            executor.shutdownNow();
        } catch (Exception ignore) {
        }
    }
}
