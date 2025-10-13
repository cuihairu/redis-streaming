package io.github.cuihairu.redis.streaming.source;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Data source connector interface for reading data from external systems
 */
public interface SourceConnector {

    /**
     * Start the source connector
     *
     * @return future that completes when the connector is started
     */
    CompletableFuture<Void> start();

    /**
     * Stop the source connector
     *
     * @return future that completes when the connector is stopped
     */
    CompletableFuture<Void> stop();

    /**
     * Poll for new records from the source
     *
     * @return list of source records, empty if no data available
     */
    List<SourceRecord> poll();

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
    SourceConfiguration getConfiguration();

    /**
     * Set event listener for connector events
     *
     * @param listener the event listener
     */
    void setEventListener(SourceEventListener listener);

    /**
     * Get connector health status
     *
     * @return health status
     */
    SourceHealthStatus getHealthStatus();
}