package io.github.cuihairu.redis.streaming.sink.impl;

import io.github.cuihairu.redis.streaming.sink.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for sink connectors
 */
@Slf4j
public abstract class AbstractSinkConnector implements SinkConnector {

    protected final SinkConfiguration configuration;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicReference<SinkMetrics> metrics = new AtomicReference<>(new SinkMetrics());
    protected volatile SinkEventListener eventListener;
    protected volatile SinkHealthStatus healthStatus;
    protected ScheduledExecutorService scheduler;

    public AbstractSinkConnector(SinkConfiguration configuration) {
        this.configuration = configuration;
        this.healthStatus = SinkHealthStatus.unknown("Connector not started");
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (running.compareAndSet(false, true)) {
                    log.info("Starting sink connector: {}", getName());

                    doStart();

                    // Start auto-flush if enabled
                    if (configuration.isAutoFlush() && configuration.getFlushIntervalMs() > 0) {
                        startAutoFlush();
                    }

                    updateHealthStatus(SinkHealthStatus.healthy("Connector started successfully"));
                    notifyEvent(listener -> listener.onConnectorStarted(getName()));

                    log.info("Sink connector started: {}", getName());
                }
            } catch (Exception e) {
                running.set(false);
                updateHealthStatus(SinkHealthStatus.unhealthy("Failed to start: " + e.getMessage()));
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
                    log.info("Stopping sink connector: {}", getName());

                    // Flush any remaining data
                    flush().get(30, TimeUnit.SECONDS);

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

                    updateHealthStatus(SinkHealthStatus.unknown("Connector stopped"));
                    notifyEvent(listener -> listener.onConnectorStopped(getName()));

                    log.info("Sink connector stopped: {}", getName());
                }
            } catch (Exception e) {
                updateHealthStatus(SinkHealthStatus.unhealthy("Failed to stop: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));
                throw new RuntimeException("Failed to stop connector: " + getName(), e);
            }
        });
    }

    @Override
    public CompletableFuture<SinkResult> write(Collection<SinkRecord> records) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                    SinkResult.failure("Connector is not running"));
        }

        if (records == null || records.isEmpty()) {
            return CompletableFuture.completedFuture(
                    SinkResult.success(0, "No records to write"));
        }

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                SinkResult result = doWrite(records);

                // Update metrics
                long latency = System.currentTimeMillis() - startTime;
                updateMetrics(result, latency, estimateBytes(records));

                // Update health status
                if (result.isSuccess()) {
                    updateHealthStatus(SinkHealthStatus.healthy("Successfully wrote " + result.getRecordsWritten() + " records"));
                } else if (result.getStatus() == SinkResult.Status.PARTIAL_SUCCESS) {
                    updateHealthStatus(SinkHealthStatus.degraded("Partial success: " + result.getMessage()));
                } else {
                    updateHealthStatus(SinkHealthStatus.degraded("Write failed: " + result.getMessage()));
                }

                notifyEvent(listener -> listener.onRecordsWritten(getName(), result));

                log.debug("Wrote {} records to connector: {} in {}ms",
                        result.getRecordsWritten(), getName(), latency);

                return result;

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - startTime;
                SinkResult errorResult = SinkResult.failure("Write error: " + e.getMessage());

                updateMetrics(errorResult, latency, 0);
                updateHealthStatus(SinkHealthStatus.degraded("Write error: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));

                log.error("Error writing records to connector: {}", getName(), e);
                return errorResult;
            }
        });
    }

    @Override
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(() -> {
            try {
                doFlush();

                // Update metrics
                SinkMetrics currentMetrics = metrics.get();
                metrics.set(currentMetrics.withFlush());

                notifyEvent(listener -> listener.onDataFlushed(getName()));
                log.debug("Flushed data for connector: {}", getName());

            } catch (Exception e) {
                updateHealthStatus(SinkHealthStatus.degraded("Flush error: " + e.getMessage()));
                notifyEvent(listener -> listener.onConnectorError(getName(), e));
                throw new RuntimeException("Failed to flush connector: " + getName(), e);
            }
        });
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
    public SinkConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void setEventListener(SinkEventListener listener) {
        this.eventListener = listener;
    }

    @Override
    public SinkHealthStatus getHealthStatus() {
        return healthStatus;
    }

    @Override
    public SinkMetrics getMetrics() {
        return metrics.get();
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
     * Write records to the sink
     * Subclasses should implement this method to perform actual data writing
     *
     * @param records the records to write
     * @return write result
     */
    protected abstract SinkResult doWrite(Collection<SinkRecord> records) throws Exception;

    /**
     * Flush any buffered data to the sink
     * Subclasses should implement this method to perform actual data flushing
     */
    protected abstract void doFlush() throws Exception;

    private void startAutoFlush() {
        long flushInterval = configuration.getFlushIntervalMs();
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (running.get()) {
                    flush().get(30, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("Error in auto-flush for connector: {}", getName(), e);
            }
        }, flushInterval, flushInterval, TimeUnit.MILLISECONDS);

        log.info("Started auto-flush for connector: {} with interval: {}ms", getName(), flushInterval);
    }

    private void updateMetrics(SinkResult result, long latencyMs, long bytes) {
        SinkMetrics currentMetrics = metrics.get();
        SinkMetrics newMetrics = currentMetrics
                .withUpdatedCounts(result.getRecordsWritten(), result.getRecordsFailed(), bytes)
                .withLatency(latencyMs);
        metrics.set(newMetrics);
    }

    private long estimateBytes(Collection<SinkRecord> records) {
        // Simple estimation - in real implementation this would be more accurate
        return records.size() * 100L; // Assume average 100 bytes per record
    }

    /**
     * Update health status and notify listeners if changed
     */
    protected void updateHealthStatus(SinkHealthStatus newStatus) {
        SinkHealthStatus oldStatus = this.healthStatus;
        SinkMetrics currentMetrics = metrics.get();

        this.healthStatus = new SinkHealthStatus(
                newStatus.getStatus(),
                newStatus.getMessage(),
                newStatus.getTimestamp(),
                currentMetrics.getRecordsWritten(),
                currentMetrics.getRecordsFailed()
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
        void notify(SinkEventListener listener);
    }
}