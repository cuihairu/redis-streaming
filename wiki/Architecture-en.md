# Streaming Framework Architecture

[中文](Architecture) | [English](Architecture-en)

---

## Architecture Overview

### Overall Architecture (5-Tier Design)

```
Application Layer -> Integration Layer -> Advanced Features Layer -> Functional Modules Layer -> Infrastructure Layer -> Redis
```

### Design Principles

1. **Layered Decoupling**: 5-tier architecture with clear responsibilities
2. **Interface Abstraction**: Separation of API and implementation
3. **Redis-Centric**: All state based on Redis
4. **Lightweight**: No additional components required
5. **Extensible**: Support for custom extensions

## Core Modules

### Tier 1: Core (Core Abstractions)
- DataStream API
- KeyedStream API
- WindowedStream API
- State Abstractions

### Tier 2: Infrastructure
- **MQ**: Redis Streams message queue
- **Registry**: Service registration and discovery
- **State**: Distributed state management
- **Checkpoint**: Checkpointing mechanism

### Tier 3: Functional Modules
- **Aggregation**: Window aggregation
- **Table**: Stream-table duality
- **Join**: Stream joins
- **CDC**: Change Data Capture
- **Sink/Source**: Connectors

### Tier 4: Advanced Features
- **Reliability**: Reliability guarantees
- **CEP**: Complex Event Processing

### Tier 5: Integration
- **Metrics**: Prometheus monitoring
- **Spring Boot**: Auto-configuration

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

---

**Version**: 0.1.0
**Last Updated**: 2025-10-13

🔗 Related Documentation:
- [[Detailed Design|Design-en]]
- [[Registry Design|Registry-Design-en]]
- [[MQ Design|MQ-Design-en]]

## Redis Commands vs Kafka (Quick Map)
- Produce: `XADD stream:topic:{t}:p:{i}` ≈ Kafka produce to partition
- Groups & consume: `XGROUP CREATE`, `XREADGROUP` ≈ create group / fetch
- Commit: `XACK` ≈ commit offsets
- In-flight: `XPENDING` ≈ in-flight (no direct Kafka command)
- Rebalance & reclaim: leases (`SET NX EX`/`EXPIRE`) + `XAUTOCLAIM`/`XCLAIM` ≈ coordinator/rebalance
- Delayed retry: `ZADD/ZRANGEBYSCORE/ZREM` + `EVAL` (Lua mover) ≈ retry topics
- DLQ: `XADD stream:topic:{t}:dlq` ≈ DLQ topic; replay `XRANGE + XADD`
- Retention: `XTRIM MAXLEN/MINID` ≈ retention.bytes/retention.ms
