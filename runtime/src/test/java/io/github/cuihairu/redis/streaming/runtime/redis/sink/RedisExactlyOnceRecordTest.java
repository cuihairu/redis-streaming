package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Direct construction coverage for {@link RedisExactlyOnceRecord}: the compact constructor
 * validation branches (null args, blank args, negative partition) plus accessor wiring.
 */
class RedisExactlyOnceRecordTest {

    @Test
    void constructsAndExposesAllComponents() {
        RedisExactlyOnceRecord<String> record =
                new RedisExactlyOnceRecord<>("topic", "group", 3, "1-2", "idem-1", "payload");
        assertEquals("topic", record.topic());
        assertEquals("group", record.consumerGroup());
        assertEquals(3, record.partitionId());
        assertEquals("1-2", record.messageId());
        assertEquals("idem-1", record.idempotencyKey());
        assertEquals("payload", record.value());
    }

    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class,
                () -> new RedisExactlyOnceRecord<>(null, "g", 0, "m", "k", "v"));
        assertThrows(NullPointerException.class,
                () -> new RedisExactlyOnceRecord<>("t", null, 0, "m", "k", "v"));
        assertThrows(NullPointerException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", 0, null, "k", "v"));
        assertThrows(NullPointerException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", 0, "m", null, "v"));
        assertThrows(NullPointerException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", 0, "m", "k", null));
    }

    @Test
    void rejectsBlankComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("  ", "g", 0, "m", "k", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("t", "", 0, "m", "k", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", 0, " ", "k", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", 0, "m", "\t", "v"));
    }

    @Test
    void rejectsNegativePartitionId() {
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", -1, "m", "k", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisExactlyOnceRecord<>("t", "g", Integer.MIN_VALUE, "m", "k", "v"));
    }

    @Test
    void acceptsZeroPartitionAndNonStringValues() {
        RedisExactlyOnceRecord<Object> record =
                new RedisExactlyOnceRecord<>("t", "g", 0, "m", "k", Integer.valueOf(42));
        assertEquals(0, record.partitionId());
        assertEquals(42, record.value());
    }
}
