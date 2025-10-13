package io.github.cuihairu.redis.streaming.sink;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Data sink connector interface for writing data to external systems
 */
public interface SinkConnector {

    /**
     * Start the sink connector
     *
     * @return future that completes when the connector is started
     */
    CompletableFuture<Void> start();

    /**
     * Stop the sink connector
     *
     * @return future that completes when the connector is stopped
     */
    CompletableFuture<Void> stop();

    /**
     * Write records to the sink
     *
     * @param records the records to write
     * @return future that completes when records are written
     */
    CompletableFuture<SinkResult> write(Collection<SinkRecord> records);

    /**
     * Flush any buffered data to the sink
     *
     * @return future that completes when data is flushed
     */
    CompletableFuture<Void> flush();

    /**
     * Check if the connector is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();

    /**
     * Get the connector name
     *
     * @return connector name
     */
    String getName();

    /**
     * Get connector configuration
     *
     * @return configuration object
     */
    SinkConfiguration getConfiguration();

    /**
     * Set event listener for connector events
     *
     * @param listener the event listener
     */
    void setEventListener(SinkEventListener listener);

    /**
     * Get connector health status
     *
     * @return health status
     */
    SinkHealthStatus getHealthStatus();

    /**
     * Get connector metrics
     *
     * @return connector metrics
     */
    SinkMetrics getMetrics();
}