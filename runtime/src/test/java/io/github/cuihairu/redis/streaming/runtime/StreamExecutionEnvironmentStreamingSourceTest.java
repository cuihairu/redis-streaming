package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code addSource} streams records through the bounded hand-off queue instead of collecting the
 * whole source into memory: the producer thread may not run ahead of the consumer, and sources far
 * larger than {@link StreamExecutionEnvironment#QUEUE_CAPACITY} flow through without being held in
 * memory.
 */
class StreamExecutionEnvironmentStreamingSourceTest {

    @Test
    void sourceDoesNotRunAheadOfItsConsumer() throws Exception {
        // The source blocks after its first record until the sink has consumed it. With the old
        // model (collect everything, then run the pipeline) addSource would hang here, which this
        // await(10s) turns into a visible failure instead of an endless one.
        CountDownLatch firstRecordConsumed = new CountDownLatch(1);
        AtomicBoolean reachedSecondRecord = new AtomicBoolean(false);

        StreamSource<Integer> source = ctx -> {
            ctx.collect(1);
            assertTrue(firstRecordConsumed.await(10, TimeUnit.SECONDS),
                    "the source must wait for the consumer instead of filling memory");
            ctx.collect(2);
            reachedSecondRecord.set(true);
            ctx.collect(3);
        };

        List<Integer> out = new ArrayList<>();
        StreamExecutionEnvironment.getExecutionEnvironment()
                .addSource(source)
                .addSink(v -> {
                    out.add(v);
                    if (v == 1) {
                        firstRecordConsumed.countDown();
                    }
                });

        assertEquals(List.of(1, 2, 3), out);
        assertTrue(reachedSecondRecord.get(),
                "the second record is emitted only after the first one was consumed");
    }

    @Test
    void streamsALargeSourceThroughTheBoundedQueueInOrder() {
        int size = 100_000;
        assertTrue(StreamExecutionEnvironment.QUEUE_CAPACITY < size,
                "the hand-off queue must not be able to hold the whole source");

        List<Integer> out = new ArrayList<>();
        StreamSource<Integer> source = ctx -> {
            for (int i = 0; i < size; i++) {
                ctx.collect(i);
            }
        };
        StreamExecutionEnvironment.getExecutionEnvironment()
                .addSource(source)
                .addSink(out::add);

        assertEquals(size, out.size());
        assertEquals(0, out.get(0));
        assertEquals(size / 2, out.get(size / 2));
        assertEquals(size - 1, out.get(size - 1));
    }

    @Test
    void sourceFailureAfterProducingSurfacesFromTheConsumer() throws Exception {
        // The source fails after the sink consumed its first record, so the failure can no longer
        // surface from addSource — it must surface from the terminal operation's iteration instead.
        CountDownLatch firstRecordConsumed = new CountDownLatch(1);

        StreamSource<Integer> source = ctx -> {
            ctx.collect(1);
            assertTrue(firstRecordConsumed.await(10, TimeUnit.SECONDS),
                    "the source must wait for the consumer instead of filling memory");
            throw new IllegalStateException("boom");
        };

        List<Integer> out = new ArrayList<>();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                StreamExecutionEnvironment.getExecutionEnvironment()
                        .addSource(source)
                        .addSink(v -> {
                            out.add(v);
                            firstRecordConsumed.countDown();
                        }));

        assertEquals("Source execution failed", failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException,
                "the consumer must rethrow the original source failure");
        assertEquals(List.of(1), out, "records already delivered before the failure are kept");
    }
}
