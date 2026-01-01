# Performance Notes

## Partitioning & Throughput
- With P partitions, total throughput scales roughly with P given CPU/instances and Redis capacity; ordering preserved per partition
- On Redis Cluster, partitions spread across slots/nodes for horizontal scaling
- Hotspot isolation: hot keys queue only within their partitions

## Recommended Settings
- Producer: batch `XADD` / pipeline when applicable; keep payload size reasonable
- Consumer: `COUNT > 1`, `BLOCK 100–500ms`; limit in-flight per worker (e.g., 100–1000)
- Retry: exponential backoff with jitter; use ZSET + Lua mover (default)
- Retention: `XTRIM MAXLEN ~ N` and time-based cleanup via MINID

## Benchmarks
- Use realistic payloads; vary P, batch size, parallelism
- Track: produce/consume rate, p99 handle latency, DLQ rate, Redis CPU/memory/network

## Trade-offs
- More partitions → more parallelism but larger total PEL/worker count
- Larger batches → higher throughput but higher latency/memory

See `MQ-Design-en.md` and `MQ-Broker-Interaction-en.md` for full rationale.
