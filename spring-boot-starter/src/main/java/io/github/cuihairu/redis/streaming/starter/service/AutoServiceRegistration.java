package io.github.cuihairu.redis.streaming.starter.service;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import io.github.cuihairu.redis.streaming.core.utils.InstanceIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Automatic service registration component
 * Automatically registers a service instance on application startup and maintains heartbeat
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "redis-streaming.registry", name = "auto-register", havingValue = "true", matchIfMissing = true)
public class AutoServiceRegistration implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired(required = false)
    private NamingService namingService;

    @Autowired
    private RedisStreamingProperties properties;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    private ServiceInstance currentInstance;
    private ScheduledExecutorService heartbeatExecutor;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (namingService == null) {
            log.debug("NamingService not available, skipping auto registration");
            return;
        }

        if (!properties.getRegistry().isEnabled()) {
            log.debug("Service registry disabled, skipping auto registration");
            return;
        }

        try {
            registerService();
            startHeartbeat();
            log.info("Service auto-registration completed successfully");
        } catch (Exception e) {
            log.error("Failed to auto-register service", e);
        }
    }

    private void registerService() throws Exception {
        RedisStreamingProperties.InstanceProperties instance = properties.getRegistry().getInstance();

        // Build service instance
        String serviceName = resolveServiceName(instance.getServiceName());
        String host = resolveHost(instance.getHost());
        int port = resolvePort(instance.getPort());
        String instanceId = resolveInstanceId(instance.getInstanceId(), serviceName, port);
        Protocol protocol = resolveProtocol(instance.getProtocol());
        boolean ephemeral = resolveEphemeral(instance.getEphemeral());

        // Build metadata
        Map<String, String> metadata = new HashMap<>(instance.getMetadata());
        metadata.put("application.name", applicationName);
        metadata.put("server.port", String.valueOf(serverPort));
        metadata.put("startup.time", String.valueOf(System.currentTimeMillis()));

        // Create service instance
        currentInstance = DefaultServiceInstance.builder()
                .serviceName(serviceName)
                .instanceId(instanceId)
                .host(host)
                .port(port)
                .protocol(protocol)
                .weight(instance.getWeight())
                .enabled(instance.isEnabled())
                .ephemeral(ephemeral)
                .metadata(metadata)
                .build();

        // Register service
        namingService.register(currentInstance);
        log.info("Registered {} service instance: {}:{} at {}:{}",
                ephemeral ? "ephemeral" : "persistent", serviceName, instanceId, host, port);
    }

    private void startHeartbeat() {
        if (currentInstance == null) {
            return;
        }

        // Only ephemeral instances require heartbeat
        if (!currentInstance.isEphemeral()) {
            log.info("Persistent instance detected, skipping heartbeat scheduler");
            return;
        }

        int heartbeatInterval = properties.getRegistry().getHeartbeatInterval();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-streaming-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                namingService.sendHeartbeat(currentInstance);
                log.debug("Sent heartbeat for service instance: {}", currentInstance.getInstanceId());
            } catch (Exception e) {
                log.warn("Failed to send heartbeat", e);
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.SECONDS);

        log.info("Started heartbeat scheduler with interval: {}s", heartbeatInterval);
    }

    @PreDestroy
    public void destroy() {
        try {
            // Stop heartbeat
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdown();
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            }

            // Deregister service
            if (namingService != null && currentInstance != null) {
                namingService.deregister(currentInstance);
                log.info("Deregistered service instance: {}", currentInstance.getInstanceId());
            }
        } catch (Exception e) {
            log.error("Error during service deregistration", e);
        }
    }

    private String resolveServiceName(String configuredName) {
        if (configuredName != null && !configuredName.contains("${")) {
            return configuredName;
        }
        return applicationName;
    }

    private String resolveInstanceId(String configuredId, String serviceName, int port) {
        if (configuredId != null && !configuredId.trim().isEmpty()) {
            return configuredId;
        }
        // Generate instance ID using service name and server port
        return InstanceIdGenerator.generateInstanceId(serviceName, port);
    }

    private String resolveHost(String configuredHost) throws Exception {
        if (configuredHost != null && !configuredHost.trim().isEmpty()) {
            return configuredHost;
        }
        return InetAddress.getLocalHost().getHostAddress();
    }

    private int resolvePort(Integer configuredPort) {
        if (configuredPort != null && configuredPort > 0) {
            return configuredPort;
        }
        return serverPort;
    }

    private boolean resolveEphemeral(Boolean configuredEphemeral) {
        // Default to ephemeral instance (consistent with Nacos behavior)
        return configuredEphemeral != null ? configuredEphemeral : true;
    }

    private Protocol resolveProtocol(String protocolName) {
        if (protocolName == null) {
            return StandardProtocol.HTTP;
        }

        switch (protocolName.toLowerCase()) {
            case "http":
                return StandardProtocol.HTTP;
            case "https":
                return StandardProtocol.HTTPS;
            case "tcp":
                return StandardProtocol.TCP;
            default:
                log.warn("Unknown protocol: {}, using HTTP as default", protocolName);
                return StandardProtocol.HTTP;
        }
    }
}
