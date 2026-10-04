# Checkpoint 模块

模块目录:`checkpoint/`

## 概述

Checkpoint 模块把 core 的检查点契约落到 Redis 上:

- `checkpoint.storage.CheckpointStorage` —— 检查点持久化接口
- `checkpoint.redis.RedisCheckpointStorage` —— Redis 实现(每个检查点一个 Redis String/Bucket 键)
- `checkpoint.redis.RedisCheckpointCoordinator` —— 触发 → 任务确认(ack)→ 完成的协调器,完成条件与超时在协调器进程内管理
- `checkpoint.DefaultCheckpoint` —— `Checkpoint` 的默认实现,带快照格式版本号

模块本身不含周期调度:检查点何时触发由调用方决定;runtime 的 Redis 引擎用 `RedisRuntimeConfig.checkpointInterval`(默认 `Duration.ZERO`,即不调度)做周期触发,并复用本模块的 `DefaultCheckpoint` / `RedisCheckpointStorage`(见 [runtime.md](runtime.md))。

## 核心接口

### CheckpointCoordinator(core 模块)

```java
public interface CheckpointCoordinator {
    long triggerCheckpoint();                              // 返回检查点 ID
    void acknowledgeCheckpoint(long checkpointId, String taskId);
    void completeCheckpoint(long checkpointId);
    void restoreFromCheckpoint(long checkpointId);
    Checkpoint getLatestCheckpoint();                      // 无则返回 null
    Checkpoint getCheckpoint(long checkpointId);           // 无则返回 null
}
```

接口方法均不抛受检异常。

### Checkpoint(core 模块)与 DefaultCheckpoint

```java
public interface Checkpoint extends Serializable {
    long getCheckpointId();
    long getTimestamp();
    StateSnapshot getStateSnapshot();
    boolean isCompleted();
    void markCompleted();
    default int getSnapshotVersion();      // 默认 0:无版本标记的旧快照
}

public interface Checkpoint.StateSnapshot extends Serializable {
    <T> T getState(String key);
    default <T> T getState(String key, Class<T> type);  // 类型不符抛 IllegalStateException
    <T> void putState(String key, T value);
    Iterable<String> getKeys();
}
```

`DefaultCheckpoint`(`checkpoint.DefaultCheckpoint`)实现:

- 常量 `CURRENT_SNAPSHOT_VERSION = 1`、`LEGACY_SNAPSHOT_VERSION = 0`;新建实例写版本 1,经无参构造器反序列化(为 JSON codec 准备)保持 0。
- `new DefaultCheckpoint(long checkpointId, long timestamp)` 为应用构造入口。
- `StateSnapshotImpl` 额外提供 `size()` 与 `clear()`;`getState(key, type)` 在类型不匹配时用 Jackson `ObjectMapper.convertValue` 尝试转换,失败抛 `IllegalStateException`。

### CheckpointStorage

```java
public interface CheckpointStorage {
    void storeCheckpoint(Checkpoint checkpoint) throws Exception;
    Checkpoint loadCheckpoint(long checkpointId) throws Exception;   // 无则返回 null
    Checkpoint getLatestCheckpoint() throws Exception;               // 只返回已完成的最新检查点
    List<Checkpoint> listCheckpoints(int limit) throws Exception;    // 按时间戳降序
    boolean deleteCheckpoint(long checkpointId) throws Exception;
    int cleanupOldCheckpoints(int keepCount) throws Exception;       // 返回删除数
    void close();
}
```

### RedisCheckpointCoordinator 的协调语义

- `triggerCheckpoint()`:生成自增 ID(计数器初值 = 已存最新检查点 ID + 1),写入存储并登记为 pending;存储失败返回 `-1` 且不登记。
- `acknowledgeCheckpoint(checkpointId, taskId)`:登记任务确认;凑满 `requiredTaskAcks` 个不同 taskId 后自动 `completeCheckpoint`。
- `completeCheckpoint(checkpointId)`:从存储读回检查点、`markCompleted()` 后重新写入。
- 超时:pending 超过 `checkpointTimeout` 毫秒的检查点不会完成,并被 `cleanupExpiredPendingCheckpoints()` 清理;`getPendingCheckpointCount()` 返回当前 pending 数。
- 恢复:`restoreFromCheckpoint(long)` 只校验并回传快照条目(协调器不持有状态后端);带 sink 的重载 `restoreFromCheckpoint(long, BiConsumer<String, Object>)` 把每个 `(key, value)` 交给回调,返回移交条数;检查点不存在或未完成时返回 `-1`,不做恢复。
- `cleanupOldCheckpoints(int keepCount)`:优先淘汰未完成检查点,再淘汰更早的已完成检查点,存活数仍为 `keepCount`。
- `close()`:清空 pending 并调用 `storage.close()`。

## 配置项

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `RedisCheckpointStorage` 构造参数 `keyPrefix` | `String` | `"checkpoint:"` | 检查点键前缀,实际键 = `keyPrefix + checkpointId` |
| `RedisCheckpointCoordinator` 构造参数 `requiredTaskAcks` | `int` | 必填 | 完成一个检查点所需的不同任务确认数 |
| `RedisCheckpointCoordinator` 构造参数 `checkpointTimeout` | `long`(毫秒) | `60000` | pending 检查点超时;`<= 0` 表示不清理超时 |
| `DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION` | `int` | `1` | 当前实现写入的快照版本 |
| `DefaultCheckpoint.LEGACY_SNAPSHOT_VERSION` | `int` | `0` | 无版本标记的旧快照读出的版本 |

runtime 侧相关配置(`RedisRuntimeConfig`,详见 [runtime.md](runtime.md)):`checkpointInterval`(默认 `Duration.ZERO`)、`checkpointThreads`(默认 1)、`checkpointKeyPrefix`(默认 `streaming:runtime:checkpoint:`)、`checkpointsToKeep`(默认 5)、`checkpointDrainTimeout`(默认 30s)。

## 用法示例

以下取自 `examples` 模块的 `CheckpointExample`(需本地 Redis,地址可用 `REDIS_URL` 覆盖):

```java
import java.util.HashMap;
import java.util.Map;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");
RedissonClient redisson = Redisson.create(config);

// 1. 创建存储与协调器(需要 3 个任务确认)
RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "example:checkpoint:");
RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3);

// 2. 触发检查点(登记为 pending)
long checkpointId = coordinator.triggerCheckpoint();

// 3. 把算子状态写进 pending 检查点的快照,再写回存储(与集成测试 CheckpointRedisLiveIntegrationTest 同款流程)
Checkpoint pending = storage.loadCheckpoint(checkpointId);
pending.getStateSnapshot().putState("user-count", 1000);
pending.getStateSnapshot().putState("offsets", Map.of("p0", 11L, "p1", 22L));
storage.storeCheckpoint(pending);

// 4. 任务确认;凑满 3 个 ack 自动完成
coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
coordinator.acknowledgeCheckpoint(checkpointId, "task-3");

Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
System.out.println("completed: " + checkpoint.isCompleted());   // true

// 5. 恢复:把已完成检查点的每个条目交给回调
Checkpoint latest = coordinator.getLatestCheckpoint();
if (latest != null) {
    Map<String, Object> restored = new HashMap<>();
    int count = coordinator.restoreFromCheckpoint(latest.getCheckpointId(), restored::put);
    System.out.println("restored " + count + " entries");
}

// 6. 清理旧检查点,只保留最近 2 个
int deleted = coordinator.cleanupOldCheckpoints(2);

coordinator.close();
redisson.shutdown();
```

## Redis 数据结构

- 每个检查点一个键:`{keyPrefix}{checkpointId}`(默认前缀即 `checkpoint:0`、`checkpoint:1`……),值为整个 `Checkpoint` 对象,经 `RBucket.set/get` 按 Redisson codec 编解码。
- `listCheckpoints` 用 `getKeys(KeysScanOptions.defaults().pattern(keyPrefix + "*"))` 扫描,仅接受纯数字后缀的键,按时间戳降序返回;不使用 Hash 或 Sorted Set 索引。
- `getLatestCheckpoint()` 只把 `isCompleted()` 的检查点视为有效恢复点。

## 注意事项

1. 协调器的 pending/确认表在协调器进程内存中(`ConcurrentHashMap`),不在 Redis:确认请求需要发给创建该检查点的同一个 `RedisCheckpointCoordinator` 实例。
2. 未完成(缺 ack 或超时)的检查点不会被 `getLatestCheckpoint()` 选中,`restoreFromCheckpoint` 也会拒绝(返回 `-1`),避免从未完成的撕裂快照恢复。
3. `restoreFromCheckpoint(long)` 单参版本只做校验与日志,不回填任何状态;要取回数据必须用 `BiConsumer` 重载。
4. 检查点 ID 计数器在构造时从存储的最新 ID + 1 起步;构造时存储不可达会标记 `idSeedUnverified`,下一次 `triggerCheckpoint` 前懒重试重播种,避免把旧检查点用 ID 0 覆盖。

## 相关文档

- [State 模块](state.md) - 状态管理
- [Runtime 模块](runtime.md) - 周期触发与运行时检查点管理
- [Core API](Core.md) - 检查点接口定义
