package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for CDC connectors
 */
@Slf4j
public abstract class AbstractCDCConnector implements CDCConnector {

    protected final CDCConfiguration configuration;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicReference<CDCMetrics> metrics = new AtomicReference<>(new CDCMetrics());
    protected volatile CDCEventListener eventListener;
    protected volatile CDCHealthStatus healthStatus;
    protected volatile String currentPosition;
    protected ScheduledExecutorService scheduler;

    public AbstractCDCConnector(CDCConfiguration configuration) {
        this.configuration = configuration;
        this.healthStatus = CDCHealthStatus.unknown("Connector not started");
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (running.compareAndSet(false, true)) {
                    log.info("Starting CDC connector: {}", getName());

                    doStart();

                    updateHealthStatus(CDCHealthStatus.healthy("Connector started successfully"));
                    notifyEvent(listener -> listener.onConnectorStarted(getName()));

                    log.info("CDC connector started: {}", getName());
                }
            } catch (Exception e) {
                running.set(false);
                updateHealthStatus(CDCHealthStatus.unhealthy("Failed to start: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));
                throw new RuntimeException("Failed to start connector: " + getName(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (running.compareAndSet(true, false)) {
                    log.info("Stopping CDC connector: {}", getName());

                    doStop();

                    if (scheduler != null) {
                        scheduler.shutdown();
                        try {
                            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                                scheduler.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            scheduler.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    }

                    updateHealthStatus(CDCHealthStatus.unknown("Connector stopped"));
                    notifyEvent(listener -> listener.onConnectorStopped(getName()));

                    log.info("CDC connector stopped: {}", getName());
                }
            } catch (Exception e) {
                updateHealthStatus(CDCHealthStatus.unhealthy("Failed to stop: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));
                throw new RuntimeException("Failed to stop connector: " + getName(), e);
            }
        });
    }

    @Override
    public List<ChangeEvent> poll() {
        if (!running.get()) {
            return List.of();
        }

        try {
            List<ChangeEvent> events = doPoll();

            if (events != null && !events.isEmpty()) {
                // Update metrics
                updateMetricsForEvents(events);
                notifyEvent(listener -> listener.onEventsCapture(getName(), events.size()));
                log.debug("Captured {} change events from connector: {}", events.size(), getName());
            }

            return events != null ? events : List.of();

        } catch (Exception e) {
            CDCMetrics currentMetrics = metrics.get();
            metrics.set(currentMetrics.withError());
            updateHealthStatus(CDCHealthStatus.degraded("Error during polling: " + e.getMessage()));
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
            log.error("Error polling change events from connector: {}", getName(), e);
            return List.of();
        }
    }

    @Override
    public void commit(String position) {
        try {
            doCommit(position);
            this.currentPosition = position;

            CDCMetrics currentMetrics = metrics.get();
            metrics.set(currentMetrics.withPosition(position).withCommit());

            notifyEvent(listener -> listener.onPositionCommitted(getName(), position));
            log.debug("Committed position: {} for connector: {}", position, getName());

        } catch (Exception e) {
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
            throw new RuntimeException("Failed to commit position: " + position, e);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String getName() {
        return configuration.getName();
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
        return metrics.get();
    }

    @Override
    public String getCurrentPosition() {
        return currentPosition;
    }

    @Override
    public void resetToPosition(String position) {
        try {
            doResetToPosition(position);
            this.currentPosition = position;
            log.info("Reset connector {} to position: {}", getName(), position);
        } catch (Exception e) {
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
            throw new RuntimeException("Failed to reset to position: " + position, e);
        }
    }

    /**
     * Initialize and start the connector
     * Subclasses should implement this method to perform connector-specific initialization
     */
    protected abstract void doStart() throws Exception;

    /**
     * Stop and cleanup the connector
     * Subclasses should implement this method to perform connector-specific cleanup
     */
    protected abstract void doStop() throws Exception;

    /**
     * Poll for change events from the source
     * Subclasses should implement this method to perform actual change event polling
     *
     * @return list of change events
     */
    protected abstract List<ChangeEvent> doPoll() throws Exception;

    /**
     * Commit the current position
     * Subclasses should implement this method to perform actual position commit
     *
     * @param position the position to commit
     */
    protected abstract void doCommit(String position) throws Exception;

    /**
     * Reset to a specific position
     * Subclasses should implement this method to perform actual position reset
     *
     * @param position the position to reset to
     */
    protected abstract void doResetToPosition(String position) throws Exception;

    /**
     * Start scheduled polling if enabled
     */
    protected void startScheduledPolling() {
        long pollingIntervalMs = configuration.getPollingIntervalMs();
        if (pollingIntervalMs > 0) {
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    poll();
                } catch (Exception e) {
                    log.error("Error in scheduled polling for connector: {}", getName(), e);
                }
            }, pollingIntervalMs, pollingIntervalMs, TimeUnit.MILLISECONDS);

            log.info("Started scheduled polling for connector: {} with interval: {}ms", getName(), pollingIntervalMs);
        }
    }

    private void updateMetricsForEvents(List<ChangeEvent> events) {
        long inserts = 0, updates = 0, deletes = 0, schemaChanges = 0;

        for (ChangeEvent event : events) {
            switch (event.getEventType()) {
                case INSERT:
                    inserts++;
                    break;
                case UPDATE:
                    updates++;
                    break;
                case DELETE:
                    deletes++;
                    break;
                case SCHEMA_CHANGE:
                    schemaChanges++;
                    break;
            }
        }

        CDCMetrics currentMetrics = metrics.get();
        metrics.set(currentMetrics.withEventCounts(inserts, updates, deletes, schemaChanges));
    }

    /**
     * Update health status and notify listeners if changed
     */
    protected void updateHealthStatus(CDCHealthStatus newStatus) {
        CDCHealthStatus oldStatus = this.healthStatus;
        CDCMetrics currentMetrics = metrics.get();

        this.healthStatus = new CDCHealthStatus(
                newStatus.getStatus(),
                newStatus.getMessage(),
                newStatus.getTimestamp(),
                currentMetrics.getTotalEventsCaptured(),
                currentMetrics.getErrorsCount(),
                currentPosition
        );

        if (oldStatus.getStatus() != newStatus.getStatus()) {
            notifyEvent(listener -> listener.onHealthStatusChanged(getName(), oldStatus, this.healthStatus));
        }
    }

    /**
     * Notify event listener safely
     */
    protected void notifyEvent(EventNotification notification) {
        if (eventListener != null) {
            try {
                notification.notify(eventListener);
            } catch (Exception e) {
                log.warn("Error notifying event listener for connector: {}", getName(), e);
            }
        }
    }

    @FunctionalInterface
    protected interface EventNotification {
        void notify(CDCEventListener listener);
    }
}