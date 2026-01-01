package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for CDCManager
 */
class CDCManagerTest {

    private CDCManager manager;
    private MockCDCConnector connector1;
    private MockCDCConnector connector2;

    @BeforeEach
    void setUp() {
        manager = new CDCManager();
        connector1 = new MockCDCConnector("connector1");
        connector2 = new MockCDCConnector("connector2");
    }

    @AfterEach
    void tearDown() {
        if (manager.isRunning()) {
            manager.stop().join();
        }
    }

    @Test
    void testAddConnector() {
        // When
        manager.addConnector(connector1);

        // Then
        assertThat(manager.getConnectorCount()).isEqualTo(1);
        assertThat(manager.getConnector("connector1")).isEqualTo(connector1);
    }

    @Test
    void testAddDuplicateConnectorThrowsException() {
        // Given
        manager.addConnector(connector1);

        // When/Then
        assertThatThrownBy(() -> manager.addConnector(connector1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void testRemoveConnector() {
        // Given
        manager.addConnector(connector1);

        // When
        CDCConnector removed = manager.removeConnector("connector1");

        // Then
        assertThat(removed).isEqualTo(connector1);
        assertThat(manager.getConnectorCount()).isEqualTo(0);
    }

    @Test
    void testRemoveNonExistentConnector() {
        // When
        CDCConnector removed = manager.removeConnector("nonexistent");

        // Then
        assertThat(removed).isNull();
    }

    @Test
    void testRemoveConnectorStopsRunningConnector() throws Exception {
        // Given
        manager.addConnector(connector1);
        connector1.start().get();

        // When
        manager.removeConnector("connector1");

        // Then
        assertThat(connector1.isRunning()).isFalse();
    }

    @Test
    void testRemoveConnectorIgnoresStopFailure() throws Exception {
        // Given
        CDCConnector bad = new CDCConnector() {
            private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

            @Override
            public CompletableFuture<Void> start() {
                return CompletableFuture.runAsync(() -> running.set(true));
            }

            @Override
            public CompletableFuture<Void> stop() {
                running.set(false);
                return CompletableFuture.failedFuture(new RuntimeException("stop failed"));
            }

            @Override
            public List<ChangeEvent> poll() {
                return List.of();
            }

            @Override
            public void commit(String position) {
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }

            @Override
            public String getName() {
                return "bad";
            }

            @Override
            public CDCConfiguration getConfiguration() {
                return CDCConfigurationBuilder.forDatabasePolling("bad")
                        .jdbcUrl("jdbc:h2:mem:test")
                        .username("sa")
                        .password("")
                        .build();
            }

            @Override
            public void setEventListener(CDCEventListener listener) {
            }

            @Override
            public CDCHealthStatus getHealthStatus() {
                return CDCHealthStatus.unknown("n/a");
            }

            @Override
            public CDCMetrics getMetrics() {
                return new CDCMetrics();
            }

            @Override
            public String getCurrentPosition() {
                return null;
            }

            @Override
            public void resetToPosition(String position) {
            }
        };
        manager.addConnector(bad);
        bad.start().get();

        // When
        CDCConnector removed = manager.removeConnector("bad");

        // Then
        assertThat(removed).isSameAs(bad);
        assertThat(manager.getConnector("bad")).isNull();
        assertThat(bad.isRunning()).isFalse();
    }

    @Test
    void testGetConnector() {
        // Given
        manager.addConnector(connector1);

        // When
        CDCConnector connector = manager.getConnector("connector1");

        // Then
        assertThat(connector).isEqualTo(connector1);
    }

    @Test
    void testGetNonExistentConnector() {
        // When
        CDCConnector connector = manager.getConnector("nonexistent");

        // Then
        assertThat(connector).isNull();
    }

    @Test
    void testGetAllConnectors() {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);

        // When
        List<CDCConnector> connectors = manager.getAllConnectors();

        // Then
        assertThat(connectors).hasSize(2);
        assertThat(connectors).containsExactlyInAnyOrder(connector1, connector2);
    }

    @Test
    void testStart() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);

        // When
        CompletableFuture<Void> startFuture = manager.start();
        startFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(manager.isRunning()).isTrue();
        assertThat(connector1.isRunning()).isTrue();
        assertThat(connector2.isRunning()).isTrue();
        assertThat(manager.getRunningConnectorCount()).isEqualTo(2);
    }

    @Test
    void testStartWhenAlreadyRunning() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.start().get();

        // When
        CompletableFuture<Void> secondStart = manager.start();
        secondStart.get();

        // Then - should be no-op
        assertThat(manager.isRunning()).isTrue();
    }

    @Test
    void testStop() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);
        manager.start().get();

        // When
        CompletableFuture<Void> stopFuture = manager.stop();
        stopFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(manager.isRunning()).isFalse();
        assertThat(connector1.isRunning()).isFalse();
        assertThat(connector2.isRunning()).isFalse();
        assertThat(manager.getRunningConnectorCount()).isEqualTo(0);
    }

    @Test
    void testStopWhenNotRunning() {
        // Given - not started

        // When
        CompletableFuture<Void> stopFuture = manager.stop();
        stopFuture.join();

        // Then - should be no-op
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void testPollAll() throws Exception {
        // Given
        ChangeEvent event1 = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "db1",
                "table1",
                Map.of("id", 1, "data", "test1")
        );
        ChangeEvent event2 = new ChangeEvent(
                ChangeEvent.EventType.UPDATE,
                "db2",
                "table2",
                Map.of("id", 2, "data", "test2")
        );
        connector1.addEventToReturn(event1);
        connector2.addEventToReturn(event2);

        manager.addConnector(connector1);
        manager.addConnector(connector2);
        manager.start().get();

        // When
        Map<String, List<ChangeEvent>> allEvents = manager.pollAll();

        // Then
        assertThat(allEvents).hasSize(2);
        assertThat(allEvents.get("connector1")).hasSize(1);
        assertThat(allEvents.get("connector1").get(0)).isEqualTo(event1);
        assertThat(allEvents.get("connector2")).hasSize(1);
        assertThat(allEvents.get("connector2").get(0)).isEqualTo(event2);
    }

    @Test
    void testPollAllReturnsEmptyMapWhenNoConnectors() {
        // When
        Map<String, List<ChangeEvent>> allEvents = manager.pollAll();

        // Then
        assertThat(allEvents).isEmpty();
    }

    @Test
    void testCommitAll() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);
        manager.start().get();

        Map<String, String> positions = Map.of(
                "connector1", "position1",
                "connector2", "position2"
        );

        // When
        manager.commitAll(positions);

        // Then
        assertThat(connector1.getLastCommittedPosition()).isEqualTo("position1");
        assertThat(connector2.getLastCommittedPosition()).isEqualTo("position2");
    }

    @Test
    void testCommitAllWithNonExistentConnector() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.start().get();

        Map<String, String> positions = Map.of(
                "connector1", "position1",
                "nonexistent", "position2"
        );

        // When - should not throw
        manager.commitAll(positions);

        // Then
        assertThat(connector1.getLastCommittedPosition()).isEqualTo("position1");
    }

    @Test
    void testGetHealthStatusAll() {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);

        // When
        Map<String, CDCHealthStatus> healthStatus = manager.getHealthStatusAll();

        // Then
        assertThat(healthStatus).hasSize(2);
        assertThat(healthStatus.get("connector1")).isNotNull();
        assertThat(healthStatus.get("connector2")).isNotNull();
    }

    @Test
    void testGetMetricsAll() {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);

        // When
        Map<String, CDCMetrics> metrics = manager.getMetricsAll();

        // Then
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get("connector1")).isNotNull();
        assertThat(metrics.get("connector2")).isNotNull();
    }

    @Test
    void testGetCurrentPositionsAll() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);
        manager.start().get();
        connector1.setCurrentPosition("pos1");
        connector2.setCurrentPosition("pos2");

        // When
        Map<String, String> positions = manager.getCurrentPositionsAll();

        // Then
        assertThat(positions).hasSize(2);
        assertThat(positions.get("connector1")).isEqualTo("pos1");
        assertThat(positions.get("connector2")).isEqualTo("pos2");
    }

    @Test
    void testGetConnectorCount() {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);

        // When
        int count = manager.getConnectorCount();

        // Then
        assertThat(count).isEqualTo(2);
    }

    @Test
    void testGetRunningConnectorCount() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);
        manager.start().get();

        // When
        int runningCount = manager.getRunningConnectorCount();

        // Then
        assertThat(runningCount).isEqualTo(2);
    }

    @Test
    void testGetRunningConnectorCountWithPartialStart() throws Exception {
        // Given
        manager.addConnector(connector1);
        manager.addConnector(connector2);
        connector1.start().get();

        // When
        int runningCount = manager.getRunningConnectorCount();

        // Then
        assertThat(runningCount).isEqualTo(1);
    }

    @Test
    void testStartWithNoConnectors() throws Exception {
        // When
        CompletableFuture<Void> startFuture = manager.start();
        startFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.getRunningConnectorCount()).isEqualTo(0);
    }

    /**
     * Mock CDC connector for testing
     */
    private static class MockCDCConnector implements CDCConnector {
        private final String name;
        private final List<ChangeEvent> eventsToReturn = new java.util.ArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
        private CDCConfiguration configuration;
        private CDCEventListener eventListener;
        private CDCHealthStatus healthStatus = CDCHealthStatus.unknown("Not started");
        private CDCMetrics metrics = new CDCMetrics();
        private String currentPosition;
        private String lastCommittedPosition;

        public MockCDCConnector(String name) {
            this.name = name;
            this.configuration = CDCConfigurationBuilder.forDatabasePolling(name)
                    .jdbcUrl("jdbc:h2:mem:test")
                    .username("sa")
                    .password("")
                    .build();
        }

        public void addEventToReturn(ChangeEvent... events) {
            for (ChangeEvent event : events) {
                this.eventsToReturn.add(event);
            }
        }

        public void setCurrentPosition(String position) {
            this.currentPosition = position;
        }

        public String getLastCommittedPosition() {
            return lastCommittedPosition;
        }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                running.set(true);
                healthStatus = CDCHealthStatus.healthy("Started");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                running.set(false);
                healthStatus = CDCHealthStatus.unknown("Stopped");
            });
        }

        @Override
        public List<ChangeEvent> poll() {
            if (!running.get()) {
                return List.of();
            }
            if (!eventsToReturn.isEmpty()) {
                List<ChangeEvent> events = List.copyOf(eventsToReturn);
                eventsToReturn.clear();
                return events;
            }
            return List.of();
        }

        @Override
        public void commit(String position) {
            this.lastCommittedPosition = position;
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public CDCConfiguration getConfiguration() {
            return configuration;
        }

        @Override
        public void setEventListener(CDCEventListener listener) {
            this.eventListener = listener;
        }

        @Override
        public CDCHealthStatus getHealthStatus() {
            return healthStatus;
        }

        @Override
        public CDCMetrics getMetrics() {
            return metrics;
        }

        @Override
        public String getCurrentPosition() {
            return currentPosition;
        }

        @Override
        public void resetToPosition(String position) {
            this.currentPosition = position;
        }
    }
}
