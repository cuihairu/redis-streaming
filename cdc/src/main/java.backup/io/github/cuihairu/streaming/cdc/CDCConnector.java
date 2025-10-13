package io.github.cuihairu.redis.streaming.cdc;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CDC connector interface for capturing database changes
 */
public interface CDCConnector {

    /**
     * Start the CDC connector
     *
     * @return future that completes when the connector is started
     */
    CompletableFuture<Void> start();

    /**
     * Stop the CDC connector
     *
     * @return future that completes when the connector is stopped
     */
    CompletableFuture<Void> stop();

    /**
     * Poll for change events
     *
     * @return list of change events, empty if no changes available
     */
    List<ChangeEvent> poll();

    /**
     * Commit the current position to prevent re-processing
     *
     * @param position the position to commit
     */
    void commit(String position);

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
    CDCConfiguration getConfiguration();

    /**
     * Set event listener for connector events
     *
     * @param listener the event listener
     */
    void setEventListener(CDCEventListener listener);

    /**
     * Get connector health status
     *
     * @return health status
     */
    CDCHealthStatus getHealthStatus();

    /**
     * Get connector metrics
     *
     * @return connector metrics
     */
    CDCMetrics getMetrics();

    /**
     * Get the current position in the change log
     *
     * @return current position
     */
    String getCurrentPosition();

    /**
     * Reset to a specific position in the change log
     *
     * @param position the position to reset to
     */
    void resetToPosition(String position);
}