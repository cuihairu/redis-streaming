package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CDCSourceTest {

    private static StreamSource.SourceContext<ChangeEvent> collectingContext(List<ChangeEvent> sink) {
        return new StreamSource.SourceContext<>() {
            @Override
            public void collect(ChangeEvent element) {
                sink.add(element);
            }

            @Override
            public void collectWithTimestamp(ChangeEvent element, long timestamp) {
                sink.add(element);
            }

            @Override
            public Object getCheckpointLock() {
                return new Object();
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        };
    }

    @Test
    void drainsEventsUntilConnectorGoesIdle() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.isRunning()).thenReturn(true);
        ChangeEvent e1 = new ChangeEvent();
        e1.setEventType(ChangeEvent.EventType.INSERT);
        e1.setTable("orders");
        e1.setTimestamp(Instant.ofEpochMilli(1000));
        when(connector.poll())
                .thenReturn(List.of(e1))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of());

        CDCSource source = new CDCSource(connector, 0L, 3);
        List<ChangeEvent> collected = new CopyOnWriteArrayList<>();
        source.run(collectingContext(collected));

        assertEquals(1, collected.size());
        assertEquals("orders", collected.get(0).getTable());
        verify(connector, times(4)).poll();
    }

    @Test
    void startsStoppedConnectorAndCancelsIt() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.isRunning()).thenReturn(false, true, true);
        when(connector.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(connector.stop()).thenReturn(CompletableFuture.completedFuture(null));
        when(connector.poll()).thenReturn(List.of());

        CDCSource source = new CDCSource(connector, 0L, 1);
        source.run(collectingContext(new CopyOnWriteArrayList<>()));
        verify(connector).start();

        source.cancel();
        verify(connector).stop();
    }

    @Test
    void rejectsInvalidParameters() {
        CDCConnector connector = mock(CDCConnector.class);
        assertThrows(IllegalArgumentException.class, () -> new CDCSource(connector, -1L, 3));
        assertThrows(IllegalArgumentException.class, () -> new CDCSource(connector, 10L, 0));
        assertThrows(NullPointerException.class, () -> new CDCSource(null, 10L, 1));
    }

    // CDC-L9: the connector field is transient — a Java-serialized source arrives without
    // one and must fail fast in run()/cancel() until rewireConnector re-attaches it.

    @Test
    void deserializedSourceFailsFastUntilRewired() throws Exception {
        // the live connector is an unserializable mock: writing the source only succeeds
        // because the field no longer rides along in the stream
        CDCConnector original = mock(CDCConnector.class);
        when(original.getConfiguration()).thenReturn(null);
        CDCSource source = new CDCSource(original, 0L, 3);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(source);
        }
        CDCSource restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (CDCSource) in.readObject();
        }

        IllegalStateException onRun = assertThrows(IllegalStateException.class,
                () -> restored.run(collectingContext(new CopyOnWriteArrayList<>())));
        assertTrue(onRun.getMessage().contains("rewireConnector"));
        IllegalStateException onCancel = assertThrows(IllegalStateException.class, restored::cancel);
        assertTrue(onCancel.getMessage().contains("rewireConnector"));

        // rewiring a live connector restores operability
        CDCConnector live = mock(CDCConnector.class);
        when(live.getConfiguration()).thenReturn(null);
        when(live.isRunning()).thenReturn(true);
        when(live.poll()).thenReturn(List.of());
        when(live.stop()).thenReturn(CompletableFuture.completedFuture(null));
        restored.rewireConnector(live);

        List<ChangeEvent> sink = new CopyOnWriteArrayList<>();
        restored.run(collectingContext(sink));
        assertTrue(sink.isEmpty());
        restored.cancel();
        verify(live).stop();
    }

    @Test
    void rewireConnectorRejectsNull() {
        CDCSource source = new CDCSource(mock(CDCConnector.class), 0L, 1);
        assertThrows(NullPointerException.class, () -> source.rewireConnector(null));
    }
}
