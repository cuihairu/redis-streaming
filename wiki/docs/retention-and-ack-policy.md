# Retention & ACK Deletion Policy

This module uses Redis Streams as the storage for topic partitions and DLQ. Redis does not delete entries on ACK; instead, you control memory via retention and optional deletion policies.

## Defaults (low overhead)
- Write-time trimming: on each XADD, we best-effort XTRIM MAXLEN ~ `retentionMaxLenPerPartition` (default 100000).
- Background housekeeper: every `trimIntervalSec` (default 60s), we run:
  - XTRIM MAXLEN ~ `retentionMaxLenPerPartition` per partition stream
  - optional XTRIM MINID ~ (now-`retentionMs`)-0 if enabled
  - safe frontier trimming: compute the min committed stream id across active groups (those holding a lease on the partition), run XTRIM MINID ~ safeId
- DLQ retention: optionally enable separate `dlqRetentionMaxLen`/`dlqRetentionMs`.

These defaults keep backlog bounded with minimal latency impact.

## ACK Deletion Policy
Configure `redis-streaming.mq.ackDeletePolicy`:
- `none` (default): only XACK; deletion via retention/housekeeper.
- `immediate`: XACK then XDEL. Only safe for single-group consumption.
- `all-groups-ack`: XACK, collect per-message ack set; when the number of acks reaches the number of active groups, XDEL. Adds per-message set overhead; enable only if you must delete immediately under multi-group.

Active groups are determined by partition leases; empty/offline groups don't block deletion or trimming.

## Recommended set
Single group:
```yaml
redis-streaming:
  mq:
    retentionMaxLenPerPartition: 100000
    trimIntervalSec: 60
    ackDeletePolicy: none  # or immediate if you must free memory immediately
```

Multiple groups:
```yaml
redis-streaming:
  mq:
    retentionMaxLenPerPartition: 100000
    trimIntervalSec: 60
    retentionMs: 0        # optional
    ackDeletePolicy: none # prefer safe frontier trimming
```

DLQ tighter retention:
```yaml
redis-streaming:
  mq:
    dlqRetentionMaxLen: 5000
    dlqRetentionMs: 604800000 # 7 days
```

## Metrics
Micrometer counters:
- `redis_streaming_mq_trim_attempts_total{topic,partition,reason,stream}`
- `redis_streaming_mq_trim_deleted_total{topic,partition,reason,stream}`

Reason: `maxlen|minid|frontier`; Stream: `main|dlq`.

