# Project Summary

`redis-streaming` is a multi-module (Gradle) Java 17 project that provides a pragmatic set of **streaming building blocks** on top of Redis (and optional external systems like Kafka / MySQL / PostgreSQL).

The goal is to keep the core APIs small and composable, while shipping production-grade “infrastructure modules” (MQ / registry / config / state / reliability / metrics) that can be adopted independently.

## Quick Links
- Start here: `README.md`, `QUICK_START.md`
- Run demos: `RUNNING_EXAMPLES.md`
- How to test: `TESTING.md` (unit vs integration)
- Completion & coverage notes: `COMPLETION_REPORT.md`
- Design docs index: `docs/README.md` (and `wiki/`)

## Module Overview (20 modules)

### Core APIs
- `core`: Streaming API definitions (DataStream / KeyedStream / WindowedStream), window & watermark contracts, state descriptors.
- `runtime`: **Minimal in-memory runtime** used for deterministic unit tests and lightweight examples (single-process, non-distributed).

### Infrastructure Modules
- `mq`: Redis Streams based MQ (topics, partitions, consumer groups, retry/DLQ, retention & ACK policy).
- `registry`: Service registry & discovery (health, metadata filters with comparators, load balancing).
- `config`: Redis-backed configuration service (versioning, history, notifications/listeners).
- `state`: Redis state primitives (Value/Map/List/Set) with typed descriptors.
- `checkpoint`: Checkpoint coordinator + storage primitives.

### Stream Semantics & Operators
- `watermark`: Watermark strategies / generators.
- `window`: Window assigners and supporting primitives.
- `aggregation`: Window aggregator + analytics utilities (PV/UV/TopK/quantiles) for rolling windows.
- `table`: KTable abstraction (InMemory + Redis-backed) and grouping helpers.
- `join`: Time-window stream-stream join operators.
- `cep`: CEP pattern matching with sequences, contiguity, quantifiers (Kleene closures), and `within(...)` constraints.
- `cdc`: CDC connectors (MySQL binlog, PostgreSQL logical replication, polling-based connector).

### Connectors & Integration
- `source`: Sources (Kafka, HTTP API, Redis List).
- `sink`: Sinks (Kafka, Redis Stream/Hash).
- `reliability`: Retry policies/executor, DLQ services, deduplication, rate-limiters.
- `metrics`: Prometheus exporter + metrics collectors.
- `spring-boot-starter`: Spring Boot auto-configuration & operational integration.
- `examples`: Runnable demos (not published as a library artifact).

## Runtime Status (Intentional Scope)
`runtime` is currently designed as a **test/example runtime**, not a distributed stream engine:
- ✅ Deterministic iteration over in-memory records for unit tests
- ✅ Timers / watermarks / basic in-memory checkpointing primitives used by examples
- 🚧 Parallelism / distributed scheduling / end-to-end exactly-once semantics are not implemented yet

If you need a distributed engine, you can still reuse most infrastructure/operator modules (`mq`, `registry`, `state`, `reliability`, etc.) independently.

## Testing & Quality
- Unit tests: `./gradlew test` (no Redis required)
- Integration tests: `docker-compose up -d && ./gradlew integrationTest && docker-compose down`
- Coverage report: `./gradlew jacocoRootReport` → `build/reports/jacoco/jacocoRootReport/html/index.html`

