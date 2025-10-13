package io.github.cuihairu.redis.streaming.sink;

/**
 * Sink connector event listener interface
 */
public interface SinkEventListener {

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
     * Called when records are written successfully
     *
     * @param connectorName the connector name
     * @param result the write result
     */
    default void onRecordsWritten(String connectorName, SinkResult result) {}

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
    default void onHealthStatusChanged(String connectorName, SinkHealthStatus oldStatus, SinkHealthStatus newStatus) {}

    /**
     * Called when data is flushed
     *
     * @param connectorName the connector name
     */
    default void onDataFlushed(String connectorName) {}
}