package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AbstractCDCConnector error-notification lambdas (start/stop/reset), the stop body
 * with an active scheduler, and updateMetricsForEvents per event type.
 */
class AbstractCDCConnectorLifecycleCoverageTest {

    private static final class FakeConnector extends AbstractCDCConnector {
        private final boolean failStart;
        private final boolean failStop;
        private final boolean failReset;
        private List<ChangeEvent> nextPoll = List.of();

        FakeConnector(CDCConfiguration cfg, boolean failStart, boolean failStop, boolean failReset) {
            super(cfg);
            this.failStart = failStart;
            this.failStop = failStop;
            this.failReset = failReset;
        }

        @Override
        protected void doStart() throws Exception {
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
            startScheduledPolling();
        }

        @Override
        protected void doStop() throws Exception {
            if (failStop) {
                throw new IllegalStateException("stop failed");
            }
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            return nextPoll;
        }

        @Override
        protected void doCommit(String position) {
        }

        @Override
        protected void doResetToPosition(String position) throws Exception {
            if (failReset) {
                throw new IllegalStateException("reset failed");
            }
        }
    }

    private static CDCConfiguration cfg(long pollingIntervalMs) {
        return CDCConfigurationBuilder.forDatabasePolling("fake")
                .pollingIntervalMs(pollingIntervalMs)
                .build();
    }

    private static ChangeEvent event(ChangeEvent.EventType type) {
        return new ChangeEvent(type, "db", "t", Map.of());
    }

    @Test
    void startFailureNotifiesListenerError() {
        FakeConnector connector = new FakeConnector(cfg(0), true, false, false);
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(connectorName + ":" + error.getMessage());
            }
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> connector.start().join());
        assertTrue(ex.getCause().getCause().getMessage().contains("start failed"));
        assertEquals(1, errors.size(), "listener observed the failure: " + errors);
    }

    @Test
    void stopWithSchedulerRunsFullShutdownPath() throws Exception {
        FakeConnector connector = new FakeConnector(cfg(50), false, false, false);
        List<String> events = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorStopped(String connectorName) {
                events.add("stopped:" + connectorName);
            }
        });

        connector.start().get();
        Thread.sleep(120);
        connector.stop().get();
        assertEquals(List.of("stopped:fake"), events);
    }

    @Test
    void stopFailureNotifiesListenerError() {
        FakeConnector connector = new FakeConnector(cfg(0), false, true, false);
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error.getMessage()));
            }
        });

        assertThrows(RuntimeException.class, () -> {
            connector.start().join();
            connector.stop().join();
        });
        assertEquals(List.of("stop failed"), errors);
    }

    @Test
    void resetFailureNotifiesListenerError() {
        FakeConnector connector = new FakeConnector(cfg(0), false, false, true);
        List<String> errors = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                errors.add(String.valueOf(error.getMessage()));
            }
        });
        connector.start().join();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> connector.resetToPosition("pos-1"));
        assertTrue(ex.getMessage().contains("pos-1"));
        assertEquals(List.of("reset failed"), errors);
    }

    @Test
    void pollCoversAllEventTypesInMetrics() {
        FakeConnector connector = new FakeConnector(cfg(0), false, false, false);
        connector.start().join();
        connector.nextPoll = List.of(
                event(ChangeEvent.EventType.INSERT),
                event(ChangeEvent.EventType.UPDATE),
                event(ChangeEvent.EventType.DELETE),
                event(ChangeEvent.EventType.SCHEMA_CHANGE));

        assertEquals(4, connector.poll().size());
        assertNotNull(connector.getMetrics());
    }

    @Test
    void listenerDefaultMethodsAreCallable() {
        CDCEventListener listener = new CDCEventListener() {
        };
        List<ChangeEvent> events = List.of(event(ChangeEvent.EventType.INSERT));
        listener.onEvents("c", events);
        listener.onSnapshotStarted("c", 1);
        listener.onSnapshotCompleted("c", 1);
        assertTrue(true);
    }
}
