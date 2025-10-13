package io.github.cuihairu.redis.streaming.cdc;

/**
 * CDC connector event listener interface
 */
public interface CDCEventListener {

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
     * Called when change events are captured
     *
     * @param connectorName the connector name
     * @param eventCount number of events captured
     */
    default void onEventsCapture(String connectorName, int eventCount) {}

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
    default void onHealthStatusChanged(String connectorName, CDCHealthStatus oldStatus, CDCHealthStatus newStatus) {}

    /**
     * Called when position is committed
     *
     * @param connectorName the connector name
     * @param position the committed position
     */
    default void onPositionCommitted(String connectorName, String position) {}

    /**
     * Called when snapshot starts
     *
     * @param connectorName the connector name
     * @param tableCount number of tables to snapshot
     */
    default void onSnapshotStarted(String connectorName, int tableCount) {}

    /**
     * Called when snapshot completes
     *
     * @param connectorName the connector name
     * @param recordCount number of records captured in snapshot
     */
    default void onSnapshotCompleted(String connectorName, long recordCount) {}
}