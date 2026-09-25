package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the defensive branches of {@link AbstractCDCConnector#poll()} and of the scheduled
 * polling task (null/empty batches, listener notification and the failure guard).
 */
@Timeout(30)
class AbstractCDCConnectorSchedulerCoverageTest {

    private static CDCConfiguration cfg() {
        return CDCConfigurationBuilder.forDatabasePolling("conn").pollingIntervalMs(25).build();
    }

    static class TestConnector extends AbstractCDCConnector {
        List<ChangeEvent> next = List.of();
        RuntimeException pollFailure;
        int pollCalls;

        TestConnector() {
            super(cfg());
        }

        @Override
        protected void doStart() {
            startScheduledPolling();
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            pollCalls++;
            if (pollFailure != null) {
                throw pollFailure;
            }
            return next;
        }

        @Override
        protected void doCommit(String position) {
        }

        @Override
        protected void doResetToPosition(String position) {
        }
    }

    /** Seam for the scheduler lambda's defensive handling of a hostile {@code poll()} override. */
    static class OverridingConnector extends TestConnector {
        List<ChangeEvent> pollResult;
        RuntimeException pollThrows;

        @Override
        public List<ChangeEvent> poll() {
            if (pollThrows != null) {
                throw pollThrows;
            }
            return pollResult;
        }
    }

    private static Runnable captureTask(Runnable setup) {
        ScheduledExecutorService fake = mock(ScheduledExecutorService.class);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        when(fake.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return null;
                });
        try (MockedStatic<java.util.concurrent.Executors> executors =
                     mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newScheduledThreadPool(1)).thenReturn(fake);
            setup.run();
        }
        Runnable task = captured.get();
        return task;
    }

    @Test
    void scheduledTaskLeavesBatchesForPullConsumersWithoutListener() {
        TestConnector connector = new TestConnector();
        Runnable task = captureTask(connector::doStart);

        connector.running.set(true);
        connector.next = List.of(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "k", null, Map.of()));
        task.run();

        assertEquals(0, connector.pollCalls, "no listener: events must stay queued for pull consumers");
    }

    @Test
    void scheduledTaskNotifiesListenerWithPolledEvents() {
        TestConnector connector = new TestConnector();
        Runnable task = captureTask(connector::doStart);
        CDCEventListener listener = mock(CDCEventListener.class);
        connector.setEventListener(listener);
        connector.running.set(true);
        List<ChangeEvent> events = List.of(
                new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "k", null, Map.of()));
        connector.next = events;

        task.run();

        verify(listener).onEvents("conn", events);
    }

    @Test
    void scheduledTaskSkipsNotificationForEmptyAndNullBatches() {
        OverridingConnector connector = new OverridingConnector();
        Runnable task = captureTask(connector::doStart);
        CDCEventListener listener = mock(CDCEventListener.class);
        connector.setEventListener(listener);
        connector.running.set(true);

        connector.pollResult = List.of();
        task.run();
        connector.pollResult = null;
        task.run();

        verify(listener, never()).onEvents(any(), any());
    }

    @Test
    void scheduledTaskSwallowsPollFailures() {
        OverridingConnector connector = new OverridingConnector();
        Runnable task = captureTask(connector::doStart);
        connector.setEventListener(mock(CDCEventListener.class));
        connector.running.set(true);
        connector.pollThrows = new IllegalStateException("poll blew up");

        task.run();

        // a second run must still work: the scheduler keeps firing after a swallowed failure
        connector.pollThrows = null;
        connector.pollResult = List.of();
        task.run();
    }

    @Test
    void pollReturnsEmptyListWhenDoPollReturnsNull() {
        TestConnector connector = new TestConnector();
        connector.running.set(true);
        connector.next = null;

        List<ChangeEvent> out = connector.poll();

        assertEquals(List.of(), out);
    }

    @Test
    void pollCountsEveryEventTypeInMetrics() {
        TestConnector connector = new TestConnector();
        connector.running.set(true);
        connector.next = List.of(
                new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "k1", null, Map.of()),
                new ChangeEvent(ChangeEvent.EventType.UPDATE, "db", "t", "k2", null, Map.of()),
                new ChangeEvent(ChangeEvent.EventType.DELETE, "db", "t", "k3", null, Map.of()),
                new ChangeEvent(ChangeEvent.EventType.SCHEMA_CHANGE, "db", "t", "k4", null, Map.of()));
        CDCEventListener listener = mock(CDCEventListener.class);
        connector.setEventListener(listener);

        List<ChangeEvent> out = connector.poll();

        assertSame(connector.next, out);
        assertEquals(4, connector.getMetrics().getTotalEventsCaptured());
        verify(listener).onEventsCapture("conn", 4);
    }
}
