package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Error-path coverage for {@link AbstractCDCConnector} hooks that the happy-path suites do
 * not reach: a RuntimeException thrown by the scheduler shutdown and a throwing listener
 * inside the scheduled-polling loop (which must survive and keep delivering).
 */
@Timeout(30)
class AbstractCDCConnectorErrorPathCoverageTest {

    /** Minimal concrete connector: queue-backed doPoll, no-op start/stop side effects. */
    private static class StubConnector extends AbstractCDCConnector {
        final BlockingQueue<ChangeEvent> queue = new ArrayBlockingQueue<>(16);
        boolean startScheduledPolling;

        StubConnector(String name, long pollingIntervalMs) {
            super(CDCConfigurationBuilder.forMySQLBinlog(name)
                    .pollingIntervalMs(pollingIntervalMs)
                    .build());
        }

        @Override
        protected void doStart() {
            if (startScheduledPolling) {
                startScheduledPolling();
            }
        }

        @Override
        protected void doStop() {
            // nothing to clean up
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            List<ChangeEvent> batch = new java.util.ArrayList<>();
            queue.drainTo(batch, 1);
            return batch;
        }

        @Override
        protected void doCommit(String position) {
            // no-op
        }

        @Override
        protected void doResetToPosition(String position) {
            // no-op
        }
    }

    @Test
    void schedulerShutdownRuntimeExceptionIsSwallowedByStop() throws Exception {
        StubConnector connector = new StubConnector("stub-sched-rte", 0);
        connector.start().get(10, TimeUnit.SECONDS);

        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any()))
                .thenThrow(new IllegalStateException("pool is poisoned"));
        connector.scheduler = scheduler;

        // must NOT propagate: cleanup must not mask the stop it accompanies
        connector.stop().get(10, TimeUnit.SECONDS);
        assertTrue(connector.getHealthStatus().getMessage().contains("Connector stopped"));
    }

    @Test
    void startFailureStillShutsDownScheduler() throws Exception {
        StubConnector connector = new StubConnector("stub-start-fail", 0) {
            @Override
            protected void doStart() {
                throw new IllegalStateException("upstream misconfigured");
            }
        };
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any()))
                .thenThrow(new IllegalStateException("pool is poisoned"));
        connector.scheduler = scheduler;

        assertThrows(Exception.class, () -> connector.start().get(10, TimeUnit.SECONDS));
        assertFalse(connector.isRunning());
    }

    @Test
    void scheduledPollingSurvivesThrowingListenerAndKeepsDelivering() throws Exception {
        StubConnector connector = new StubConnector("stub-throwing-listener", 30);
        connector.startScheduledPolling = true;
        AtomicInteger deliveries = new AtomicInteger();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onEvents(String connectorName, List<ChangeEvent> events) {
                deliveries.incrementAndGet();
                throw new IllegalStateException("listener exploded (call " + deliveries + ")");
            }
        });

        connector.start().get(10, TimeUnit.SECONDS);
        try {
            connector.queue.put(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "1", null, java.util.Map.of("id", 1)));
            connector.queue.put(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "2", null, java.util.Map.of("id", 2)));

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && deliveries.get() < 2) {
                Thread.sleep(50);
            }
            assertTrue(deliveries.get() >= 2,
                    "a throwing listener must not kill the polling loop: deliveries=" + deliveries);
        } finally {
            connector.stop().get(10, TimeUnit.SECONDS);
        }
    }
}