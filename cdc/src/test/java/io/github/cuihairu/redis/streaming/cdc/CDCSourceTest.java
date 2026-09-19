package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

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
}
