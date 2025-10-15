# Redis MQ Broker Interaction

This document captures the client<->Redis interactions used by the MQ subsystem.

## Key Commands
- XADD: append entries to partition streams or DLQ.
- XREADGROUP: read from partition streams within a group.
- XACK: acknowledge processed messages.
- XPENDING: inspect pending messages.
- XAUTOCLAIM: reclaim orphaned pending messages by idle time.
- XTRIM: retention by length; time-based retention via ID boundaries.
- SET NX EX: used for lease acquisition with TTL; EXPIRE to renew.
- ZADD/ZRANGEBYSCORE/ZREM: delayed retry bucket operations.

## Produce Flow
```mermaid
sequenceDiagram
  participant P as Producer
  participant M as Meta/Registry
  participant S as stream:topic:{t}:p:i
  P->>M: ensure meta (partitionCount)
  P->>P: i = hash(key)%P
  P->>S: XADD headers(payload, partitionId=i)
```

## Consume Flow
```mermaid
sequenceDiagram
  participant W as Worker
  participant S as stream:topic:{t}:p:i
  W->>S: XREADGROUP COUNT n BLOCK t
  W->>W: handle
  alt success
    W->>S: XACK
  else retry
    W->>S: XACK
    W->>Retry: ZADD(now+backoff)
  else dead-letter
    W->>DLQ: XADD
    W->>S: XACK
  end
```

## Rebalance & Recovery
```mermaid
sequenceDiagram
  participant C as Consumer Instance
  participant L as Lease Key
  participant S as stream:topic:{t}:p:i
  C->>L: SET NX EX (acquire)
  loop heartbeat
    C->>L: EXPIRE (renew)
  end
  note over C: on owner death
  C->>S: XAUTOCLAIM idle>threshold
```

## Retention & Cleanup
- Prefer XTRIM MAXLEN for length-based retention.
- Time-based cleanup: compute boundary IDs then delete ranges (avoid full range reads).

## Rationale
- Using first-class Redis Streams commands and minimal coordination primitives keeps infra simple and portable.
- XAUTOCLAIM is critical to automatic recovery of orphaned pending entries.

