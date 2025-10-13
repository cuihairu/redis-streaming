package io.github.cuihairu.redis.streaming.source;

import io.github.cuihairu.redis.streaming.core.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.core.mq.MessageQueueFactory;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Source manager for managing multiple source connectors
 */
@Slf4j
public class SourceManager implements SourceEventListener {

    private final Map<String, SourceConnector> connectors = new ConcurrentHashMap<>();
    private final SourceConnectorFactory factory;
    private final MessageProducer messageProducer;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public SourceManager(RedissonClient redissonClient) {
        this.factory = new SourceConnectorFactory();
        MessageQueueFactory mqFactory = new MessageQueueFactory(redissonClient);
        this.messageProducer = mqFactory.createProducer();
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    public SourceManager(SourceConnectorFactory factory, MessageProducer messageProducer) {
        this.factory = factory;
        this.messageProducer = messageProducer;
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    /**
     * Add a source connector
     *
     * @param configuration the source configuration
     * @return the created connector
     */
    public SourceConnector addConnector(SourceConfiguration configuration) {
        String name = configuration.getName();

        if (connectors.containsKey(name)) {
            throw new IllegalArgumentException("Connector already exists: " + name);
        }

        SourceConnector connector = factory.createConnector(configuration);
        connector.setEventListener(this);

        connectors.put(name, connector);
        log.info("Added source connector: {}", name);

        // Auto-start if configured
        if (configuration.isAutoStart() && running) {
            startConnector(name);
        }

        return connector;
    }

    /**
     * Remove a source connector
     *
     * @param name the connector name
     * @return the removed connector, or null if not found
     */
    public SourceConnector removeConnector(String name) {
        SourceConnector connector = connectors.remove(name);
        if (connector != null) {
            try {
                connector.stop().get(10, TimeUnit.SECONDS);
                log.info("Removed source connector: {}", name);
            } catch (Exception e) {
                log.error("Error stopping connector during removal: {}", name, e);
            }
        }
        return connector;
    }

    /**
     * Start a specific connector
     *
     * @param name the connector name
     */
    public void startConnector(String name) {
        SourceConnector connector = connectors.get(name);
        if (connector != null) {
            connector.start().thenRun(() -> {
                // Start polling for this connector
                startPolling(connector);
                log.info("Started source connector: {}", name);
            }).exceptionally(throwable -> {
                log.error("Failed to start source connector: {}", name, throwable);
                return null;
            });
        } else {
            throw new IllegalArgumentException("Connector not found: " + name);
        }
    }

    /**
     * Stop a specific connector
     *
     * @param name the connector name
     */
    public void stopConnector(String name) {
        SourceConnector connector = connectors.get(name);
        if (connector != null) {
            connector.stop().thenRun(() -> {
                log.info("Stopped source connector: {}", name);
            }).exceptionally(throwable -> {
                log.error("Failed to stop source connector: {}", name, throwable);
                return null;
            });
        } else {
            throw new IllegalArgumentException("Connector not found: " + name);
        }
    }

    /**
     * Start the source manager and all connectors
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;

        // Start all auto-start connectors
        connectors.values().forEach(connector -> {
            if (connector.getConfiguration().isAutoStart()) {
                startConnector(connector.getName());
            }
        });

        log.info("Source manager started with {} connectors", connectors.size());
    }

    /**
     * Stop the source manager and all connectors
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        // Stop all connectors
        connectors.values().parallelStream().forEach(connector -> {
            try {
                connector.stop().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error stopping connector: {}", connector.getName(), e);
            }
        });

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close message producer
        messageProducer.close();

        log.info("Source manager stopped");
    }

    /**
     * Get a connector by name
     *
     * @param name the connector name
     * @return the connector, or null if not found
     */
    public SourceConnector getConnector(String name) {
        return connectors.get(name);
    }

    /**
     * Get all connectors
     *
     * @return map of connector name to connector
     */
    public Map<String, SourceConnector> getAllConnectors() {
        return new ConcurrentHashMap<>(connectors);
    }

    /**
     * Get connector factory
     *
     * @return the connector factory
     */
    public SourceConnectorFactory getFactory() {
        return factory;
    }

    private void startPolling(SourceConnector connector) {
        long pollingInterval = connector.getConfiguration().getPollingIntervalMs();

        if (pollingInterval > 0) {
            scheduler.scheduleWithFixedDelay(() -> {
                if (connector.isRunning()) {
                    try {
                        List<SourceRecord> records = connector.poll();
                        if (records != null && !records.isEmpty()) {
                            // Send records to message queue
                            for (SourceRecord record : records) {
                                messageProducer.send(record.getTopic(), record.getKey(), record.getValue())
                                        .exceptionally(throwable -> {
                                            log.error("Failed to send record from connector: {}",
                                                    connector.getName(), throwable);
                                            return null;
                                        });
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error polling connector: {}", connector.getName(), e);
                    }
                }
            }, pollingInterval, pollingInterval, TimeUnit.MILLISECONDS);
        }
    }

    // SourceEventListener implementation

    @Override
    public void onConnectorStarted(String connectorName) {
        log.info("Connector started event: {}", connectorName);
    }

    @Override
    public void onConnectorStopped(String connectorName) {
        log.info("Connector stopped event: {}", connectorName);
    }

    @Override
    public void onRecordsPolled(String connectorName, int recordCount) {
        log.debug("Connector {} polled {} records", connectorName, recordCount);
    }

    @Override
    public void onConnectorError(String connectorName, Throwable error) {
        log.error("Connector error: {}", connectorName, error);
    }

    @Override
    public void onHealthStatusChanged(String connectorName, SourceHealthStatus oldStatus, SourceHealthStatus newStatus) {
        log.info("Connector {} health changed from {} to {}",
                connectorName, oldStatus.getStatus(), newStatus.getStatus());
    }
}