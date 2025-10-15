# Performance Notes

## Partitioning and Throughput
- With P partitions and per-partition serial workers, total throughput scales approximately with P given enough CPU/instances and Redis capacity.
- On Redis Cluster, partitions are hashed to slots, distributing load across nodes.
- Hot keys are isolated to their own partition queues, reducing tail latency for non-hot keys.

## Recommended Settings
- Producer: batch XADD where applicable; consider pipelining.
- Consumer: `COUNT > 1`, `BLOCK ~ 100-500ms`, limit in-flight per worker (e.g., 100-1000).
- Retry: exponential backoff with jitter to prevent thundering herds.
- Retention: use XTRIM MAXLEN to bound memory; tune per topic.

## Benchmarks (Guidelines)
- Benchmark on realistic payload sizes; vary P, batch size, and consumer parallelism.
- Measure: produce rate, consume rate, p99 handle latency, DLQ rate, Redis CPU/mem, network.

## Trade-offs
- More partitions increase parallelism but also total pending lists and worker count.
- Larger batches improve throughput but increase latency and memory pressure.

Refer to `docs/redis-mq-design.md` for full rationale and diagrams.
