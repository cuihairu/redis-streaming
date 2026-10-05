# Performance Notes

[中文](../Performance.md) | [English](Performance-en.md)

---

Defaults are taken from source code (`RedisRuntimeConfig`, `MqOptions`).

## Partitioning & Throughput

- P partitions approximately scale throughput linearly (subject to CPU/instance and Redis capacity); per-partition serial processing guarantees ordering.
- Under Redis Cluster, different partition keys spread across slots, aiding horizontal scaling.
- Hotspot isolation: hot keys only block their partition, others unaffected.

## Recommended Settings

- **Producer**: batch `XADD`/pipeline when needed; control message size carefully.
- **Consumer**: `COUNT > 1`, `BLOCK 100~500ms`; limit per-worker in-flight (e.g. 100~1000, via `MqOptions.maxInFlight`, default `0` = unlimited).
- **Retry**: exponential backoff (base `retryBaseBackoffMs` default 1000ms, ceiling `retryMaxBackoffMs` default 60000ms, saturated shift avoids overflow); delayed retry uses ZSET + Lua mover (enabled by default). Jitter is not built-in; caller must layer it externally if desired.
- **Retention**: `XTRIM MAXLEN ~ N` bounds memory (`retentionMaxLenPerPartition` default 100000, `trimIntervalSec` default 60s); combine with time boundary (`retentionMs`, default 0 = disabled) for cleanup.

## Redis Runtime Tuning

- **Parallelism**: `RedisRuntimeConfig.pipelineParallelism(n)` (default 1; within a process, subtasks pinned by `partitionId % parallelism`) + horizontal scaling via multiple instances (same consumer group).
- **Backpressure**: `MqOptions.maxInFlight(n)` (global concurrency cap, default 0 = unlimited) + `workerThreads` (execution threads, default 8).
- **Thread Resources**: `timerThreads` (processing-time timers, default 1) / `checkpointThreads` (checkpoint scheduling/execution, default 1, still serial within a job).
- **Queue Capacity**: `eventTimeTimerMaxSize` (event-time timer queue cap, default 100000, 0 = unlimited, prevents unbounded growth).
- **Window**: `windowMaxFiresPerRecord` (max windows a single record can fire, default 256, avoids single record stalling latency); `windowAllowedLateness` (default `Duration.ZERO`, best-effort late tolerance).
- **Watermark**: `watermarkOutOfOrderness` (out-of-orderness tolerance, default `Duration.ZERO`; watermark = max seen event time − value, affects window trigger latency and late determination).

## Benchmarking Suggestions

- Use realistic payloads; vary P, batchSize, parallelism independently.
- Watch metrics: produce/consume rate, p99 handle latency, DLQ rate, Redis CPU/memory/network (metric inventory in [Metrics](../Metrics.md)).

## Trade-offs

- More partitions = more parallelism, but total PEL/workers also grow; choose P based on hardware and load.
- Larger batches = higher throughput, but per-batch latency and memory usage also increase.

---

**Version**: 0.2.0
**Last Updated**: 2026-10-05

Related: [MQ-Design.md](../MQ-Design.md) / [MQ-Broker-Interaction.md](../MQ-Broker-Interaction.md)