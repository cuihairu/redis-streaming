<div align="center">

<img src="docs/public/logo.svg" width="64" alt="Redis-Streaming logo" />

# Redis-Streaming - A Lightweight Streaming Framework Built on Redis

[English](README.md) | [中文](README.zh.md)

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Redis](https://img.shields.io/badge/Redis-6.0+-red.svg)](https://redis.io/)
[![Version](https://img.shields.io/badge/Version-0.2.4-blue.svg)](https://github.com/cuihairu/redis-streaming)
[![codecov](https://codecov.io/gh/cuihairu/redis-streaming/branch/main/graph/badge.svg)](https://codecov.io/gh/cuihairu/redis-streaming)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

A **lightweight stream processing runtime** built on Redis and Redisson (Apache License 2.0): Redis Streams serves as both the message pipeline and the foundation for state and checkpoints — from consumption, event time, and windowed aggregation to checkpoint / failure recovery / HA takeover, all managed by a single runtime. Redis infrastructure capabilities such as MQ, Registry, and Config are provided as companion modules.

## Understand It in 60 Seconds

```java
RedissonClient redis = Redisson.create(cfg);            // a plain Redisson client
RedisRuntimeConfig rc = RedisRuntimeConfig.builder()
        .jobName("order-analytics")
        .build();
RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, rc);

env.fromMqTopic("orders", "analytics")                  // topic + consumer group
   .map(m -> (Integer) m.getPayload())
   .keyBy(v -> v % 4)                                   // keyed partitioning
   .window(TumblingWindow.<Integer>ofMillis(60_000))    // 1-minute tumbling window
   .reduce(Integer::sum)                                // aggregate within the window
   .addSink(sum -> System.out.println("window sum = " + sum));

RedisJobClient job = env.executeAsync();                 // consumption/watermarks/windows/checkpoints fully managed
job.triggerCheckpointNow();                              // optional: trigger a checkpoint manually
```

### Processing Guarantees

| Guarantee | Path | Boundary |
|---|---|---|
| At-least-once | Default MQ consumption: ACK after processing, retries/DLQ on failure | Possible duplicate delivery |
| Effectively-once (Redis sinks) | checkpoint + defer-ack + idempotent/atomic sinks (`RedisAtomicCheckpointListSink`, sink dedup, 2PC) | End-to-end dedup, closed loop within Redis |
| Effectively-once* (cross-system) | Outbox-WAL: `RedisOutboxSink` + async Dispatcher | At-least-once dispatch + per-record id dedup on the target; * depends on sink capability |

See `docs/exactly-once.md` for design details and boundaries.

## Key Features

Documentation site (GitHub Pages): https://cuihairu.github.io/redis-streaming/

### Implemented Features
- Stream Processing Runtime: Redis-backed runtime (Redis Streams consumer-group driven, single-process parallelism / watermarks / windows / checkpoints / HA takeover) + in-memory runtime (tests/examples)
- Message Queue (MQ): Redis Streams-based message queue with consumer groups and dead-letter queues
- Service Registry & Discovery: service registration and discovery with multi-protocol health checks (HTTP/HTTPS/TCP/UDP/WebSocket/KCP/gRPC/Dubbo) and metadata comparison-operator filtering
- Config Center: Redis-based distributed configuration management with versioning, change notifications, and history
- State Management: Redis-based distributed state store with ValueState, MapState, ListState, SetState
- Checkpointing: distributed checkpoint coordination with failure recovery; two-phase commit sinks and Outbox-WAL cross-system delivery
- Watermark: WatermarkStrategy + generators (ordered / out-of-order), usable with the runtime (event time)
- Window Assigners: tumbling / sliding / session windows + triggers (the Redis runtime supports watermark-based window computation; richer trigger semantics are extensible)
- Window Aggregation: real-time aggregation over time windows with PV/UV, TopK, and quantile computation
- Streaming Join (Join): stream-stream joins within time windows
- CDC Integration: MySQL Binlog, PostgreSQL logical replication, database polling
- Reliability Guarantees: retries, dead-letter queues, Bloom Filter dedup, windowed dedup
- Sink Connectors: Kafka Sink, Redis Stream Sink (XADD), Redis List/Hash Sinks
- Source Connectors: Kafka Source, HTTP API Source, Redis List/Stream Sources
- Prometheus Monitoring: Prometheus Exporter and metric collectors
- Spring Boot Integration: auto-configuration and annotation support
- Stream-Table Duality (Table): both in-memory and Redis-persisted KTable implemented
- CEP: complex event processing with Kleene closure and advanced pattern operations

## Module Architecture

### Tier 1: Core Abstractions

#### core - Core Abstractions and API Definitions
The core APIs and foundational abstractions of stream processing, defining the interfaces for all streaming operations.

Complete, with full API definitions.

Responsibilities:
- Stream processing API (DataStream, KeyedStream, WindowedStream)
- State management abstractions (State, ValueState, MapState, ListState, SetState)
- Checkpoint abstractions (Checkpoint, CheckpointCoordinator)
- Watermark abstractions (Watermark, WatermarkGenerator)
- Window abstractions (WindowAssigner, WindowAssigner.Trigger)
- Connector abstractions (StreamSource, StreamSink)
- Utilities (InstanceIdGenerator, SystemUtils)

Key classes: `DataStream.java`, `KeyedStream.java`, `State.java` (27 files)

#### runtime - Stream Processing Runtime Engine
The execution engine of the stream processing runtime.

Implemented: Redis runtime (single-process parallelism + checkpoints/windows/watermarks) and a minimal in-memory runtime.

Note: `runtime` provides both:
- Redis runtime: `RedisStreamExecutionEnvironment` (Redis Streams consumer-group driven, Redis keyed state, stop-the-world checkpoints (experimental), watermark/window/timers)
- In-memory runtime: `StreamExecutionEnvironment` (mainly for tests/examples)
See `docs/` (VitePress) and `runtime/README.md` for details.

Event-time Watermark example (extracting event time from elements):
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;
import java.time.Duration;

record Event(long ts, String value) {}

var env = StreamExecutionEnvironment.getExecutionEnvironment();
var strategy = WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
    .withTimestampAssigner((e, recordTs) -> e.ts());

env.fromElements(new Event(10, "a"), new Event(20, "b"))
    .assignTimestampsAndWatermarks(strategy.getTimestampAssigner(), strategy.createWatermarkGenerator())
    .keyBy(e -> "k")
    .process(/* ... */);
```

### Tier 2: Infrastructure

#### mq - Message Queue
A message queue implementation based on Redis Streams.

Complete.

Responsibilities:
- Message production and consumption (async supported)
- Consumer group management
- Dead-letter queue (DLQ)
- Message retry mechanism
- Serves as the data pipeline for stream processing

Retention and ACK deletion policy (overview)
- Memory is controlled by "retain + trim" by default:
  - Trim-on-write: `XTRIM MAXLEN ~` after each write (low overhead)
  - Background trim: `XTRIM MAXLEN ~` every `trimIntervalSec`, optionally `XTRIM MINID ~`; with multiple groups, safe trimming is based on the "minimum committed frontier"
- Optional ACK deletion policies:
  - `none` (default): ACK only, no immediate deletion; relies on the retention policy
  - `immediate`: usable in single-group scenarios, `XDEL` right after ACK
  - `all-groups-ack`: per-record counting across groups; delete once all active groups have ACKed
- DLQ can have its own retention thresholds (length/time)

See `docs/retention-and-ack-policy.md` for details.

#### registry - Service Registry & Discovery
Redis-based service registry and discovery supporting microservice architectures and health checks.

Complete.

Responsibilities:
- Service registration and deregistration (heartbeat mechanism, optimized Lua scripts)
- Service discovery and subscription (real-time notifications via Redis Pub/Sub)
- Multi-protocol health checks (`StandardProtocol` enum: HTTP/HTTPS/TCP/UDP/WS/WSS/KCP/GRPC/GRPCS/DUBBO/DUBBO2, custom `Protocol` supported)
- Metadata-filtered queries (comparison operators: `>`, `>=`, `<`, `<=`, `!=`, `==`)
- Load balancing (based on metadata such as weight, CPU, latency)
- Ephemeral / permanent instance management

Key classes: `RedisNamingService.java`, `RedisServiceProvider.java`, `RedisServiceConsumer.java`, `RegistryLuaScriptExecutor.java` (68 files)

Metadata filtering example:
```java
// Weight-based load balancing
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");          // weight >= 80
filters.put("cpu_usage:<", "70");        // CPU < 70%
filters.put("region", "us-east-1");      // exact match
filters.put("status:!=", "maintenance"); // exclude maintenance status

List<ServiceInstance> instances =
    namingService.getInstancesByMetadata("order-service", filters);
```

#### config - Config Center
Redis-based distributed configuration management with versioning and change notifications.

Complete.

Responsibilities:
- Config publishing and retrieval (group management supported)
- Config versioning (history and rollback)
- Config change notifications (real-time push via Redis Pub/Sub)
- Config listeners (auto-update, hot reload)
- Config history queries (last N versions retained)

Key classes: `RedisConfigService.java`, `ConfigManager.java`, `ConfigChangeListener.java` (12 files)

Config management example:
```java
// Publish a configuration
configService.publishConfig("app.properties", "DEFAULT_GROUP",
    "key=value\ndb.url=jdbc:mysql://localhost:3306/db",
    "Updated database configuration");

// Listen for configuration changes
configService.addListener("app.properties", "DEFAULT_GROUP", (dataId, group, content, version) -> {
    System.out.println("Configuration changed (v" + version + "): " + content);
    // Reload configuration automatically
});

// Query history versions
List<ConfigHistory> history = configService.getConfigHistory("app.properties", "DEFAULT_GROUP", 10);
```

#### state - State Management
Redis-based distributed state store with multiple state types.

Complete.

Responsibilities:
- ValueState - single-value state (Redis String)
- MapState - key-value state (Redis Hash)
- ListState - list state (Redis List)
- SetState - set state (Redis Set)
- State persistence and restoration

Key classes: `RedisStateBackend.java`, `RedisValueState.java`, `RedisMapState.java` (6 files)

#### checkpoint - Checkpointing
Distributed checkpoint coordination and storage for fault tolerance.

Complete.

Responsibilities:
- Checkpoint coordination (distributed)
- State snapshots (async snapshotting)
- Failure recovery (restore from checkpoints)
- Checkpoint storage (Redis persistence)

Key classes: `RedisCheckpointCoordinator.java`, `RedisCheckpointStorage.java`, `DefaultCheckpoint.java` (4 files)

#### watermark - Watermarks
Event-time processing for out-of-order data.

Complete; usable with the runtime (event time).

Responsibilities:
- Watermark generation (ordered, out-of-order)
- Late data handling
- Timestamp assignment (`TimestampAssigner`)
- Multiple watermark strategies

Key classes: `WatermarkStrategy.java`, `AscendingTimestampWatermarkGenerator.java`, `BoundedOutOfOrdernessWatermarkGenerator.java`

### Tier 3: Feature Modules

#### window - Windowing
Various window types and triggers supporting time- and count-based windows.

Complete: window assigners + triggers (the in-memory runtime drives the default trigger per element via `WindowAssigner.getDefaultTrigger()`; the Redis runtime window operator is wired to the default trigger as well).

Responsibilities:
- Tumbling windows
- Sliding windows
- Session windows
- Triggers (EventTime / ProcessingTime / Count)

Key classes: `TumblingWindow.java`, `SlidingWindow.java`, `SessionWindow.java`, `EventTimeTrigger.java`, `ProcessingTimeTrigger.java`, `CountTrigger.java`

#### aggregation - Aggregation Functions
Aggregation functions and window aggregation support built on Redis.

Complete.

Responsibilities:
- Basic aggregations (Sum, Count, Avg, Min, Max)
- PV/UV counting (Redis HyperLogLog)
- TopK rankings (Redis Sorted Set)
- Quantile computation
- Window aggregation (tumbling, sliding)

Key classes: `WindowAggregator.java`, `PVCounter.java`, `TopKAnalyzer.java`, `SumFunction.java` (14 files)

#### table - Stream-Table Duality
KTable and KStream with stream-table conversion and table operations.

Complete: InMemoryKTable + RedisKTable.

Responsibilities:
- KTable - an updatable table
- KGroupedTable - a grouped table
- Stream-table conversion
- Table operations (map, filter, join)

Key classes: `KTable.java`, `InMemoryKTable.java`, `RedisKTable.java`, `StreamTableConverter.java`

#### join - Joins
Streaming joins within time windows with multiple join types.

Complete.

Responsibilities:
- Stream-Stream joins (time windows)
- Join types (INNER, LEFT, RIGHT, FULL_OUTER)
- State buffering (Redis-backed)
- Join window management

Key classes: `StreamJoiner.java`, `JoinConfig.java`, `JoinWindow.java` (6 files)

#### cdc - Change Data Capture
Change event capture from databases with multiple source types.

Complete.

Responsibilities:
- MySQL Binlog CDC (real-time capture)
- PostgreSQL logical replication
- Database polling CDC
- Change event routing and transformation
- Health monitoring and metrics

Key classes: `MySQLBinlogCDCConnector.java`, `PostgreSQLLogicalReplicationCDCConnector.java`, `CDCManager.java` (18 files)

#### sink - Output Connectors
Multiple sink connectors.

Complete; available connectors are listed below.

Responsibilities:
- PrintSink - console output
- FileSink - file output
- CollectionSink - collection output
- RedisStreamSink - Redis Stream output (XADD)
- RedisListSink - Redis List output
- RedisHashSink - Redis Hash output
- KafkaSink - Kafka output

Key classes: `KafkaSink.java`, `RedisStreamSink.java`, `RedisHashSink.java`, `PrintSink.java`

#### source - Input Connectors
Multiple source connectors.

Complete; available connectors are listed below.

Responsibilities:
- CollectionSource - collection data source
- FileSource - file data source
- GeneratorSource - test data generation
- RedisListSource - Redis List data source
- HttpApiSource - HTTP API polling data source
- KafkaSource - Kafka data source

Key classes: `KafkaSource.java`, `HttpApiSource.java`, `RedisListSource.java`, `CollectionSource.java`

### Tier 4: Advanced Features

#### reliability - Reliability Guarantees
Reliability mechanisms for stream processing: retries and failure handling.

Complete.

Responsibilities:
- Retry mechanism (exponential backoff, max retry count)
- Dead-letter queue management
- Failure policies (retry, skip, DLQ)
- Failed element tracking
- Deduplication (Bloom Filter / Set / Windowed)
- Rate limiting (sliding window, token bucket, leaky bucket; Redis/InMemory)

Key classes: `RetryExecutor.java`, `RedisDeadLetterService.java`, `BloomFilterDeduplicator.java`, `RedisSlidingWindowRateLimiter.java`

#### cep - Complex Event Processing
Pattern matching and complex event detection.

Complete, with Kleene closure / contiguity / advanced sequence matching.

Responsibilities:
- Pattern definition (Pattern Builder)
- Sequence detection (PatternSequence / PatternSequenceMatcher)
- Kleene closure (*, +, ?, {n}, {n,m})
- Contiguity constraints (STRICT / RELAXED / NON_DETERMINISTIC)
- Time window constraints (within)

Key classes: `PatternSequenceMatcher.java`, `PatternSequence.java`, `PatternQuantifier.java`, `PatternConfig.java`

### Tier 5: Integrations

#### metrics - Monitoring Metrics
Metric collection and exposure.

Complete: Prometheus Exporter + Collector.

Responsibilities:
- Metric collection (Counter, Gauge, Histogram, Timer)
- In-memory metric storage
- Metric registry management
- Timer support
- Prometheus export (HTTP)

Key classes: `PrometheusExporter.java`, `PrometheusMetricCollector.java`, `MetricRegistry.java`

#### spring-boot-starter - Spring Boot Integration
Spring Boot auto-configuration and integration.

Complete.

Responsibilities:
- Auto-configuration (Registry, Discovery, ConfigService)
- Configuration property binding
- Bean auto-wiring
- Annotation support (@EnableRedisStreaming, @ServiceChangeListener, @ConfigChangeListener)
- Automatic service registration

Key classes: `RedisStreamingAutoConfiguration.java`, `RedisStreamingProperties.java`, `@EnableRedisStreaming.java`

#### examples - Example Code
Usage examples and best practices.

Basic examples provided.

Responsibilities:
- Service registry & discovery examples
- Message queue examples
- Rate limiting examples
- Aggregation examples
- Comprehensive streaming example (in-memory runtime)

Key classes: `ServiceRegistryExample.java`, `CustomPrefixExample.java`, `MessageQueueExample.java`, `RateLimitExample.java`, `StreamAggregationExample.java`, `ComprehensiveStreamingExample.java`

## Quick Start

### 1. Requirements

- Java 17+
- Redis 6.0+
- Gradle 8.5+ (or just use the bundled wrapper `./gradlew`)

### 2. Add Dependencies

Pick the core modules you need:
```gradle
dependencies {
    // Message queue
    implementation 'io.github.cuihairu.redis-streaming:mq:0.2.0'

    // Service registry & discovery (metadata comparison-operator filtering supported)
    implementation 'io.github.cuihairu.redis-streaming:registry:0.2.0'

    // Config center (versioned config, change notifications)
    implementation 'io.github.cuihairu.redis-streaming:config:0.2.0'

    // State management
    implementation 'io.github.cuihairu.redis-streaming:state:0.2.0'

    // Checkpointing
    implementation 'io.github.cuihairu.redis-streaming:checkpoint:0.2.0'

    // Window aggregation
    implementation 'io.github.cuihairu.redis-streaming:aggregation:0.2.0'

    // CDC
    implementation 'io.github.cuihairu.redis-streaming:cdc:0.2.0'
}
```

Spring Boot integration:
```gradle
dependencies {
    implementation 'io.github.cuihairu.redis-streaming:spring-boot-starter:0.2.0'
    // Brings in core modules such as registry, config, and mq
}
```

### 3. Configure Redis

```java
Config config = new Config();
config.useSingleServer()
    .setAddress("redis://127.0.0.1:6379")
    .setConnectionPoolSize(20)
    .setConnectionMinimumIdleSize(5);

RedissonClient redissonClient = Redisson.create(config);
```

### 4. Quick Examples

#### Service Registry & Discovery (with metadata filtering)
```java
import io.github.cuihairu.redis.streaming.registry.*;

// Create the naming service
NamingService namingService = new RedisNamingService(redissonClient);
namingService.start();

// Register a service (with metadata)
Map<String, String> metadata = new HashMap<>();
metadata.put("version", "1.0.0");
metadata.put("weight", "100");
metadata.put("cpu_usage", "45");
metadata.put("region", "us-east-1");

ServiceInstance instance = DefaultServiceInstance.builder()
    .serviceName("order-service")
    .instanceId("order-service-001")
    .host("localhost")
    .port(8080)
    .protocol(StandardProtocol.HTTP)
    .metadata(metadata)
    .build();

namingService.register(instance);

// Basic service discovery
List<ServiceInstance> allInstances = namingService.getHealthyInstances("order-service");

// Advanced filtering: comparison operators
Map<String, String> filters = new HashMap<>();
filters.put("weight:>=", "80");           // weight >= 80
filters.put("cpu_usage:<", "70");         // CPU usage < 70%
filters.put("region", "us-east-1");       // exact region match
filters.put("status:!=", "maintenance");  // exclude maintenance status

List<ServiceInstance> filteredInstances =
    namingService.getInstancesByMetadata("order-service", filters);

// Subscribe to service changes
namingService.subscribe("order-service", (serviceName, action, instance, allInstances) -> {
    System.out.println("Service changed: " + action + " - " + instance.getInstanceId());
});
```

#### Config Center
```java
import io.github.cuihairu.redis.streaming.config.*;

// Create the config service
ConfigService configService = new RedisConfigService(redissonClient);
configService.start();

// Publish a configuration
configService.publishConfig(
    "database.config",              // config ID
    "DEFAULT_GROUP",                // config group
    "db.url=jdbc:mysql://localhost:3306/mydb\ndb.username=root",
    "Initial database configuration" // description
);

// Get a configuration
String dbConfig = configService.getConfig("database.config", "DEFAULT_GROUP");
System.out.println("Database config: " + dbConfig);

// Listen for config changes (hot reload)
configService.addListener("database.config", "DEFAULT_GROUP",
    (dataId, group, content, version) -> {
        System.out.println("Configuration updated (v" + version + "): " + content);
        // Reload database connection pools, etc.
        reloadDatabaseConnection(content);
    }
);

// Query history versions
List<ConfigHistory> history = configService.getConfigHistory("database.config", "DEFAULT_GROUP", 5);
for (ConfigHistory h : history) {
    System.out.println("Version " + h.getVersion() + ": " + h.getDescription());
}

// Delete a configuration
configService.removeConfig("database.config", "DEFAULT_GROUP");
```

#### Message Queue
```java
import io.github.cuihairu.redis.streaming.mq.*;

MessageQueueFactory mq = new MessageQueueFactory(redissonClient);

// Producer: send a message (topic=order-events, key=order-123)
MessageProducer producer = mq.createProducer();
producer.send(new Message("order-events", "order-123", orderData)).join();

// Consumer: subscribe and start consuming
MessageConsumer consumer = mq.createConsumer("order-processor-1");
consumer.subscribe("order-events", "order-processor-group", message -> {
    Object payload = message.getPayload();
    // Process the message
    return MessageHandleResult.SUCCESS;
});
consumer.start();
```

#### Window Aggregation
```java
import io.github.cuihairu.redis.streaming.aggregation.*;
import io.github.cuihairu.redis.streaming.aggregation.functions.SumFunction;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

// Create a window aggregator
WindowAggregator aggregator = new WindowAggregator(redissonClient, "page_views");
aggregator.registerFunction("SUM", SumFunction.getInstance());

TimeWindow window = TumblingWindow.of(Duration.ofMinutes(5));

// Add values (values within the window will be aggregated)
aggregator.addValue(window, "product-123", 19.99, Instant.now());

// Get the aggregated result
BigDecimal total = aggregator.getAggregatedResult(window, "product-123", "SUM", Instant.now());
```

#### CDC Data Capture
```java
import io.github.cuihairu.redis.streaming.cdc.*;
import io.github.cuihairu.redis.streaming.cdc.impl.MySQLBinlogCDCConnector;

// Configure MySQL Binlog CDC (static factories provide defaults per connector type)
CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("order_cdc")
    .username("cdc_user")
    .password("password")
    .mysqlHostname("localhost")
    .mysqlPort(3306)
    .tables("orders,products")
    .build();

// Create the CDC connector
CDCConnector connector = new MySQLBinlogCDCConnector(config);

connector.setEventListener(new CDCEventListener() {
    @Override
    public void onEventsCapture(String connectorName, int eventCount) {
        System.out.println("Captured events: " + eventCount);
    }
});

connector.start().join();
List<ChangeEvent> events = connector.poll();
```

## Tech Stack

### Core Dependencies
- Redisson 4.7.0: Redis client for distributed operations
- Jackson 2.17.0: JSON serialization/deserialization
- Lombok 1.18.34: code generation to reduce boilerplate
- SLF4J 2.0.17: logging abstraction

### Testing
- JUnit Jupiter 5.9.2: unit tests
- Mockito 4.6.1: mocking framework

### Build Tools
- Gradle 8.5+: build tool (wrapper bundled)
- Java 17: compilation target

## Roadmap

### Maturity Overview

All modules are in place (20/20), but "modules complete ≠ framework complete" — current status is annotated per capability:

| Capability | Status |
|---|---|
| Stream API (DataStream / KeyedStream / WindowedStream) | ✅ |
| Redis Runtime (single-process parallelism, event time, windows, timers) | ✅ |
| State / Checkpoint / failure recovery | ✅ |
| Two-phase commit sinks + Outbox-WAL (cross-system delivery path) | ✅ |
| HA: leader election + fencing token + crash takeover | ✅ |
| Watermark/window trigger semantics (idle-partition advancement, etc.) | 🚧 |
| Multi-worker task splitting / dynamic scaling | 🚧 |
| Benchmarks (throughput/latency/recovery-time baselines) | 🚧 |

---

### Completed Modules

#### Tier 1: Core Abstractions
- [x] core: core API definitions
  - Stream processing API abstractions
  - State, checkpoint, watermark, and window abstractions
- [x] runtime: stream processing runtime engine
  - Redis runtime: `RedisStreamExecutionEnvironment` (Redis Streams, single-process parallelism / watermarks / windows / checkpoints)
  - In-memory runtime: `StreamExecutionEnvironment` (for tests/examples)

#### Tier 2: Infrastructure
- [x] mq: message queue
  - Redis Streams implementation
  - Consumer groups, DLQ, async support
- [x] registry: service registry & discovery
  - Service registration, discovery, health checks
  - Multi-protocol support (HTTP/HTTPS/TCP/WebSocket/gRPC)
  - Metadata comparison-operator filtering (`>`, `>=`, `<`, `<=`, `!=`, `==`)
  - Load balancing based on metadata such as weight, CPU, and latency
- [x] config: config center
  - Config publishing, retrieval, deletion
  - Config versioning and history
  - Config change notifications (Redis Pub/Sub)
  - Config listeners and hot reload
- [x] state: state management
  - 4 state types (Value, Map, List, Set)
  - Redis persistence
- [x] checkpoint: checkpointing
  - Distributed coordination, snapshots, recovery
- [x] watermark: watermarks
  - Watermark generator implementations
- [x] window: windowing
  - Tumbling, sliding, and session windows

#### Tier 3: Feature Modules
- [x] aggregation: aggregation functions
  - Window aggregation, PV/UV, TopK
- [x] table: stream-table duality
  - In-memory & Redis-persisted KTable
- [x] join: joins
  - Time-window joins, 4 join types
- [x] cdc: CDC
  - MySQL, PostgreSQL, polling CDC
- [x] sink: output connectors
  - Kafka Sink, Redis Stream/Hash Sinks
- [x] source: input connectors
  - Kafka Source, HTTP API Source, Redis List Source

#### Tier 4: Advanced Features
- [x] cep: complex event processing
  - Kleene closure, advanced pattern operations
- [x] reliability: reliability guarantees
  - Retries, DLQ, deduplication, rate limiting

#### Tier 5: Integrations
- [x] metrics: monitoring metrics
  - Prometheus Exporter, metric collectors
- [x] spring-boot-starter: Spring Boot integration
  - Auto-configuration, annotation support

---

### Next Priorities

#### Delivered (v0.2.3 / v0.2.4)
- End-to-end two-phase commit (`TwoPhaseCommitSink` + recovery compensation + fault injection) and Outbox-WAL dispatch (`RedisOutboxSink` + Dispatcher), see `docs/exactly-once.md`
- Leader election (`RedisLeaderElector`: SET NX PX lease + Lua-based renewal/release) + fencing token + crash takeover (checkpoint IDs realigned from storage)
- Keyed state hot-key handling (LOG_ONLY / THROTTLE / FAIL_FAST), static audit cleanup with the low-severity audit queue cleared

#### High Priority (Runtime Depth)
1. Runtime semantics completion: idle-partition watermark advancement, atomic fire-and-purge, restore atomicity
2. Multi-worker task splitting / dynamic scaling (parallelism changes, partition rebalancing)
3. Benchmark baselines: throughput / p50-p99 latency / recovery time / checkpoint duration (1/2/4 worker comparison)

#### Delivered Semantic Surface
- `DeliveryGuarantee` (AT_MOST_ONCE / AT_LEAST_ONCE / EFFECTIVELY_ONCE): sinks declare their capability via `StreamSink.deliveryGuarantee()` — plain sinks default to AT_LEAST_ONCE, while `TwoPhaseCommitSink` and the Redis exactly-once sinks (atomic checkpoint / idempotent list / Outbox) declare EFFECTIVELY_ONCE

#### Medium Priority (Feature Enhancements)
1. Connector expansion
   - Elasticsearch Sink
   - HBase Sink
   - IoT Device Source

## Documentation

### Quick Start
- [Quick Start Tutorial](QUICK_START.md) - get up and running in 5 minutes
- [Running Examples](RUNNING_EXAMPLES.md) - end-to-end examples and demos
- [Completion Report](docs/archive/COMPLETION_REPORT.md) - module completeness and coverage
- [Testing Guide](TESTING.md) - unit tests / integration tests

### Design Documents
- [Architecture](docs/Architecture.md) - overall architecture design
- [Project Summary](PROJECT_SUMMARY.md) - detailed feature description

### Deployment & Operations
- [Deployment Guide](docs/Deployment.md) - production deployment
- [Performance Tuning](docs/Performance.md) - performance tuning guide

### Development Guides
- [Development Docs](CLAUDE.md) - developer guide
- [Documentation Center](docs/README.md) - documentation site and index (VitePress)

## Contributing

Contributions, bug reports, and suggestions are welcome!

1. Fork the project
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contact

- Project: https://github.com/cuihairu/redis-streaming
- Issues: https://github.com/cuihairu/redis-streaming/issues

---

Current version: 0.2.4 (latest release)
Last updated: 2026-10-08
Completeness: 20/20 modules; dynamic scaling and benchmarks in progress

### Release Notes

0.2.4 - Low-severity audit cleanup + silent-loss window fixes
- [Low-severity audit queue cleared: CDC config validation/serialization, DeferredAcks structural keys, partition count fallback chain, sink commit marker TTL]
- [Closed two silent-loss windows in 2PC/defer-ack: epoch mis-claiming and stale acks after checkpoint abort]
- [Bounded growth of KTable-derived keyspaces (lineage + generational retention)]

0.2.3 - Reliability & HA
- [End-to-end two-phase commit (TwoPhaseCommitSink + recovery compensation + fault injection)]
- [Leader election + fencing token + crash takeover (checkpoint IDs realigned from storage)]
- [Outbox/WAL dispatch (RedisOutboxSink + Dispatcher), keyed state hot-key handling (LOG_ONLY/THROTTLE/FAIL_FAST)]

0.2.2 - Hardening & modernization
- [16 static-audit defect fixes (B-04..B-33)]
- [Redisson 3.52.0→4.7.0, centralized Gradle version catalog, SLF4J unified to 2.0.17]
- [New components: CDCSource, ChangeEventQueueSink, RedisStreamSource; starter decoupled from implicit actuator/Micrometer dependencies]

0.2.0 - Single-process runtime complete + documentation site launched
- [Redis runtime: parallelism/backpressure, watermark/window, end-to-end checkpoints (incl. sink coordination and recovery)]
- [Redis-only atomic commit sinks (Lua: write sink + XACK + commit frontier), with an exactly-once route description (idempotent/2PC/outbox)]
- [Docs migrated to `docs/`, VitePress + GitHub Pages (Actions) auto-build and publish]

0.1.1 - Fixes and quality improvements
- [Stability fixes across Registry/MQ/Reliability modules]
- [Documentation and CI release process improvements]

0.1.0 - Initial release
- [Core API abstractions: stream processing API definitions (DataStream, KeyedStream, WindowedStream)]
- [Infrastructure complete: MQ, Registry (incl. metadata comparison operators), Config, State, Checkpoint, Watermark, Window]
- [Registry enhancements: metadata comparison-operator filtering (`>`, `>=`, `<`, `<=`, `!=`, `==`) and metadata-based load balancing]
- [Config center complete: config versioning, change notifications, history, listener support]
- [Feature modules complete: Aggregation, Table (incl. Redis persistence), Join, CDC]
- [Reliability module: Reliability (incl. Bloom Filter dedup)]
- [Connectors complete: Kafka/Redis Sinks, Kafka/HTTP/Redis Sources]
- [CEP complete: complex event processing (incl. Kleene closure and advanced pattern operations)]
- [Monitoring integration: Prometheus Exporter and metric collectors]
- [Spring Boot auto-configuration (incl. @ServiceChangeListener annotation support)]
- [Runtime module: Redis runtime + in-memory runtime (see `runtime/` and `docs/`)]
