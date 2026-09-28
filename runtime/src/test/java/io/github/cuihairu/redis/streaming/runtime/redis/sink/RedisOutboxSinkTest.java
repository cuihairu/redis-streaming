package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.Codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the write side of the v2.5 outbox ({@link RedisOutboxSink}). */
class RedisOutboxSinkTest {

    private static final String OUTBOX_KEY = "test:job:outbox";

    private RedissonClient redisson;
    private RStream<String, String> stream;
    private RMap<String, String> epochs;
    private RedisOutboxSink<String> sink;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        stream = mock(RStream.class);
        epochs = mock(RMap.class);
        when(redisson.<String, String>getMap(eq(OUTBOX_KEY + ":epochs"), any(Codec.class))).thenReturn(epochs);
        when(redisson.<String, String>getStream(eq(OUTBOX_KEY), any(Codec.class))).thenReturn(stream);
        sink = new RedisOutboxSink<>(redisson, OUTBOX_KEY, new ObjectMapper());
    }

    @Test
    void invokeBuffersInMemoryUntilPreCommitFlushesTheOutboxStream() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.invoke(new IdempotentRecord<>("r2", "b"), txn);

        // nothing durable until the checkpoint's preCommit phase
        verifyNoInteractions(stream);

        sink.preCommit(txn);
        verify(stream, times(2)).add(any(StreamAddArgs.class));

        sink.commit(txn);
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_COMMITTED);
    }

    @Test
    void preCommitWithEmptyBufferTouchesNoStreamButCommitStillMarksEpoch() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.preCommit(txn);
        verify(stream, times(0)).add(any(StreamAddArgs.class));
        sink.commit(txn);
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_COMMITTED);
    }

    @Test
    void epochsAreBufferedAndFlushedIndependently() throws Exception {
        RedisOutboxSink.OutboxTxn txnA = sink.beginTxn();
        RedisOutboxSink.OutboxTxn txnB = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("a1", "va"), txnA);
        sink.invoke(new IdempotentRecord<>("b1", "vb"), txnB);
        sink.invoke(new IdempotentRecord<>("a2", "vc"), txnA);

        sink.preCommit(txnA);
        verify(stream, times(2)).add(any(StreamAddArgs.class));
        verifyNoInteractions(epochs);

        // committing B must not touch A's epoch marker or flush A's data
        sink.commit(txnB);
        verify(epochs, times(1)).put(anyString(), anyString());
        verify(epochs).put(txnB.epoch(), RedisOutboxSink.STATUS_COMMITTED);

        sink.preCommit(txnA); // A's snapshot is idempotent-replayed by the caller; buffer still holds 2
        verify(stream, times(4)).add(any(StreamAddArgs.class));
    }

    @Test
    void commitClearsTheEpochBufferSoLatePreCommitFlushesNothing() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.commit(txn);

        sink.preCommit(txn);
        verify(stream, times(0)).add(any(StreamAddArgs.class));
    }

    @Test
    void recoverAndCommitMarksCommittedIdempotentlyWithoutLocalState() {
        RedisOutboxSink.OutboxTxn restored = new RedisOutboxSink.OutboxTxn("epoch-from-checkpoint");
        sink.recoverAndCommit(restored);
        sink.recoverAndCommit(restored);
        verify(epochs, times(2)).put("epoch-from-checkpoint", RedisOutboxSink.STATUS_COMMITTED);
    }

    @Test
    void recoverAndAbortMarksAbortedAndDropsTheBuffer() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);

        sink.recoverAndAbort(txn);
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_ABORTED);

        // buffer dropped with the marker: no half-prepared data can flush afterwards
        sink.preCommit(txn);
        verify(stream, times(0)).add(any(StreamAddArgs.class));
    }

    @Test
    void inheritedAbortDefaultDelegatesToRecoverAndAbort() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.abort(txn);
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_ABORTED);
    }

    @Test
    void txnHandleSurvivesJavaSerialization() throws Exception {
        // the runtime encodes the handle with Java serialization + Base64 before storing
        // it into the checkpoint snapshot; the record must round-trip through that
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(new RedisOutboxSink.OutboxTxn("epoch-7"));
        }
        RedisOutboxSink.OutboxTxn decoded;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            decoded = (RedisOutboxSink.OutboxTxn) ois.readObject();
        }
        assertEquals("epoch-7", decoded.epoch());
        assertEquals(new RedisOutboxSink.OutboxTxn("epoch-7"), decoded);
    }

    @Test
    void plainInvokeIsBridgedToFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sink.invoke(new IdempotentRecord<>("r1", "a")));
        assertTrue(ex.getMessage().contains("invoke(value, txn)"));
    }

    @Test
    void preCommitFailurePropagatesForTheRuntimeToAbortTheEpoch() throws Exception {
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), sink.beginTxn()); // foreign epoch noise
        sink.invoke(new IdempotentRecord<>("r2", "b"), txn);

        org.mockito.Mockito
                .doThrow(new IllegalStateException("redis write failed"))
                .when(stream).add(any(StreamAddArgs.class));

        assertThrows(IllegalStateException.class, () -> sink.preCommit(txn));
    }

    @Test
    void derivedKeysFollowTheOutboxKey() {
        assertEquals(OUTBOX_KEY, sink.getOutboxKey());
        assertEquals(OUTBOX_KEY + ":epochs", sink.getEpochsKey());
    }
}
