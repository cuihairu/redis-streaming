# Checkpoint

Module: `checkpoint/`

Checkpointing and retention policies

## Scope
- 检查点生成/协调（`CheckpointCoordinator`）
- 检查点存储（`CheckpointStorage`，当前提供 Redis 实现）
- 与 MQ 留存/ACK 策略的联动见文档（Retention & ACK policy）

## Key Classes
- `DefaultCheckpoint`, `redis.RedisCheckpointCoordinator`, `redis.RedisCheckpointStorage`, `storage.CheckpointStorage`

## Minimal Sample
```java
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;

// RedissonClient redisson = ... (see State.md)
RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson);
RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2);

long checkpointId = coordinator.triggerCheckpoint();
coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
coordinator.acknowledgeCheckpoint(checkpointId, "task-2"); // will complete automatically
```

## References
- docs/retention-and-ack-policy.md
