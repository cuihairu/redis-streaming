# Streaming Framework Architecture

[中文](../Architecture.md) | [English](Architecture-en.md)

---

## Architecture Overview

This document outlines the overall architecture of the streaming framework and highlights the role and interactions of the MQ (Redis Streams) subsystem. Detailed design is in [MQ-Design.md](./MQ-Design.md).

## Overall Architecture (5-Tier Design)

The project is a Gradle multi-module build (20 modules, matching `settings.gradle`):

| Tier | Modules |
|---|---|
| Tier 1: Core Abstractions | `core` (API abstractions), `runtime` (stream processing runtime engine) |
| Tier 2: Infrastructure | `mq`, `registry`, `config`, `state`, `checkpoint`, `watermark` |
| Tier 3: Functional Modules | `window`, `aggregation`, `table`, `join`, `cdc`, `sink`, `source` |
| Tier 4: Advanced Features | `reliability`, `cep` |
| Tier 5: Integration | `metrics`, `spring-boot-starter`, `examples` |

## Design Principles

- **Redis-Centric**: All state and coordination use Redis data structures (Streams, Hash, ZSet, Pub/Sub, keys).
- **No External Coordinator**: Consumer groups, rebalancing, and lease management are built on Redis primitives (no Kafka broker, no ZooKeeper/etcd).
- **At-Least-Once by Default**: Exactly-once is available via the checkpoint + 2PC runtime for pipelines that need it.
- **Horizontal Scalability**: Partitioned streams + consumer groups allow linear throughput scaling.
- **Observable**: Built-in Micrometer bridges for MQ, runtime, retention, DLQ, and rate-limiting metrics.

## Core Modules

### Tier 1: Core (Core Abstractions)
- `core`: Pure API interfaces — `DataStream`, `State`, `Checkpoint`, `Window`, `Join`, `Source`, `Sink`, etc. No Redis dependencies.
- `runtime`: Minimal in-memory runtime (single-threaded) for tests and examples. `RedisStreamExecutionEnvironment` is the production entry point that adds Redis-backed checkpointing, state, and exactly-once sinks.

### Tier 2: Infrastructure
- `mq`: Redis Streams message queue — partitioned topics, consumer groups, leases, retry/DLQ, commit frontier, retention.
- `registry`: Service registration & discovery — heartbeats, metadata/metrics filtering, change notifications, client-side load balancing.
- `config`: Configuration center — versioned configs, change listeners, Redis-backed storage.
- `state`: Keyed state abstractions + Redis-backed implementations (`RedisKeyedStateBackend`).
- `checkpoint`: Checkpoint coordination, storage, and alignment barriers.
- `watermark`: Watermark generators (periodic, punctuated, idle-source).

### Tier 3: Functional Modules
- `window`: Window assigners (tumbling, sliding, session), triggers, evictors.
- `aggregation`: Window aggregations (TopK, quantiles, PV/UV, etc.).
- `table`: KTable (in-memory & Redis-backed).
- `join`: Stream-stream joins.
- `cdc`: CDC connectors (MySQL binlog, PostgreSQL logical replication, polling).
- `source`: Input connectors (Kafka, HTTP, Redis, etc.).
- `sink`: Output connectors (Kafka, Redis, etc.).

### Tier 4: Advanced Features
- `reliability`: Deduplication, DLQ helpers, exactly-once building blocks.
- `cep`: Complex Event Processing (pattern detection on streams).

### Tier 5: Integration
- `metrics`: Micrometer collectors/binders, Prometheus exporter.
- `spring-boot-starter`: Auto-configuration for all modules, Redisson integration, health indicators.
- `examples`: Runnable usage patterns.

## Technology Stack

- **Language**: Java 17 (`options.release = 17`)
- **Build**: Gradle 8.5
- **Redis Client**: Redisson 4.7.0
- **Serialization**: Jackson 2.17, Lombok 1.18
- **Testing**: JUnit Jupiter 5.9, Mockito 4.6
- **Observability**: Micrometer, Prometheus, SLF4J/Logback

## Redis Data Structure Mapping

| Subsystem | Redis Structures |
|---|---|
| MQ | Streams (partitioned topics), ZSet (retry buckets), Hash (retry items, commit frontier), String (leases, payload offload), Set (topic registry) |
| Registry | Set (service index), Hash (instance details), ZSet (heartbeats), Pub/Sub (change notifications) |
| Config | Hash (config versions), Pub/Sub (change notifications) |
| State/Checkpoint | Hash (keyed state), String (checkpoint handles) |
| Retention | Stream `XTRIM`, ZSet (payload TTL index) |

## Extension Points

- `Partitioner` — custom partitioning logic for producers.
- `HealthChecker` — protocol-specific health probes (HTTP, TCP, gRPC, custom).
- `RetryPolicy` — custom backoff strategies.
- `BrokerFactory` / `BrokerRouter` / `BrokerPersistence` — swap the MQ backend (Redis vs JDBC).
- `RateLimiter` algorithms (sliding window, token bucket, leaky bucket).

## Redis Commands vs Kafka (Quick Map)

- Produce: `XADD stream:topic:{t}:p:{i}` ≈ Kafka Producer → partition (key format in `StreamKeys.java`, prefix configurable via `StreamKeys.configure`)
- Create group: `XGROUP CREATE` ≈ Kafka create consumer group
- Consume: `XREADGROUP GROUP <g> <c>` ≈ Kafka fetch (batch + block)
- Commit offset: `XACK` ≈ Kafka commit
- Pending query: `XPENDING` ≈ in-flight (Kafka has no direct equivalent)
- Orphan reclaim: `XPENDING`+`XCLAIM` (Redisson `listPending` + `claim`) ≈ rebalance reclaim of unacked records
- Partition lease: `SET NX EX` + `EXPIRE` (lease) ≈ group coordinator assigns partitions
- Delayed retry: `ZADD/ZRANGEBYSCORE/ZREM` + `EVAL` (Lua mover) ≈ retry topic / delayed replay
- Retention/trim: `XTRIM MAXLEN/MINID` ≈ retention.bytes/retention.ms
- DLQ: `XADD stream:topic:{t}:dlq` ≈ DLQ topic; replay via `XRANGE + XADD`

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05

Related documentation:
- [MQ Design](MQ-Design-en.md)
- [MQ Broker Interaction](MQ-Broker-Interaction-en.md)
- [Registry Design](Registry-Design-en.md)
- [Spring Boot Starter](Spring-Boot-Starter-en.md)