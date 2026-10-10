package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisStreamExecutionEnvironment.DeferredAcks} — the RT-L2 fix.
 * Pipelines are keyed by the structured {@link RedisRuntimeCheckpointManager.PipelineKey}, so a
 * topic containing {@code |} can no longer split back into the wrong topic/group at ack time,
 * and the offsets/ack frontier is the furthest stream id rather than the last appended one.
 */
class DeferredAcksTest {

    @Test
    void snapshotOffsets_keysByStructuredPipelineKeyEvenWithPipeInTopic() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        String topic = "t|fake-group-injection";
        acks.record(topic, "g", 0, "1-0");

        Map<String, Map<Integer, String>> snapshot = acks.snapshotOffsets();

        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey(new RedisRuntimeCheckpointManager.PipelineKey(topic, "g").key()),
                "snapshot key must be the PipelineKey string form for the checkpoint-side exact match");
        assertEquals(Map.of(0, "1-0"), snapshot.get(new RedisRuntimeCheckpointManager.PipelineKey(topic, "g").key()));
    }

    @Test
    void snapshotOffsets_frontierIsFurthestIdNotLastAppended() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "100-0");
        acks.record("t", "g", 0, "50-0");

        Map<String, Map<Integer, String>> snapshot = acks.snapshotOffsets();

        assertEquals("100-0", snapshot.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g").key()).get(0));
    }

    @Test
    void snapshotOffsets_comparesSequenceWhenMillisecondsTie() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 1, "7-2");
        acks.record("t", "g", 1, "7-9");

        Map<String, Map<Integer, String>> snapshot = acks.snapshotOffsets();

        assertEquals("7-9", snapshot.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g").key()).get(1));
    }

    @Test
    void snapshotOffsets_skipsBlankIds() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "  ");

        assertTrue(acks.snapshotOffsets().isEmpty());
    }

    @Test
    void drainForAck_groupsByIdAndPartitionAndEmptiesState() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g1", 0, "1-0");
        acks.record("t", "g1", 0, "2-0");
        acks.record("t", "g1", 1, "3-0");
        acks.record("t", "g2", 0, "4-0");

        Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, List<String>>> drained = acks.drainForAck();

        assertEquals(2, drained.size());
        assertEquals(List.of("1-0", "2-0"), drained.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g1")).get(0));
        assertEquals(List.of("3-0"), drained.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g1")).get(1));
        assertEquals(List.of("4-0"), drained.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g2")).get(0));

        // drained entries leave the tracking state; a second drain sees nothing
        assertTrue(acks.drainForAck().isEmpty());
        assertTrue(acks.snapshotOffsets().isEmpty());
    }

    @Test
    void drainForAck_keepsPipelineEntryForOtherPartitionsStillPending() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "1-0");

        Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, List<String>>> drained = acks.drainForAck();
        assertEquals(1, drained.size());

        // a new epoch records partition 1 only — the pipeline stays tracked for it
        acks.record("t", "g", 1, "2-0");
        Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, List<String>>> next = acks.drainForAck();
        assertEquals(Map.of(1, List.of("2-0")), next.get(new RedisRuntimeCheckpointManager.PipelineKey("t", "g")));
    }

    @Test
    void ackAll_acksOnTheStructuredPipelineKeyStreamNotASplit() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        doReturn(stream).when(redis).getStream(anyString(), any(StringCodec.class));
        when(redis.getScript(any(StringCodec.class))).thenReturn(mock(RScript.class));

        String topic = "t|fake";
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record(topic, "g", 0, "1-0");
        acks.record(topic, "g", 1, "2-0");

        acks.ackAll(redis, acks.drainForAck(), StreamKeys.shared());

        // the pipe-in-topic case used to split back to the wrong stream key here
        verify(redis).getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE);
        verify(redis).getStream(StreamKeys.partitionStream(topic, 1), StringCodec.INSTANCE);
        verify(stream, times(2)).ack(eq("g"), any());
    }

    @Test
    void ackAll_frontierUsesFurthestIdInLuaCas() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        doReturn(stream).when(redis).getStream(anyString(), any(StringCodec.class));
        RScript script = mock(RScript.class);
        when(redis.getScript(any(StringCodec.class))).thenReturn(script);

        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "100-0");
        acks.record("t", "g", 0, "50-0");

        acks.ackAll(redis, acks.drainForAck(), StreamKeys.shared());

        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(List.of(StreamKeys.commitFrontier("t", 0))), eq("g"), eq("100-0"));
    }

    @Test
    void ackAll_toleratesNullClientAndNullSnapshot() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        assertDoesNotThrow(() -> acks.ackAll(null, StreamKeys.shared()));
        assertDoesNotThrow(() -> acks.ackAll(null, Map.of(), StreamKeys.shared()));

        RedissonClient redis = mock(RedissonClient.class);
        assertDoesNotThrow(() -> acks.ackAll(redis, null, StreamKeys.shared()));
        verifyNoInteractions(redis);
    }

    @Test
    void ackAll_swallowsPerStreamAckFailures() {
        RedissonClient redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RStream<String, Object> stream = mock(RStream.class);
        doReturn(stream).when(redis).getStream(anyString(), any(StringCodec.class));
        when(redis.getScript(any(StringCodec.class))).thenReturn(mock(RScript.class));
        when(stream.ack(anyString(), any())).thenThrow(new RuntimeException("redis down"));

        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "1-0");

        assertDoesNotThrow(() -> acks.ackAll(redis, acks.drainForAck(), StreamKeys.shared()));
    }

    @Test
    void clear_emptiesEverything() {
        RedisStreamExecutionEnvironment.DeferredAcks acks = new RedisStreamExecutionEnvironment.DeferredAcks();
        acks.record("t", "g", 0, "1-0");
        acks.record("t", "g", 1, "2-0");

        acks.clear();

        assertTrue(acks.snapshotOffsets().isEmpty());
        assertTrue(acks.drainForAck().isEmpty());
    }
}
