package io.github.cuihairu.redis.streaming.source;

/**
 * Source connector event listener interface
 */
public interface SourceEventListener {

    /**
     * Called when connector starts successfully
     *
     * @param connectorName the connector name
     */
    default void onConnectorStarted(String connectorName) {}

    /**
     * Called when connector stops
     *
     * @param connectorName the connector name
     */
    default void onConnectorStopped(String connectorName) {}

    /**
     * Called when records are polled successfully
     *
     * @param connectorName the connector name
     * @param recordCount number of records polled
     */
    default void onRecordsPolled(String connectorName, int recordCount) {}

    /**
     * Called when an error occurs in the connector
     *
     * @param connectorName the connector name
     * @param error the error
     */
    default void onConnectorError(String connectorName, Throwable error) {}

    /**
     * Called when connector health status changes
     *
     * @param connectorName the connector name
     * @param oldStatus the old health status
     * @param newStatus the new health status
     */
    default void onHealthStatusChanged(String connectorName, SourceHealthStatus oldStatus, SourceHealthStatus newStatus) {}
}