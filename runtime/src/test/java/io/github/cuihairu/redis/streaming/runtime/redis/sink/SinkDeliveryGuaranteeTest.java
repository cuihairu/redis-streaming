package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import io.github.cuihairu.redis.streaming.api.stream.DeliveryGuarantee;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The exactly-once building-block sinks must declare {@code EFFECTIVELY_ONCE}; a plain lambda
 * sink is kept as the AT_LEAST_ONCE control.
 */
class SinkDeliveryGuaranteeTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);

    @Test
    void plainSinkDefaultsToAtLeastOnce() {
        io.github.cuihairu.redis.streaming.api.stream.StreamSink<String> sink = value -> { };
        assertEquals(DeliveryGuarantee.AT_LEAST_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void atomicCheckpointListSinkDeclaresEffectivelyOnce() {
        RedisAtomicCheckpointListSink<String> sink =
                new RedisAtomicCheckpointListSink<>(redissonClient, "dedup", "list");
        assertEquals(DeliveryGuarantee.EFFECTIVELY_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void idempotentListSinkDeclaresEffectivelyOnce() {
        RedisIdempotentListSink<String> sink =
                new RedisIdempotentListSink<>(redissonClient, "dedup", "list");
        assertEquals(DeliveryGuarantee.EFFECTIVELY_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void checkpointedIdempotentListSinkDeclaresEffectivelyOnce() {
        RedisCheckpointedIdempotentListSink<String> sink =
                new RedisCheckpointedIdempotentListSink<>(redissonClient, "dedup", "list");
        assertEquals(DeliveryGuarantee.EFFECTIVELY_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void outboxSinkDeclaresEffectivelyOnceViaTwoPhaseCommitContract() {
        RedisOutboxSink<String> sink = new RedisOutboxSink<>(redissonClient, "outbox");
        assertEquals(DeliveryGuarantee.EFFECTIVELY_ONCE, sink.deliveryGuarantee());
    }
}
