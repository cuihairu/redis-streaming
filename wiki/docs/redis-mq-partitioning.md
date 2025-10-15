# MQ Partitioning Design

This is a focused write-up on partitioning for the MQ subsystem.

- Naming: `stream:topic:{t}:p:{i}`; DLQ per topic `stream:topic:{t}:dlq`.
- Meta: `streaming:mq:topic:{t}:meta` with `partitionCount`; registry `streaming:mq:topics:registry`.
- Routing: hash(key) % P; fallback RR/random when no key; include `partitionId` header.
- Consumer: per-partition serial worker; leases `streaming:mq:lease:{t}:{g}:{i}`; XAUTOCLAIM orphaned pending.
- Aggregated Admin: sum length/pending; `lag ≈ Σ(lastId - groupLastDeliveredId)`.

```mermaid
flowchart LR
  P(Producer) -->|hash(key)%P| P0[stream:topic:{t}:p:0]
  P --> P1[stream:topic:{t}:p:1]
  C(Consumer Group g) -->|XREADGROUP| P0
  C -->|XREADGROUP| P1
```

Pros: scalable throughput, hotspot isolation, cluster-friendly.
Cons: re-partitioning remaps keys; more workers; eventual consistency in rebalance.

See `redis-mq-design.md` for full details.
