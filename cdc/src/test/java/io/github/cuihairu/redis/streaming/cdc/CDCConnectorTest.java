package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDCConnector interface
 */
class CDCConnectorTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(CDCConnector.class.isInterface());
    }

    @Test
    void testStartMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("start"));
    }

    @Test
    void testStopMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("stop"));
    }

    @Test
    void testPollMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("poll"));
    }

    @Test
    void testCommitMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("commit", String.class));
    }

    @Test
    void testIsRunningMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("isRunning"));
    }

    @Test
    void testGetNameMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("getName"));
    }

    @Test
    void testGetConfigurationMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("getConfiguration"));
    }

    @Test
    void testSetEventListenerMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("setEventListener", CDCEventListener.class));
    }

    @Test
    void testGetHealthStatusMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("getHealthStatus"));
    }

    @Test
    void testGetMetricsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("getMetrics"));
    }

    @Test
    void testGetCurrentPositionMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("getCurrentPosition"));
    }

    @Test
    void testResetToPositionMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CDCConnector.class.getMethod("resetToPosition", String.class));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        CDCConnector connector = new CDCConnector() {
            private boolean running = false;
            private String currentPosition = "0";

            @Override
            public CompletableFuture<Void> start() {
                running = true;
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                running = false;
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
                currentPosition = position;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public String getName() {
                return "test-connector";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
                // Simple implementation
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return currentPosition;
            }

            @Override
            public void resetToPosition(String position) {
                currentPosition = position;
            }
        };

        // When & Then - verify methods can be called
        assertDoesNotThrow(() -> connector.start().get());
        assertTrue(connector.isRunning());
        assertNotNull(connector.poll());
        assertEquals("test-connector", connector.getName());
        CDCHealthStatus status = connector.getHealthStatus();
        assertEquals(CDCHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("OK", status.getMessage());
        assertNotNull(connector.getMetrics());
        assertEquals("0", connector.getCurrentPosition());
        assertDoesNotThrow(() -> connector.commit("100"));
        assertEquals("100", connector.getCurrentPosition());
        assertDoesNotThrow(() -> connector.resetToPosition("50"));
        assertEquals("50", connector.getCurrentPosition());
        assertDoesNotThrow(() -> connector.stop().get());
        assertFalse(connector.isRunning());
    }

    @Test
    void testStartStopLifecycle() throws Exception {
        // Given
        CDCConnector connector = new CDCConnector() {
            private boolean running = false;

            @Override
            public CompletableFuture<Void> start() {
                running = true;
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                running = false;
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When & Then
        assertFalse(connector.isRunning());
        connector.start().get();
        assertTrue(connector.isRunning());
        connector.stop().get();
        assertFalse(connector.isRunning());
    }

    @Test
    void testPollReturnsEmptyListInitially() {
        // Given
        CDCConnector connector = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When
        List<ChangeEvent> events = connector.poll();

        // Then
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testPollReturnsChangeEvents() {
        // Given
        ChangeEvent event1 = new ChangeEvent();
        event1.setEventType(ChangeEvent.EventType.INSERT);
        event1.setTable("users");

        ChangeEvent event2 = new ChangeEvent();
        event2.setEventType(ChangeEvent.EventType.UPDATE);
        event2.setTable("orders");

        CDCConnector connector = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Arrays.asList(event1, event2);
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When
        List<ChangeEvent> events = connector.poll();

        // Then
        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals(ChangeEvent.EventType.INSERT, events.get(0).getEventType());
        assertEquals(ChangeEvent.EventType.UPDATE, events.get(1).getEventType());
    }

    @Test
    void testCommitPosition() {
        // Given
        CDCConnector connector = new CDCConnector() {
            private String committedPosition = "0";

            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
                committedPosition = position;
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return committedPosition;
            }

            @Override
            public void resetToPosition(String position) {
                committedPosition = position;
            }
        };

        // When
        connector.commit("100");
        String position = connector.getCurrentPosition();

        // Then
        assertEquals("100", position);
    }

    @Test
    void testResetToPosition() {
        // Given
        CDCConnector connector = new CDCConnector() {
            private String position = "500";

            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return position;
            }

            @Override
            public void resetToPosition(String newPosition) {
                position = newPosition;
            }
        };

        // When
        connector.resetToPosition("100");
        String position = connector.getCurrentPosition();

        // Then
        assertEquals("100", position);
    }

    @Test
    void testGetHealthStatus() {
        // Given
        CDCConnector connector = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When
        CDCHealthStatus status = connector.getHealthStatus();

        // Then
        assertEquals(CDCHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("OK", status.getMessage());
    }

    @Test
    void testSetEventListener() {
        // Given
        CDCConnector connector = new CDCConnector() {
            private CDCEventListener listener;

            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
                this.listener = listener;
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When & Then - should not throw
        CDCEventListener listener = new CDCEventListener() {
        };
        assertDoesNotThrow(() -> connector.setEventListener(listener));
    }

    @Test
    void testGetMetrics() {
        // Given
        CDCConnector connector = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics(
                    100, // totalEventsCaptured
                    50,  // insertEvents
                    30,  // updateEvents
                    15,  // deleteEvents
                    5,   // schemaChangeEvents
                    0,   // snapshotRecords
                    2,   // errorsCount
                    10.5,// averageEventLatencyMs
                    null,// lastEventTime
                    null,// lastCommitTime
                    java.time.Instant.now(), // startTime
                    null // currentPosition
                );
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When
        CDCMetrics metrics = connector.getMetrics();

        // Then
        assertNotNull(metrics);
    }

    @Test
    void testAsyncStartStop() throws Exception {
        // Given - connector with async operations
        CDCConnector connector = new CDCConnector() {
            private boolean running = false;

            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(100);
                        running = true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(100);
                        running = false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When & Then
        assertFalse(connector.isRunning());
        connector.start().get();
        assertTrue(connector.isRunning());
        connector.stop().get();
        assertFalse(connector.isRunning());
    }

    @Test
    void testMultipleConnectors() {
        // Given
        CDCConnector connector1 = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "connector-1";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        CDCConnector connector2 = new CDCConnector() {
            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<ChangeEvent> poll() {
                return Collections.emptyList();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public String getName() {
                return "connector-2";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return null;
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.healthy("OK");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return "0";
            }

            @Override
            public void resetToPosition(String position) {
            }
        };

        // When & Then
        assertEquals("connector-1", connector1.getName());
        assertEquals("connector-2", connector2.getName());
        assertNotEquals(connector1.getName(), connector2.getName());
    }
}
