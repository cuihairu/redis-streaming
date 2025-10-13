package io.github.cuihairu.redis.streaming.examples.registry;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.impl.*;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Service Registry demonstration example
 * Shows microservices discovering and communicating with each other
 */
@Slf4j
public class ServiceRegistryExample {

    public static void main(String[] args) throws Exception {
        ServiceRegistryExample example = new ServiceRegistryExample();
        example.runExample();
    }

    private RedissonClient redissonClient;
    private NamingService namingService;

    public void runExample() throws Exception {
        log.info("Starting Service Registry Example");

        // 1. Setup Redis-based service registry and discovery
        setupRedisClients();

        // 2. Create and register multiple microservices
        List<MicroService> services = createMicroServices(namingService);

        // 3. Start all services
        startServices(services);

        // 4. Demonstrate service discovery and communication
        demonstrateServiceDiscovery((ServiceDiscovery) namingService);

        // 5. Simulate service failures and recovery
        simulateServiceFailures(services, (ServiceDiscovery) namingService);

        // 6. Demonstrate load balancing
        demonstrateLoadBalancing((ServiceDiscovery) namingService);

        // Clean up
        stopServices(services);
        cleanup();
        log.info("Service Registry Example completed");
    }

    private void setupRedisClients() {
        log.info("Setting up Redis-based service registry and discovery");

        // Create Redis client
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        redissonClient = Redisson.create(config);

        // Create naming service instance
        namingService = new RedisNamingService(redissonClient);

        // Start service
        namingService.start();

        // Subscribe to service changes
        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {
            String instanceId = instance != null ? instance.getInstanceId() : "unknown";
            log.info("🔔 Service change: {} - {} (instance: {}, total: {})",
                serviceName, action, instanceId, allInstances.size());
        };

        // Subscribe to all services we're interested in
        namingService.subscribe("api-gateway", listener);
        namingService.subscribe("user-service", listener);
        namingService.subscribe("order-service", listener);
        namingService.subscribe("payment-service", listener);
        namingService.subscribe("notification-service", listener);
    }

    private void cleanup() {
        log.info("Cleaning up resources...");

        // First unsubscribe from all services to avoid race conditions
        if (namingService != null && namingService.isRunning()) {
            try {
                // Give time for pending messages to be processed
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Stop naming service
        if (namingService != null) {
            try {
                namingService.stop();
            } catch (Exception e) {
                log.warn("Error stopping naming service", e);
            }
        }

        if (redissonClient != null) {
            try {
                // Shutdown Redisson gracefully
                redissonClient.shutdown();
                log.info("Redisson client shutdown completed");
            } catch (Exception e) {
                log.warn("Error shutting down Redisson client", e);
            }
        }

        // Give time for all threads to cleanup
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Cleanup completed");
    }

    private List<MicroService> createMicroServices(NamingService namingService) {
        log.info("Creating microservices");

        // API Gateway
        MicroService apiGateway = new MicroService(
                "api-gateway",
                "localhost",
                8080,
                StandardProtocol.HTTP,
                Map.of(
                    "version", "1.0",
                    "environment", "demo",
                    "role", "gateway"
                ),
                namingService
        );

        // User Service
        MicroService userService = new MicroService(
                "user-service",
                "localhost",
                8081,
                StandardProtocol.HTTP,
                Map.of(
                    "version", "2.1",
                    "environment", "demo",
                    "database", "postgresql"
                ),
                namingService
        );

        // Order Service
        MicroService orderService = new MicroService(
                "order-service",
                "localhost",
                8082,
                StandardProtocol.HTTP,
                Map.of(
                    "version", "1.5",
                    "environment", "demo",
                    "database", "mysql"
                ),
                namingService
        );

        // Payment Service
        MicroService paymentService = new MicroService(
                "payment-service",
                "localhost",
                8083,
                StandardProtocol.HTTPS,
                Map.of(
                    "version", "3.0",
                    "environment", "demo",
                    "security", "high"
                ),
                namingService
        );

        // Notification Service
        MicroService notificationService = new MicroService(
                "notification-service",
                "localhost",
                8084,
                StandardProtocol.HTTP,
                Map.of(
                    "version", "1.0",
                    "environment", "demo",
                    "channels", "email,sms,push"
                ),
                namingService
        );

        return List.of(apiGateway, userService, orderService, paymentService, notificationService);
    }

    private void startServices(List<MicroService> services) throws Exception {
        log.info("Starting all microservices");

        CountDownLatch startupLatch = new CountDownLatch(services.size());

        // Start all services concurrently
        services.forEach(service -> {
            CompletableFuture.runAsync(() -> {
                try {
                    service.start();
                    startupLatch.countDown();
                } catch (Exception e) {
                    log.error("Failed to start service: {}", service.getServiceName(), e);
                }
            });
        });

        boolean allStarted = startupLatch.await(30, TimeUnit.SECONDS);
        if (!allStarted) {
            throw new RuntimeException("Not all services started within timeout");
        }

        log.info("All microservices started successfully");
        Thread.sleep(2000); // Allow registration to complete
    }

    private void demonstrateServiceDiscovery(ServiceDiscovery discovery) throws Exception {
        log.info("=== Demonstrating Service Discovery ===");

        // Discover all services
        List<ServiceInstance> userServices = discovery.discover("user-service");
        log.info("Discovered {} user service instances:", userServices.size());
        userServices.forEach(service ->
            log.info("  - {} at {}:{} (enabled: {})",
                service.getInstanceId(),
                service.getHost(),
                service.getPort(),
                service.isEnabled())
        );

        List<ServiceInstance> paymentServices = discovery.discover("payment-service");
        log.info("Payment service instances: {}", paymentServices.size());

        Thread.sleep(1000);
    }

    private void simulateServiceFailures(List<MicroService> services, ServiceDiscovery discovery) throws Exception {
        log.info("=== Simulating Service Failures and Recovery ===");

        // Simulate payment service failure
        MicroService paymentService = services.stream()
                .filter(s -> "payment-service".equals(s.getServiceName()))
                .findFirst()
                .orElseThrow();

        log.info("Simulating payment service failure...");
        paymentService.simulateFailure();

        // Wait for health check to detect failure
        Thread.sleep(3000);

        // Check service status
        List<ServiceInstance> healthyPaymentServices = discovery.discoverHealthy("payment-service");
        log.info("Healthy payment services after failure: {}", healthyPaymentServices.size());

        // Simulate recovery
        log.info("Simulating payment service recovery...");
        paymentService.simulateRecovery();

        // Wait for health check to detect recovery
        Thread.sleep(3000);

        healthyPaymentServices = discovery.discoverHealthy("payment-service");
        log.info("Healthy payment services after recovery: {}", healthyPaymentServices.size());
    }

    private void demonstrateLoadBalancing(ServiceDiscovery discovery) throws Exception {
        log.info("=== Demonstrating Load Balancing ===");

        // Register multiple instances of the same service
        MicroService userService2 = new MicroService(
                "user-service",
                "localhost",
                8085,
                StandardProtocol.HTTP,
                Map.of("version", "2.1", "environment", "demo", "instance", "2"),
                namingService
        );

        MicroService userService3 = new MicroService(
                "user-service",
                "localhost",
                8086,
                StandardProtocol.HTTP,
                Map.of("version", "2.1", "environment", "demo", "instance", "3"),
                namingService
        );

        userService2.start();
        userService3.start();
        Thread.sleep(2000);

        // Demonstrate load balancing selection
        log.info("Demonstrating load balancing across user service instances:");
        for (int i = 0; i < 10; i++) {
            List<ServiceInstance> instances = discovery.discoverHealthy("user-service");
            if (!instances.isEmpty()) {
                // Simple round-robin selection
                ServiceInstance selected = instances.get(i % instances.size());
                log.info("Request {}: Selected instance at {}:{} (instance: {})",
                    i + 1,
                    selected.getHost(),
                    selected.getPort(),
                    selected.getMetadata().getOrDefault("instance", "1"));
            }
            Thread.sleep(500);
        }

        // Stop additional instances
        userService2.stop();
        userService3.stop();
    }

    private void stopServices(List<MicroService> services) {
        log.info("Stopping all microservices");

        services.parallelStream().forEach(service -> {
            try {
                service.stop();
            } catch (Exception e) {
                log.error("Failed to stop service: {}", service.getServiceName(), e);
            }
        });

        log.info("All microservices stopped");
    }

    /**
     * Simplified microservice implementation for demonstration
     */
    private static class MicroService {
        private final String serviceName;
        private final String host;
        private final int port;
        private final Protocol protocol;
        private final Map<String, String> metadata;
        private final NamingService namingService;
        private ServiceInstance serviceInstance;
        private boolean healthy = true;

        public MicroService(String serviceName, String host, int port, Protocol protocol,
                          Map<String, String> metadata, NamingService namingService) {
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.metadata = metadata;
            this.namingService = namingService;
        }

        public void start() throws Exception {
            // Generate a unique instance ID
            String instanceId = serviceName + "-" + host + "-" + port;

            // Create service instance using builder
            serviceInstance = DefaultServiceInstance.builder()
                .serviceName(serviceName)
                .instanceId(instanceId)
                .host(host)
                .port(port)
                    .protocol(protocol)
                    .metadata(metadata)
                    .healthy(true)
                    .enabled(true)
                    .weight(1)
                    .build();

            // Register with service registry
            namingService.register(serviceInstance);

            log.info("Started microservice: {} at {}:{}", serviceName, host, port);

            // Start health monitoring
            startHealthMonitoring();
        }

        public void stop() throws Exception {
            if (serviceInstance != null) {
                namingService.deregister(serviceInstance);
                log.info("Stopped microservice: {}", serviceName);
                serviceInstance = null;
            }
        }

        public void simulateFailure() {
            healthy = false;
            if (serviceInstance != null) {
                // Update instance to unhealthy
                serviceInstance = DefaultServiceInstance.builder()
                        .serviceName(serviceName)
                        .instanceId(serviceInstance.getInstanceId())
                        .host(host)
                        .port(port)
                        .protocol(protocol)
                        .metadata(metadata)
                        .healthy(false)
                        .enabled(true)
                        .weight(1)
                        .build();

                try {
                    namingService.register(serviceInstance); // Re-register with unhealthy status
                } catch (Exception e) {
                    log.error("Failed to update health status", e);
                }
            }
            log.warn("Simulated failure for service: {}", serviceName);
        }

        public void simulateRecovery() {
            healthy = true;
            if (serviceInstance != null) {
                // Update instance to healthy
                serviceInstance = DefaultServiceInstance.builder()
                        .serviceName(serviceName)
                        .instanceId(serviceInstance.getInstanceId())
                        .host(host)
                        .port(port)
                        .protocol(protocol)
                        .metadata(metadata)
                        .healthy(true)
                        .enabled(true)
                        .weight(1)
                        .build();

                try {
                    namingService.register(serviceInstance); // Re-register with healthy status
                } catch (Exception e) {
                    log.error("Failed to update health status", e);
                }
            }
            log.info("Simulated recovery for service: {}", serviceName);
        }

        public String getServiceName() {
            return serviceName;
        }

        private void startHealthMonitoring() {
            Thread healthThread = new Thread(() -> {
                while (serviceInstance != null) {
                    try {
                        // Send heartbeat
                        namingService.sendHeartbeat(serviceInstance);
                        Thread.sleep(5000); // Heartbeat every 5 seconds
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Health monitoring error for service: {}", serviceName, e);
                    }
                }
            });

            healthThread.setDaemon(true);
            healthThread.setName("health-" + serviceName);
            healthThread.start();
        }
    }
}