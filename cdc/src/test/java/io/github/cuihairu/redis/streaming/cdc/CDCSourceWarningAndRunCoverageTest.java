package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the constructor warning/exception guards and the remaining {@code run()} branches of
 * {@link CDCSource}: idle sleeping, null batches, mid-batch stop, null events and null event
 * timestamps.
 */
@Timeout(10)
class CDCSourceWarningAndRunCoverageTest {

    private static ChangeEvent event(String key, Instant ts) {
        ChangeEvent e = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", key, null, java.util.Map.of("id", key));
        e.setTimestamp(ts);
        return e;
    }

    private static StreamSource.SourceContext<ChangeEvent> ctx() {
        @SuppressWarnings("unchecked")
        StreamSource.SourceContext<ChangeEvent> ctx = mock(StreamSource.SourceContext.class);
        return ctx;
    }

    @Test
    void warnsWhenConnectorUsesScheduledPushPolling() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        CDCConfiguration cfg = CDCConfigurationBuilder.forDatabasePolling("pushy")
                .pollingIntervalMs(50)
                .build();
        when(connector.getConfiguration()).thenReturn(cfg);
        when(connector.getName()).thenReturn("pushy");
        when(connector.isRunning()).thenReturn(true);
        when(connector.poll()).thenReturn(List.of(event("k1", Instant.ofEpochMilli(1000))), List.of());

        CDCSource source = new CDCSource(connector, 0L, 1);
        StreamSource.SourceContext<ChangeEvent> ctx = ctx();
        source.run(ctx);

        verify(ctx).collectWithTimestamp(any(ChangeEvent.class), anyLong());
    }

    @Test
    void constructionSurvivesFailingConfigurationLookup() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.getConfiguration()).thenThrow(new IllegalStateException("config gone"));
        when(connector.isRunning()).thenReturn(true);
        when(connector.poll()).thenReturn(List.of());

        CDCSource source = new CDCSource(connector, 0L, 1);
        source.run(ctx());

        verify(connector, never()).start();
    }

    @Test
    void sleepsBetweenIdlePollsUntilMaxIdleReached() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.getConfiguration()).thenReturn(null);
        when(connector.isRunning()).thenReturn(true);
        when(connector.poll()).thenReturn(null, List.of());

        CDCSource source = new CDCSource(connector, 1L, 2);
        source.run(ctx());

        verify(connector, org.mockito.Mockito.times(2)).poll();
    }

    @Test
    void exitsImmediatelyWhenContextAlreadyStopped() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        when(connector.getConfiguration()).thenReturn(null);
        when(connector.isRunning()).thenReturn(false);
        when(connector.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(connector.poll()).thenReturn(List.of(event("k", Instant.now())));

        StreamSource.SourceContext<ChangeEvent> ctx = ctx();
        when(ctx.isStopped()).thenReturn(true);

        new CDCSource(connector, 0L, 1).run(ctx);

        verify(connector).start();
        verify(connector, never()).poll();
    }

    @Test
    void stopsMidBatchAndSkipsNullEvents() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        ChangeEvent withTs = event("k1", Instant.ofEpochMilli(1234L));
        ChangeEvent neverCollected = event("k2", Instant.ofEpochMilli(5678L));
        when(connector.getConfiguration()).thenReturn(null);
        when(connector.isRunning()).thenReturn(true);
        when(connector.poll()).thenReturn(Arrays.asList(null, withTs, neverCollected), List.of());

        StreamSource.SourceContext<ChangeEvent> ctx = ctx();
        // while-check, null-event check, second-event check -> false; third-event check -> true (stop)
        when(ctx.isStopped()).thenReturn(false, false, false, true);

        new CDCSource(connector, 0L, 1).run(ctx);

        org.mockito.ArgumentCaptor<Long> captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(ctx).collectWithTimestamp(any(ChangeEvent.class), captor.capture());
        assertEquals(1234L, captor.getValue());
    }

    @Test
    void usesWallClockWhenEventTimestampMissing() throws Exception {
        CDCConnector connector = mock(CDCConnector.class);
        ChangeEvent withoutTs = event("k2", null);
        when(connector.getConfiguration()).thenReturn(null);
        when(connector.isRunning()).thenReturn(true);
        when(connector.poll()).thenReturn(List.of(withoutTs), List.of());

        StreamSource.SourceContext<ChangeEvent> ctx = ctx();
        when(ctx.isStopped()).thenReturn(false);

        long before = System.currentTimeMillis();
        new CDCSource(connector, 0L, 1).run(ctx);
        long after = System.currentTimeMillis();

        org.mockito.ArgumentCaptor<Long> captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(ctx).collectWithTimestamp(any(ChangeEvent.class), captor.capture());
        long ts = captor.getValue();
        assertTrue(ts >= before && ts <= after, "timestamp should fall back to wall clock, got " + ts);
    }
}
