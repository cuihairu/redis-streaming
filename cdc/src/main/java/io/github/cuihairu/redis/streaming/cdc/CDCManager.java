package io.github.cuihairu.redis.streaming.cdc;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manager for coordinating multiple CDC connectors
 */
@Slf4j
public class CDCManager {

    private final Map<String, CDCConnector> connectors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private volatile boolean running = false;

    /**
     * Add a connector to the manager
     *
     * @param connector the connector to add
     */
    public void addConnector(CDCConnector connector) {
        String name = connector.getName();
        if (connectors.containsKey(name)) {
            throw new IllegalArgumentException("Connector with name already exists: " + name);
        }
        connectors.put(name, connector);
        log.info("Added CDC connector: {}", name);
    }

    /**
     * Remove a connector from the manager
     *
     * @param name the connector name
     * @return the removed connector, or null if not found
     */
    public CDCConnector removeConnector(String name) {
        CDCConnector connector = connectors.remove(name);
        if (connector != null) {
            if (connector.isRunning()) {
                connector.stop();
            }
            log.info("Removed CDC connector: {}", name);
        }
        return connector;
    }

    /**
     * Get a connector by name
     *
     * @param name the connector name
     * @return the connector, or null if not found
     */
    public CDCConnector getConnector(String name) {
        return connectors.get(name);
    }

    /**
     * Get all connectors
     *
     * @return list of all connectors
     */
    public List<CDCConnector> getAllConnectors() {
        return List.copyOf(connectors.values());
    }

    /**
     * Start the CDC manager and all connectors
     *
     * @return CompletableFuture that completes when all connectors are started
     */
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.completedFuture(null);
        }

        running = true;
        log.info("Starting CDC manager with {} connectors", connectors.size());

        // Start all connectors in parallel
        List<CompletableFuture<Void>> startFutures = connectors.values().stream()
                .map(CDCConnector::start)
                .collect(Collectors.toList());

        return CompletableFuture.allOf(startFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    startHealthMonitoring();
                    log.info("CDC manager started successfully");
                });
    }

    /**
     * Stop the CDC manager and all connectors
     *
     * @return CompletableFuture that completes when all connectors are stopped
     */
    public CompletableFuture<Void> stop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        running = false;
        log.info("Stopping CDC manager");

        // Stop all connectors in parallel
        List<CompletableFuture<Void>> stopFutures = connectors.values().stream()
                .map(CDCConnector::stop)
                .collect(Collectors.toList());

        return CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    scheduler.shutdown();
                    try {
                        if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    log.info("CDC manager stopped");
                });
    }

    /**
     * Poll all connectors for change events
     *
     * @return map of connector name to change events
     */
    public Map<String, List<ChangeEvent>> pollAll() {
        return connectors.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().poll()
                ));
    }

    /**
     * Commit positions for all connectors
     *
     * @param positions map of connector name to position
     */
    public void commitAll(Map<String, String> positions) {
        positions.forEach((name, position) -> {
            CDCConnector connector = connectors.get(name);
            if (connector != null) {
                try {
                    connector.commit(position);
                } catch (Exception e) {
                    log.error("Error committing position for connector: {}", name, e);
                }
            }
        });
    }

    /**
     * Get health status for all connectors
     *
     * @return map of connector name to health status
     */
    public Map<String, CDCHealthStatus> getHealthStatusAll() {
        return connectors.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getHealthStatus()
                ));
    }

    /**
     * Get metrics for all connectors
     *
     * @return map of connector name to metrics
     */
    public Map<String, CDCMetrics> getMetricsAll() {
        return connectors.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getMetrics()
                ));
    }

    /**
     * Get current positions for all connectors
     *
     * @return map of connector name to current position
     */
    public Map<String, String> getCurrentPositionsAll() {
        return connectors.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getCurrentPosition()
                ));
    }

    /**
     * Check if the manager is running
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the number of connectors
     *
     * @return connector count
     */
    public int getConnectorCount() {
        return connectors.size();
    }

    /**
     * Get the number of running connectors
     *
     * @return running connector count
     */
    public int getRunningConnectorCount() {
        return (int) connectors.values().stream()
                .mapToLong(connector -> connector.isRunning() ? 1 : 0)
                .sum();
    }

    private void startHealthMonitoring() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                monitorConnectorHealth();
            } catch (Exception e) {
                log.error("Error in health monitoring", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void monitorConnectorHealth() {
        connectors.forEach((name, connector) -> {
            CDCHealthStatus status = connector.getHealthStatus();
            if (status.isUnhealthy()) {
                log.warn("Connector {} is unhealthy: {}", name, status.getMessage());
            }
        });
    }
}