# Streaming Framework Architecture

[中文](../Architecture.md) | [English](Architecture-en.md)

---

## Architecture Overview

### Overall Architecture (5-Tier Design)

```
Application Layer -> Integration Layer -> Advanced Features Layer -> Functional Modules Layer -> Infrastructure Layer -> Redis
```

### Design Principles

- Layered design: 5 tiers, each with a clear responsibility
- APIs are separated from implementations behind interfaces
- All state is stored in Redis; no additional components are required
- Custom extensions plug in at the extension points listed below

## Core Modules

### Tier 1: Core (Core Abstractions)
- DataStream API
- KeyedStream API
- WindowedStream API
- State Abstractions

### Tier 2: Infrastructure
- MQ: Redis Streams message queue
- Registry: Service registration and discovery
- State: Distributed state management
- Checkpoint: Checkpointing mechanism

### Tier 3: Functional Modules
- Aggregation: Window aggregation
- Table: Stream-table duality
- Join: Stream joins
- CDC: Change Data Capture
- Sink/Source: Connectors

### Tier 4: Advanced Features
- Reliability: Reliability guarantees
- CEP: Complex Event Processing

### Tier 5: Integration
- Metrics: Prometheus monitoring
- Spring Boot: Auto-configuration

## Technology Stack

### Redis Data Structure Mapping

| Feature | Redis Structure |
|---------|----------------|
| Message Queue | Streams |
| Service Registry | Hash + Pub/Sub |
| ValueState | String |
| MapState | Hash |
| ListState | List |
| SetState | Set |
| PV Counter | String (INCR) |
| UV Counter | HyperLogLog |
| Top-K | Sorted Set |
| KTable | Hash |

## Extension Points

- Custom Source/Sink
- Custom aggregation functions
- Custom CEP patterns
- Custom monitoring metrics

## Redis Commands vs Kafka (Quick Map)
- Produce: `XADD stream:topic:{t}:p:{i}` ≈ Kafka produce to partition
- Groups & consume: `XGROUP CREATE`, `XREADGROUP` ≈ create group / fetch
- Commit: `XACK` ≈ commit offsets
- In-flight: `XPENDING` ≈ in-flight (no direct Kafka command)
- Rebalance & reclaim: leases (`SET NX EX`/`EXPIRE`) + `XAUTOCLAIM`/`XCLAIM` ≈ coordinator/rebalance
- Delayed retry: `ZADD/ZRANGEBYSCORE/ZREM` + `EVAL` (Lua mover) ≈ retry topics
- DLQ: `XADD stream:topic:{t}:dlq` ≈ DLQ topic; replay `XRANGE + XADD`
- Retention: `XTRIM MAXLEN/MINID` ≈ retention.bytes/retention.ms

---

Related documentation:
- [Registry Design](Registry-Design-en.md)
- [MQ Design](MQ-Design-en.md)

**Version**: 0.2.0
**Last Updated**: 2026-10-05
