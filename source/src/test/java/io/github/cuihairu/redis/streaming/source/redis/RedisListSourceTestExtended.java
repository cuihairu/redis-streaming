package io.github.cuihairu.redis.streaming.source.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisListSource - extended coverage
 */
class RedisListSourceTestExtended {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RList<Object> mockList;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        when(mockRedissonClient.getList(anyString())).thenReturn(mockList);
    }

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithValidParameters() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        assertEquals("test-list", source.getListName());
        assertFalse(source.isRunning());

        source.close();
    }

    @Test
    void testConstructorWithCustomObjectMapper() {
        ObjectMapper customMapper = new ObjectMapper();
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", customMapper, String.class);

        assertEquals("test-list", source.getListName());

        source.close();
    }

    @Test
    void testConstructorWithNullRedissonClient() {
        assertThrows(NullPointerException.class, () ->
                new RedisListSource<>(null, "test-list", String.class));
    }

    @Test
    void testConstructorWithNullListName() {
        assertThrows(NullPointerException.class, () ->
                new RedisListSource<>(mockRedissonClient, null, String.class));
    }

    @Test
    void testConstructorWithNullObjectMapper() {
        assertThrows(NullPointerException.class, () ->
                new RedisListSource<>(mockRedissonClient, "test-list", (ObjectMapper) null, String.class));
    }

    @Test
    void testConstructorWithNullValueClass() {
        assertThrows(NullPointerException.class, () ->
                new RedisListSource<>(mockRedissonClient, "test-list", (Class<String>) null));
    }

    // ===== readOne Tests =====

    @Test
    void testReadOneWithValue() {
        when(mockList.remove(0)).thenReturn("test-value");

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            String result = source.readOne();
            assertEquals("test-value", result);
        } finally {
            source.close();
        }
    }

    @Test
    void testReadOneWithNullValue() {
        when(mockList.remove(0)).thenReturn(null);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            String result = source.readOne();
            assertNull(result);
        } finally {
            source.close();
        }
    }

    @Test
    void testReadOneThrowsException() {
        when(mockList.remove(0)).thenThrow(new RuntimeException("Redis error"));

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            String result = source.readOne();
            assertNull(result);
        } finally {
            source.close();
        }
    }

    // ===== readBatch Tests =====

    @Test
    void testReadBatchWithZeroCount() {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            List<String> result = source.readBatch(0);
            assertTrue(result.isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void testReadBatchWithPartialData() {
        when(mockList.isEmpty()).thenReturn(false, false, true);
        when(mockList.remove(0)).thenReturn("value1", "value2");

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            List<String> result = source.readBatch(5);
            assertEquals(2, result.size());
        } finally {
            source.close();
        }
    }

    @Test
    void testReadBatchWithDeserializationFailure() {
        when(mockList.isEmpty()).thenReturn(false, true);
        when(mockList.remove(0)).thenReturn("invalid-json");

        RedisListSource<TestRecord> source = new RedisListSource<>(
                mockRedissonClient, "test-list", objectMapper, TestRecord.class);

        try {
            List<TestRecord> result = source.readBatch(5);
            assertTrue(result.isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void testReadBatchWithException() {
        when(mockList.remove(anyInt())).thenThrow(new RuntimeException("Redis error"));

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            List<String> result = source.readBatch(5);
            assertTrue(result.isEmpty());
        } finally {
            source.close();
        }
    }

    // ===== readAll Tests =====

    @Test
    void testReadAllWithEmptyList() {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            List<String> result = source.readAll();
            assertTrue(result.isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void testReadAllWithException() {
        when(mockList.isEmpty()).thenThrow(new RuntimeException("Redis error"));

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            List<String> result = source.readAll();
            assertTrue(result.isEmpty());
        } finally {
            source.close();
        }
    }

    // ===== consume Tests =====

    @Test
    void testConsumeWithNullHandler() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(NullPointerException.class, () -> source.consume(null));
        } finally {
            source.close();
        }
    }

    @Test
    void testConsumeHandlesInterruptedException() throws Exception {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        AtomicReference<Exception> exception = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread consumerThread = new Thread(() -> {
            try {
                source.consume(value -> {
                    // Interrupt the thread
                    Thread.currentThread().interrupt();
                });
            } catch (Exception e) {
                exception.set(e);
            } finally {
                latch.countDown();
            }
        });

        consumerThread.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // The thread should have stopped gracefully
        source.stop();
        source.close();
    }

    // ===== poll Tests =====

    @Test
    void testPollWithNullHandler() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(NullPointerException.class, () ->
                    source.poll(null, Duration.ofMillis(100)));
        } finally {
            source.close();
        }
    }

    @Test
    void testPollWithNullInterval() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(NullPointerException.class, () ->
                    source.poll(value -> {}, null));
        } finally {
            source.close();
        }
    }

    // ===== pollBatch Tests =====

    @Test
    void testPollBatchWithNullHandler() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(NullPointerException.class, () ->
                    source.pollBatch(null, 5, Duration.ofMillis(100)));
        } finally {
            source.close();
        }
    }

    @Test
    void testPollBatchWithNullInterval() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(NullPointerException.class, () ->
                    source.pollBatch(batch -> {}, 5, null));
        } finally {
            source.close();
        }
    }

    @Test
    void testPollBatchWithNegativeBatchSize() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertThrows(IllegalArgumentException.class, () ->
                    source.pollBatch(batch -> {}, -1, Duration.ofMillis(100)));
        } finally {
            source.close();
        }
    }

    // ===== stop Tests =====

    @Test
    void testStopWhenNotRunning() {
        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        source.stop(); // Should not throw

        assertFalse(source.isRunning());
        source.close();
    }

    @Test
    void testStopStopsRunningConsumer() {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        source.consume(value -> {});

        assertTrue(source.isRunning());

        source.stop();

        assertFalse(source.isRunning());

        source.close();
    }

    // ===== getSize Tests =====

    @Test
    void testGetSize() {
        when(mockList.size()).thenReturn(42);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertEquals(42, source.getSize());
        } finally {
            source.close();
        }
    }

    // ===== isEmpty Tests =====

    @Test
    void testIsEmptyReturnsTrue() {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertTrue(source.isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void testIsEmptyReturnsFalse() {
        when(mockList.isEmpty()).thenReturn(false);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        try {
            assertFalse(source.isEmpty());
        } finally {
            source.close();
        }
    }

    // ===== poll execution Tests =====

    @Test
    void testPollDeliversElementAndCanStop() throws Exception {
        when(mockList.remove(0)).thenReturn("v").thenThrow(new IndexOutOfBoundsException());

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            source.poll(v -> {
                seen.set(v);
                source.stop();
                latch.countDown();
            }, Duration.ofMillis(10));

            assertTrue(source.isRunning());
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals("v", seen.get());
        } finally {
            source.close();
        }
    }

    @Test
    void testPollBatchDeliversNonEmptyBatchAndCanStop() throws Exception {
        when(mockList.isEmpty()).thenReturn(false, false, true);
        when(mockList.remove(0)).thenReturn("a", "b");

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<String>> batch = new AtomicReference<>();
        try {
            source.pollBatch(b -> {
                batch.set(b);
                source.stop();
                latch.countDown();
            }, 10, Duration.ofMillis(10));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b"), batch.get());
        } finally {
            source.close();
        }
    }

    // ===== close Tests =====

    @Test
    void testCloseStopsConsumerAndShutsDownScheduler() {
        when(mockList.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(
                mockRedissonClient, "test-list", String.class);

        source.consume(value -> {});
        source.close();

        assertFalse(source.isRunning());
    }

    // ===== Helper Classes =====

    record TestRecord(int value) {}
}
