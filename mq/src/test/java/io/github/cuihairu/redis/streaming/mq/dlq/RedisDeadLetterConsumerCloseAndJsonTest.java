package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Covers RedisDeadLetterConsumer.close() lifecycle and the private toJson helper.
 */
class RedisDeadLetterConsumerCloseAndJsonTest {

    private static String toJson(Object o) throws Exception {
        Method m = RedisDeadLetterConsumer.class.getDeclaredMethod("toJson", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(null, o);
    }

    static class BadToString {
        @Override
        public String toString() {
            return "bad-to-string";
        }
    }

    @Test
    void toJsonSerializesOrFallsBack() throws Exception {
        assertEquals("{\"a\":1}", toJson(Map.of("a", 1)));
        assertEquals("\"plain\"", toJson("plain"));
        assertEquals("bad-to-string", toJson(new BadToString() {
            @Override
            public String toString() {
                // Jackson can serialize this empty bean; exercise the happy path too
                return "bad-to-string";
            }
        }));
    }

    @Test
    void toJsonFallsBackToStringOnUnserializableObject() throws Exception {
        Object unserializable = new Object() {
            public String getBoom() {
                throw new IllegalStateException("nope");
            }

            @Override
            public String toString() {
                return "fallback-value";
            }
        };
        assertEquals("fallback-value", toJson(unserializable));
    }

    @Test
    void closeStopsExecutorAndIsIdempotent() {
        RedisDeadLetterConsumer consumer =
                new RedisDeadLetterConsumer(mock(RedissonClient.class), "c", "g");
        assertFalse(consumer.isClosed());
        consumer.close();
        assertTrue(consumer.isClosed());
        assertFalse(consumer.isRunning());
        assertDoesNotThrow(consumer::close);
    }

    @Test
    void subscribeAfterCloseThrows() {
        RedisDeadLetterConsumer consumer =
                new RedisDeadLetterConsumer(mock(RedissonClient.class), "c", "g");
        consumer.close();
        assertThrows(IllegalStateException.class,
                () -> consumer.subscribe("t", entry -> DeadLetterConsumer.HandleResult.SUCCESS));
    }

    @Test
    void stopWithoutStartIsSafeAndDefaultGroupApplied() {
        RedisDeadLetterConsumer consumer =
                new RedisDeadLetterConsumer(mock(RedissonClient.class), "c", "  ");
        assertDoesNotThrow(consumer::stop);
        // default group fallback for blank group name
        assertDoesNotThrow(() -> consumer.subscribe("t", entry -> DeadLetterConsumer.HandleResult.SUCCESS));
        consumer.close();
    }
}
