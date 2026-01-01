# Checkpoint 模块

## 概述

Checkpoint 模块提供检查点机制，用于实现流处理应用的容错和状态恢复。支持定期创建检查点、保存状态快照，并在故障发生时从最近的检查点恢复。

## 核心功能

- **检查点协调**: 协调整个作业的检查点创建
- **状态快照**: 保存所有算子的状态到持久化存储
- **故障恢复**: 从最近的检查点恢复作业状态
- **分布式协调**: 支持分布式环境下的检查点协调

## 核心接口

### CheckpointCoordinator

```java
public interface CheckpointCoordinator {
    // 触发检查点
    long triggerCheckpoint() throws Exception;

    // 恢复检查点
    void restoreFromCheckpoint(long checkpointId) throws Exception;

    // 获取最新检查点ID
    long getLatestCheckpointId();
}
```

### CheckpointStorage

```java
public interface CheckpointStorage {
    // 保存检查点
    void saveCheckpoint(Checkpoint checkpoint) throws IOException;

    // 获取检查点
    Checkpoint getCheckpoint(long checkpointId) throws IOException;

    // 获取最新检查点
    Checkpoint getLatestCheckpoint() throws IOException;
}
```

## 使用方式

### 1. 创建检查点协调器

```java
CheckpointStorage storage = new RedisCheckpointStorage(redissonClient);
CheckpointCoordinator coordinator = new RedisCheckpointCoordinator(
    redissonClient,
    storage,
    "my-job"
);
```

### 2. 触发检查点

```java
long checkpointId = coordinator.triggerCheckpoint();
System.out.println("Checkpoint created: " + checkpointId);
```

### 3. 恢复检查点

```java
long latestId = coordinator.getLatestCheckpointId();
coordinator.restoreFromCheckpoint(latestId);
```

## Redis 数据结构

- **检查点数据**: Hash
  - Key: `checkpoint:{jobId}:{checkpointId}`
  - Fields: timestamp, status, stateSnapshots

- **检查点索引**: Sorted Set
  - Key: `checkpoint:{jobId}:index`
  - Score: checkpointId
  - Member: checkpointId

## 相关文档

- [State 模块](State.md) - 状态管理
- [Runtime 模块](Runtime.md) - 运行时环境
