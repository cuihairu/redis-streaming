package io.github.cuihairu.redis.streaming.source.impl;

import io.github.cuihairu.redis.streaming.source.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for source connectors
 */
@Slf4j
public abstract class AbstractSourceConnector implements SourceConnector {

    protected final SourceConfiguration configuration;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicLong recordsPolledCount = new AtomicLong(0);
    protected final AtomicLong errorsCount = new AtomicLong(0);
    protected volatile SourceEventListener eventListener;
    protected volatile SourceHealthStatus healthStatus;
    protected ScheduledExecutorService scheduler;

    public AbstractSourceConnector(SourceConfiguration configuration) {
        this.configuration = configuration;
        this.healthStatus = SourceHealthStatus.unknown("Connector not started");
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (running.compareAndSet(false, true)) {
                    log.info("Starting source connector: {}", getName());

                    doStart();

                    updateHealthStatus(SourceHealthStatus.healthy("Connector started successfully"));
                    notifyEvent(listener -> listener.onConnectorStarted(getName()));

                    log.info("Source connector started: {}", getName());
                }
            } catch (Exception e) {
                running.set(false);
                updateHealthStatus(SourceHealthStatus.unhealthy("Failed to start: " + e.getMessage()));
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
                    log.info("Stopping source connector: {}", getName());

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

                    updateHealthStatus(SourceHealthStatus.unknown("Connector stopped"));
                    notifyEvent(listener -> listener.onConnectorStopped(getName()));

                    log.info("Source connector stopped: {}", getName());
                }
            } catch (Exception e) {
                updateHealthStatus(SourceHealthStatus.unhealthy("Failed to stop: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));
                throw new RuntimeException("Failed to stop connector: " + getName(), e);
            }
        });
    }

    @Override
    public List<SourceRecord> poll() {
        if (!running.get()) {
            return Collections.emptyList();
        }

        try {
            List<SourceRecord> records = doPoll();

            if (records != null && !records.isEmpty()) {
                recordsPolledCount.addAndGet(records.size());
                notifyEvent(listener -> listener.onRecordsPolled(getName(), records.size()));
                log.debug("Polled {} records from connector: {}", records.size(), getName());
            }

            return records != null ? records : Collections.emptyList();

        } catch (Exception e) {
            errorsCount.incrementAndGet();
            updateHealthStatus(SourceHealthStatus.degraded("Error during polling: " + e.getMessage()));
            notifyEvent(listener -> listener.onConnectorError(getName(), e));
            log.error("Error polling records from connector: {}", getName(), e);
            return Collections.emptyList();
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
    public SourceConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void setEventListener(SourceEventListener listener) {
        this.eventListener = listener;
    }

    @Override
    public SourceHealthStatus getHealthStatus() {
        return healthStatus;
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
     * Poll for records from the source
     * Subclasses should implement this method to perform actual data polling
     *
     * @return list of source records
     */
    protected abstract List<SourceRecord> doPoll() throws Exception;

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

    /**
     * Update health status and notify listeners if changed
     */
    protected void updateHealthStatus(SourceHealthStatus newStatus) {
        SourceHealthStatus oldStatus = this.healthStatus;
        this.healthStatus = new SourceHealthStatus(
                newStatus.getStatus(),
                newStatus.getMessage(),
                newStatus.getTimestamp(),
                recordsPolledCount.get(),
                errorsCount.get()
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
    private interface EventNotification {
        void notify(SourceEventListener listener);
    }
}