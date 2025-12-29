package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AbstractCDCConnector
 */
class AbstractCDCConnectorTest {

    private CDCConfiguration configuration;
    private TestCDCConnector connector;
    private CDCEventListener mockListener;

    @BeforeEach
    void setUp() {
        configuration = CDCConfigurationBuilder.forDatabasePolling("test-connector")
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("")
                .pollingIntervalMs(100)
                .build();
        connector = new TestCDCConnector(configuration);
        mockListener = mock(CDCEventListener.class);
        connector.setEventListener(mockListener);
    }

    @Test
    void testStart() throws Exception {
        // When
        CompletableFuture<Void> startFuture = connector.start();
        startFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(connector.isRunning()).isTrue();
        assertThat(connector.getHealthStatus().getStatus()).isEqualTo(CDCHealthStatus.Status.HEALTHY);
        verify(mockListener, timeout(1000)).onConnectorStarted("test-connector");
    }

    @Test
    void testStop() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        CompletableFuture<Void> stopFuture = connector.stop();
        stopFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(connector.isRunning()).isFalse();
        verify(mockListener).onConnectorStopped("test-connector");
    }

    @Test
    void testPollWhenRunning() throws Exception {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test-db",
                "test-table",
                Map.of("id", 1, "name", "test")
        );
        connector.addEventToReturn(event);
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        List<ChangeEvent> events = connector.poll();

        // Then
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isEqualTo(event);
        assertThat(connector.getMetrics().getTotalEventsCaptured()).isEqualTo(1);
        verify(mockListener).onEventsCapture("test-connector", 1);
    }

    @Test
    void testPollWhenNotRunning() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test-db",
                "test-table",
                Map.of("id", 1)
        );
        connector.addEventToReturn(event);

        // When
        List<ChangeEvent> events = connector.poll();

        // Then
        assertThat(events).isEmpty();
        assertThat(connector.getMetrics().getTotalEventsCaptured()).isEqualTo(0);
        verify(mockListener, never()).onEventsCapture(any(), anyInt());
    }

    @Test
    void testCommit() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        connector.commit("test-position");

        // Then
        assertThat(connector.getCurrentPosition()).isEqualTo("test-position");
        assertThat(connector.getMetrics().getCurrentPosition()).isEqualTo("test-position");
        assertThat(connector.getMetrics().getLastCommitTime()).isNotNull();
        verify(mockListener).onPositionCommitted("test-connector", "test-position");
    }

    @Test
    void testCommitThrowsExceptionWhenPositionNull() {
        // Given
        connector.start().join();

        // When/Then
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> {
            connector.commit(null);
        });
    }

    @Test
    void testResetToPosition() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        connector.resetToPosition("reset-position");

        // Then
        assertThat(connector.getCurrentPosition()).isEqualTo("reset-position");
        assertThat(connector.getLastResetPosition()).isEqualTo("reset-position");
    }

    @Test
    void testGetConfiguration() {
        // When
        CDCConfiguration config = connector.getConfiguration();

        // Then
        assertThat(config).isNotNull();
        assertThat(config.getName()).isEqualTo("test-connector");
    }

    @Test
    void testGetName() {
        // When
        String name = connector.getName();

        // Then
        assertThat(name).isEqualTo("test-connector");
    }

    @Test
    void testSetEventListener() {
        // Given
        CDCEventListener newListener = mock(CDCEventListener.class);

        // When
        connector.setEventListener(newListener);

        // Then
        assertThat(connector.getEventListener()).isEqualTo(newListener);
    }

    @Test
    void testHealthStatusChanges() throws Exception {
        // Given
        CDCHealthStatus oldStatus = connector.getHealthStatus();

        // When
        connector.start().get(5, TimeUnit.SECONDS);
        CDCHealthStatus newStatus = connector.getHealthStatus();

        // Then
        assertThat(oldStatus.getStatus()).isEqualTo(CDCHealthStatus.Status.UNKNOWN);
        assertThat(newStatus.getStatus()).isEqualTo(CDCHealthStatus.Status.HEALTHY);
        verify(mockListener).onHealthStatusChanged(eq("test-connector"), eq(oldStatus), any());
    }

    @Test
    void testMetricsUpdate() throws Exception {
        // Given
        ChangeEvent insertEvent = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test-db",
                "test-table",
                Map.of("id", 1)
        );
        ChangeEvent updateEvent = new ChangeEvent(
                ChangeEvent.EventType.UPDATE,
                "test-db",
                "test-table",
                "key2",
                Map.of("id", 2),
                Map.of("id", 2, "name", "updated")
        );
        ChangeEvent deleteEvent = new ChangeEvent(
                ChangeEvent.EventType.DELETE,
                "test-db",
                "test-table",
                "key3",
                Map.of("id", 3),
                null
        );
        connector.addEventToReturn(insertEvent, updateEvent, deleteEvent);
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        connector.poll();
        connector.poll();
        connector.poll();

        // Then
        CDCMetrics metrics = connector.getMetrics();
        assertThat(metrics.getInsertEvents()).isEqualTo(1);
        assertThat(metrics.getUpdateEvents()).isEqualTo(1);
        assertThat(metrics.getDeleteEvents()).isEqualTo(1);
        assertThat(metrics.getTotalEventsCaptured()).isEqualTo(3);
    }

    @Test
    void testPollWithError() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);
        connector.setShouldThrowOnPoll(true);

        // When
        List<ChangeEvent> events = connector.poll();

        // Then
        assertThat(events).isEmpty();
        assertThat(connector.getMetrics().getErrorsCount()).isEqualTo(1);
        assertThat(connector.getHealthStatus().getStatus()).isEqualTo(CDCHealthStatus.Status.DEGRADED);
        verify(mockListener).onConnectorError(eq("test-connector"), any(Exception.class));
    }

    @Test
    void testStartFailsWhenAlreadyRunning() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        CompletableFuture<Void> secondStart = connector.start();
        secondStart.join();

        // Then - second start should be a no-op, connector still running
        assertThat(connector.isRunning()).isTrue();
    }

    @Test
    void testStopFailsWhenNotRunning() {
        // Given - not started

        // When
        CompletableFuture<Void> stopFuture = connector.stop();
        stopFuture.join();

        // Then - should be no-op
        assertThat(connector.isRunning()).isFalse();
    }

    @Test
    void testScheduledPolling() throws Exception {
        // Given
        AtomicInteger pollCount = new AtomicInteger(0);
        connector.setPollCallback(() -> pollCount.incrementAndGet());
        connector.start().get(5, TimeUnit.SECONDS);

        // When - wait for scheduled polls
        Thread.sleep(500);

        // Then - should have polled multiple times
        assertThat(pollCount.get()).isGreaterThan(0);
    }

    @Test
    void testScheduledPollingStopsAfterConnectorStops() throws Exception {
        // Given
        AtomicInteger pollCount = new AtomicInteger(0);
        connector.setPollCallback(() -> pollCount.incrementAndGet());
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        Thread.sleep(200);
        connector.stop().get(5, TimeUnit.SECONDS);
        int countAfterStop = pollCount.get();
        Thread.sleep(200);

        // Then
        assertThat(pollCount.get()).isEqualTo(countAfterStop);
    }

    @Test
    void testMultipleCommits() throws Exception {
        // Given
        connector.start().get(5, TimeUnit.SECONDS);

        // When
        connector.commit("position-1");
        connector.commit("position-2");
        connector.commit("position-3");

        // Then
        assertThat(connector.getCurrentPosition()).isEqualTo("position-3");
        assertThat(connector.getMetrics().getCurrentPosition()).isEqualTo("position-3");
        // Note: CDCMetrics doesn't track commits count, only last commit time
        assertThat(connector.getMetrics().getLastCommitTime()).isNotNull();
    }

    @Test
    void testEventListenerNotificationException() throws Exception {
        // Given
        CDCEventListener failingListener = mock(CDCEventListener.class);
        doThrow(new RuntimeException("Listener error"))
                .when(failingListener).onEventsCapture(any(), anyInt());
        connector.setEventListener(failingListener);

        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test-db",
                "test-table",
                Map.of("id", 1)
        );
        connector.addEventToReturn(event);
        connector.start().get(5, TimeUnit.SECONDS);

        // When - should not throw exception
        List<ChangeEvent> events = connector.poll();

        // Then
        assertThat(events).hasSize(1);
        assertThat(connector.getMetrics().getTotalEventsCaptured()).isEqualTo(1);
    }

    /**
     * Test implementation of AbstractCDCConnector
     */
    private static class TestCDCConnector extends AbstractCDCConnector {
        private final List<ChangeEvent> eventsToReturn = new java.util.ArrayList<>();
        private boolean shouldThrowOnPoll = false;
        private String lastResetPosition;
        private Runnable pollCallback = () -> {};

        public TestCDCConnector(CDCConfiguration configuration) {
            super(configuration);
        }

        public void addEventToReturn(ChangeEvent... events) {
            for (ChangeEvent event : events) {
                this.eventsToReturn.add(event);
            }
        }

        public void setShouldThrowOnPoll(boolean shouldThrow) {
            this.shouldThrowOnPoll = shouldThrow;
        }

        public void setPollCallback(Runnable callback) {
            this.pollCallback = callback;
        }

        public String getLastResetPosition() {
            return lastResetPosition;
        }

        public CDCEventListener getEventListener() {
            return eventListener;
        }

        @Override
        protected void doStart() throws Exception {
            // Simulate startup work
            Thread.sleep(50);
            startScheduledPolling();
        }

        @Override
        protected void doStop() throws Exception {
            // Simulate cleanup work
            Thread.sleep(50);
        }

        @Override
        protected List<ChangeEvent> doPoll() throws Exception {
            pollCallback.run();
            if (shouldThrowOnPoll) {
                throw new RuntimeException("Poll error");
            }
            if (!eventsToReturn.isEmpty()) {
                List<ChangeEvent> events = List.copyOf(eventsToReturn);
                eventsToReturn.clear();
                return events;
            }
            return List.of();
        }

        @Override
        protected void doCommit(String position) throws Exception {
            if (position == null) {
                throw new IllegalArgumentException("Position cannot be null");
            }
        }

        @Override
        protected void doResetToPosition(String position) throws Exception {
            this.lastResetPosition = position;
        }
    }
}
