package io.github.cuihairu.redis.streaming.examples.streaming;

import io.github.cuihairu.redis.streaming.mq.*;
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive streaming processing example
 * Demonstrates integration of multiple streaming components:
 * - Service Registry for service discovery
 * - Message Queue for event streaming
 * - Real-time event processing
 */
@Slf4j
public class ComprehensiveStreamingExample {

    private final ObjectMapper objectMapper;
    private RedissonClient redissonClient;
    private NamingService namingService;
    private ServiceDiscovery discovery;
    private MessageQueueFactory mqFactory;
    private final List<MessageConsumer> consumers = new ArrayList<>();
    private final List<MessageProducer> producers = new ArrayList<>();

    public ComprehensiveStreamingExample() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static void main(String[] args) throws Exception {
        ComprehensiveStreamingExample example = new ComprehensiveStreamingExample();
        example.runExample();
    }

    public void runExample() throws Exception {
        log.info("=== Starting Comprehensive Streaming Example ===");

        try {
            // 1. Setup Redis infrastructure
            setupRedis();

            // 2. Setup naming service
            setupNamingService();

            // 3. Demonstrate the complete pipeline
            demonstrateStreamingPipeline();

            log.info("=== Comprehensive Streaming Example Completed ===");

        } finally {
            cleanup();
        }
    }

    private void setupRedis() {
        log.info("Setting up Redis infrastructure");

        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10);

        redissonClient = Redisson.create(config);
        mqFactory = new MessageQueueFactory(redissonClient);
    }

    private void setupNamingService() {
        log.info("Setting up naming service and discovery");

        namingService = new RedisNamingService(redissonClient);
        discovery = new RedisNamingService(redissonClient);

        namingService.start();
        discovery.start();

        // Register our streaming services
        registerStreamingServices();
    }

    private void registerStreamingServices() {
        log.info("Registering streaming services");

        // Register event producer service
        ServiceInstance producerService = DefaultServiceInstance.builder()
                .serviceName("event-producer")
                .instanceId("producer-1")
                .host("localhost")
                .port(8080)
                .protocol(StandardProtocol.HTTP)
                .metadata(Map.of(
                        "role", "producer",
                        "version", "1.0",
                        "throughput", "1000/sec"
                ))
                .build();

        namingService.register(producerService);

        // Register event processor service
        ServiceInstance processorService = DefaultServiceInstance.builder()
                .serviceName("event-processor")
                .instanceId("processor-1")
                .host("localhost")
                .port(8081)
                .protocol(StandardProtocol.HTTP)
                .metadata(Map.of(
                        "role", "processor",
                        "version", "1.0",
                        "batch-size", "100"
                ))
                .build();

        namingService.register(processorService);

        log.info("Services registered successfully");
    }

    private void demonstrateStreamingPipeline() throws Exception {
        log.info("=== Demonstrating Streaming Pipeline ===");

        // Create topics
        String rawEventsTopic = "raw-events";
        String processedEventsTopic = "processed-events";

        // Setup producers and consumers
        MessageProducer rawProducer = mqFactory.createProducer();
        MessageConsumer rawConsumer = mqFactory.createConsumer("processor-consumer");
        MessageProducer processedProducer = mqFactory.createProducer();
        MessageConsumer processedConsumer = mqFactory.createConsumer("sink-consumer");

        consumers.add(rawConsumer);
        consumers.add(processedConsumer);
        producers.add(rawProducer);
        producers.add(processedProducer);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger sinkCount = new AtomicInteger(0);
        CountDownLatch processingLatch = new CountDownLatch(10);

        // Stage 1: Raw event processing
        rawConsumer.subscribe(rawEventsTopic, "processor-group", message -> {
            try {
                RawEvent raw = objectMapper.readValue((String) message.getPayload(), RawEvent.class);
                log.info("[PROCESSOR] Processing raw event: {}", raw.getEventId());

                // Transform and enrich
                ProcessedEvent processed = new ProcessedEvent(
                        raw.getEventId(),
                        raw.getData(),
                        "processed",
                        Instant.now(),
                        Map.of("processor", "v1", "enriched", "true")
                );

                // Send to next stage
                String payload = objectMapper.writeValueAsString(processed);
                processedProducer.send(processedEventsTopic, payload);

                processedCount.incrementAndGet();
                return MessageHandleResult.SUCCESS;

            } catch (Exception e) {
                log.error("Error processing raw event", e);
                return MessageHandleResult.RETRY;
            }
        });

        // Stage 2: Processed event sink
        processedConsumer.subscribe(processedEventsTopic, "sink-group", message -> {
            try {
                ProcessedEvent processed = objectMapper.readValue((String) message.getPayload(), ProcessedEvent.class);
                log.info("[SINK] Storing processed event: {} (status: {})",
                        processed.getEventId(), processed.getStatus());

                sinkCount.incrementAndGet();
                processingLatch.countDown();
                return MessageHandleResult.SUCCESS;

            } catch (Exception e) {
                log.error("Error sinking processed event", e);
                return MessageHandleResult.FAIL;
            }
        });

        // Start consumers
        rawConsumer.start();
        processedConsumer.start();

        // Wait a bit for consumers to be ready
        Thread.sleep(500);

        // Produce raw events
        log.info("Producing raw events...");
        for (int i = 1; i <= 10; i++) {
            RawEvent event = new RawEvent(
                    "event-" + i,
                    "data-" + i,
                    "sensor-" + (i % 3),
                    Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            rawProducer.send(rawEventsTopic, payload);

            if (i % 3 == 0) {
                log.info("Produced {} raw events", i);
            }

            Thread.sleep(100);
        }

        // Wait for processing to complete
        boolean completed = processingLatch.await(15, TimeUnit.SECONDS);

        log.info("Pipeline execution completed: {}", completed);
        log.info("Events processed: {}", processedCount.get());
        log.info("Events sinked: {}", sinkCount.get());

        // Show service discovery in action
        demonstrateServiceDiscovery();
    }

    private void demonstrateServiceDiscovery() {
        log.info("=== Service Discovery ===");

        List<ServiceInstance> producers = discovery.discover("event-producer");
        log.info("Found {} producer services", producers.size());

        List<ServiceInstance> processors = discovery.discover("event-processor");
        log.info("Found {} processor services", processors.size());

        for (ServiceInstance instance : processors) {
            log.info("Processor: {} at {}:{} - metadata: {}",
                    instance.getInstanceId(),
                    instance.getHost(),
                    instance.getPort(),
                    instance.getMetadata());
        }
    }

    private void cleanup() {
        log.info("Cleaning up resources...");

        // Stop consumers
        for (MessageConsumer consumer : consumers) {
            try {
                consumer.stop();
                consumer.close();
            } catch (Exception e) {
                log.warn("Error stopping consumer", e);
            }
        }

        // Close producers
        for (MessageProducer producer : producers) {
            try {
                producer.close();
            } catch (Exception e) {
                log.warn("Error closing producer", e);
            }
        }

        // Stop naming service and discovery
        if (namingService != null) {
            try {
                namingService.stop();
            } catch (Exception e) {
                log.warn("Error stopping naming service", e);
            }
        }

        if (discovery != null) {
            try {
                discovery.stop();
            } catch (Exception e) {
                log.warn("Error stopping discovery", e);
            }
        }

        // Shutdown Redis
        if (redissonClient != null) {
            try {
                Thread.sleep(200);
                redissonClient.shutdown();
                log.info("Redis client shutdown completed");
            } catch (Exception e) {
                log.warn("Error shutting down Redis", e);
            }
        }

        log.info("Cleanup completed");
    }

    // Event classes
    @Data
    public static class RawEvent {
        private String eventId;
        private String data;
        private String source;
        private Instant timestamp;

        public RawEvent() {}

        public RawEvent(String eventId, String data, String source, Instant timestamp) {
            this.eventId = eventId;
            this.data = data;
            this.source = source;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class ProcessedEvent {
        private String eventId;
        private String data;
        private String status;
        private Instant processedAt;
        private Map<String, String> metadata;

        public ProcessedEvent() {}

        public ProcessedEvent(String eventId, String data, String status,
                            Instant processedAt, Map<String, String> metadata) {
            this.eventId = eventId;
            this.data = data;
            this.status = status;
            this.processedAt = processedAt;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
    }
}
