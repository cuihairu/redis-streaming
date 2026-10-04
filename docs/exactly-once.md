# Exactly-once 路线设计（Redis Runtime）

本文描述 `runtime`（尤其是 `runtime` 的 Redis runtime）要达到“端到端 exactly-once side effects”的路线与边界，给出可选方案与推荐的分阶段落地策略。

> 术语说明：这里的 exactly-once 指 **sink 侧效果（side effects）恰好一次**。在分布式系统里，“消息被消费恰好一次”往往不可达或成本极高；工程上通常追求“效果恰好一次”，实现方式是 **事务性提交** 或 **幂等写入**。

## 现状（当前实现能力）

Redis runtime 当前提供的是 at-least-once 加更强对齐的一致性基础：

- 默认 `SUCCESS` 后 ACK（at-least-once）。
- 端到端 checkpoint（实验）：`deferAckUntilCheckpoint=true` 时，消息处理成功后不 ACK，直到 checkpoint 完成后再统一 ACK；checkpoint 元数据包含 `sinkCommitted`（快照 meta 字段 + `runtime:sinkCommitted:{checkpointId}` marker），恢复时以最近的 `sinkCommitted=true` checkpoint 为基线，未提交的 checkpoint 不作为恢复点。唯一例外：最新的「已 store 未 commit」的两阶段 epoch（存有事务句柄、未被 `markTxnEpochAborted` 丢弃、也未被更新的已提交 checkpoint 取代）会连同句柄一起恢复，用 `recoverAndCommit` 补偿（见 `restoreFromLatestCheckpointOrNull` / `getLatestInDoubtTwoPhaseCheckpoint`）。
- sink 钩子（实验）：`CheckpointAwareSink` 支持 `onCheckpointStart/onCheckpointComplete/onCheckpointAbort/onCheckpointRestore`，用于将 side effects 延迟到 checkpoint 完成点。
- sink 去重（best-effort）：`sinkDeduplicationEnabled=true` 基于 `x-original-message-id` 做“运行时去重”，降低重放/重试导致的重复写入概率，但不构成严格 exactly-once 保证。

这让 checkpoint 成为 (state + offsets) 的一致恢复点，可信度高于裸 at-least-once；但 side effects 可能在 checkpoint 前后非原子发生，严格的 exactly-once side effects 仍未达到。

## 约束与难点（为什么 exactly-once 很难）

1. 跨系统原子性不可得：要 exactly-once，必须把“写 sink + 提交 source offset（或 ACK）”做成一个不可分割的原子动作；当 sink 在 Redis 之外（JDBC/Kafka/HTTP）时，天然跨系统。
2. Redis Streams ACK 与外部事务不绑定：Redis 的 `XACK` 无法与外部系统事务同一个事务域提交。
3. 故障窗口：常见的“最难”窗口是：
   - checkpoint 已写入（state+offsets）但 sink commit 失败/未执行；
   - sink commit 已执行但 ACK 未执行；
   - ACK 已执行但 sink commit 未执行（这是必须避免的）。

因此，路线设计的核心就是：**在任何故障窗口里，都能通过恢复逻辑保证 side effects 最终恰好一次**（要么做到事务性 commit，要么保证幂等写入）。

## 可选方案（从易到难）

### 方案 A：幂等 sink（推荐作为 v1 基线）

把“去重/幂等”下沉到 sink 的目标存储，通过唯一键/幂等写接口确保重复写入不会产生重复效果。

幂等键优先用稳定的 event-id，其次 `mq.Message.id`/`x-original-message-id`，实践中最常用的是业务主键。落到实现上：

- JDBC：`INSERT ... ON CONFLICT DO NOTHING` / `INSERT IGNORE` / 唯一索引 + upsert
- Redis：Lua “check-and-set” 或 `SETNX`/`HSETNX` + TTL
- Kafka：幂等 producer（只保证 producer 侧幂等，不等价于端到端 exactly-once）

配合现有 runtime 的三处开关：`deferAckUntilCheckpoint=true` 让 ACK 对齐到 checkpoint 完成点；`CheckpointAwareSink` 把 side effects 延迟到 `onCheckpointComplete` 触发；`sinkDeduplicationEnabled` 可留作运行时层面的额外保险，但它替代不了真正的幂等 sink。

实现成本低、适配面广，不改 runtime 核心协议也能逐步落地；代价是 exactly-once 依赖外部存储的幂等能力，业务建模上必须有唯一键。

### 方案 B：Two-Phase Commit Sink（2PC，推荐作为 v2）

提供类似 Flink 的两阶段提交 sink，将 checkpoint 作为事务边界：

1) `beginTxn()`：开始一个 sink 事务（或“可提交但不可见”的写入会话）  
2) `preCommit(txn)`：把本次 checkpoint 之前的 side effects 准备好（flush 到事务/缓冲区）  
3) 写 checkpoint（state+offsets+txn 句柄）：将事务句柄持久化进 checkpoint  
4) `commit(txn)`：checkpoint 成功后提交 sink 事务；再将 checkpoint 标记 `sinkCommitted=true`；最后 ACK/推进 offsets

API 已落地：`core` 定义 `TwoPhaseCommitSink<T, Txn extends Serializable>`，完整签名与提案差异见下方 As-built。

恢复语义是关键。恢复时依据 checkpoint 里的 `sinkCommitted` marker 判断：marker 存在说明句柄已提交过，跳过；marker 缺失（store 后 commit 前崩溃）则对留存句柄执行 `recoverAndCommit` 补偿。ACK 排在 `markSinkCommitted` 之后，不会出现“ACK 已推进但 sink 未提交”的状态。

在支持事务的 sink 上可以拿到严格的 exactly-once side effects；代价是实现复杂，每个 sink 都要实现事务协议，外部系统不支持事务时用不了。

As-built 以 2026-09-28 的落地实现为准，与上述提案的差异如下：

- API（`core/.../api/stream/TwoPhaseCommitSink.java`）：`Txn beginTxn()`（无 checkpointId 参数，由运行时在 epoch 首条消息前**惰性开启**，commit/abort 后自动开新 epoch）、`invoke(T value, Txn txn)`、`preCommit(Txn)`、`commit(Txn)`、`abort(Txn)`（默认委托 `recoverAndAbort`）、`Txn recoverAndCommit(Txn)` / `Txn recoverAndAbort(Txn)`。`Txn extends Serializable`。单参 `invoke(T)` 被桥接为 fail-fast（2PC sink 不允许直写）。
- 句柄持久化：`TwoPhaseCommitCoordinator`（runtime）以 Java 序列化 + Base64 编码句柄，存储无关（内存/Redis checkpoint 皆可放）；checkpoint 快照键 `runtime:txns`，键 `"runnerIndex:sinkIndex"`，空 map 不写键。
- 运行时序固定为 `prepareCommit → storeCheckpoint(handle) → commit → markSinkCommitted → ack`；空 epoch 也 preCommit（句柄总是落 checkpoint）。
- commit 抛错不予 abort 补偿：句柄已落 checkpoint，丢弃会丢数据——epoch 保持未决，恢复路径从存储句柄重放 `recoverAndCommit`（幂等）。仅 preCommit 失败或 store 失败走 `abort`。
- 恢复补偿：restore 后按 runner 索引回放——`sinkCommitted` marker 存在→句柄已过期跳过；marker 缺失（store 后 commit 前崩溃）→逐个 `recoverAndCommit`；补偿失败仅记日志不阻断启动。
- 参考实现是 `RedisOutboxSink<T>`（方案 C 的 outbox 即以 2PC API 驱动：preCommit 逐条 XADD、commit 翻 epoch 状态 COMMITTED）。其投递语义为 at-least-once，端到端 exactly-once 需目标端按稳定 record id 幂等（方案 C 折中口径）。
- 测试：`TwoPhaseCommitSinkTest`（6）、`TwoPhaseCommitCoordinatorTest`（9）、`RedisPipelineRunnerTwoPhaseCommitTest`（5）、`TwoPhaseCommitFaultInjectionTest`（11，含 store 后 commit 前崩溃/commit 抛错/store 失败注入）、`TwoPhaseCommitRecoveryCompensationTest`（4）；v2.5 侧 sink/dispatcher/fault 28 例。以上均为 Mock/单元级测试；真 Redis 故障注入与恢复的集成测试尚未提供。

### 方案 C：Outbox / WAL（推荐作为 v2.5 或特定场景）

把 side effects 写入一个“可恢复的 outbox”（例如 Redis Stream/Hash/表），outbox 写入与 checkpoint 同域（Redis 内部），然后由异步 dispatcher 把 outbox 投递到外部系统。

外部投递由此变成可重试的后处理，checkpoint 只需要保证 outbox 记录不丢不重。前提是 dispatcher 必须具备幂等投递能力（通常仍需要外部幂等或投递去重表）；代价是增加延迟与存储，系统变成“最终一致”。

### 方案 D：Redis 内部 exactly-once（单 Redis 实例/同槽位键）

若 sink 也落在 Redis 中（并且 keys 可保证同一 Redis 实例/同 hash slot），可以把写 sink（如 HSET/SET/Stream add）、更新 commit frontier（offset）、执行 `XACK` 三步打包成一个 Lua 脚本原子执行，获得“Redis 视角”的 exactly-once。

限制有两条：Redis Cluster 下跨 slot key 无法在同一个 Lua 脚本原子执行（除非使用 hash tags 强制同槽位）；这类 exactly-once 仅在“sink 也在 Redis 内”时才成立。

与 checkpoint 对齐的增强做法有两种。一是把 sink side effects 延迟到 checkpoint 完成点、checkpoint complete 之后统一提交，例如 `RedisCheckpointedIdempotentListSink` 这种 “commit-on-checkpoint” 模式；二是在 checkpoint complete 时用单条 Lua 把 “写入 sink + XACK + 推进 commit frontier” 打包成一次原子执行（`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord`）。

## 推荐落地路线（分阶段）

### v1：Exactly-once by Idempotency（先上线、可运维）

- 明确对外语义：`deferAckUntilCheckpoint=true` + `CheckpointAwareSink` + “幂等 sink” = 端到端效果恰好一次（依赖 sink 端幂等）。
- 给出标准幂等键建议：优先业务主键；其次 `x-original-message-id`；需要 TTL/清理策略。
- 提供至少一个“幂等 sink 示例”（如 Redis idempotent writer 或 JDBC upsert sink）。
  - 参考实现：`core` 提供 `IdempotentRecord<T>`，`runtime` 提供 `RedisIdempotentListSink<T>`（Lua 原子去重 + RPUSH）。
  - 注意：在 Redis Cluster 下，Lua 脚本要求所有 KEYS 在同一 hash slot；建议对 dedup key 与 sink key 使用相同 hash tag（如 `xxx:{job}:seen`、`xxx:{job}:list`）。

### v2：Two-Phase Commit Sink（已落地，见方案 B 的 As-built）

- `core` 的 `TwoPhaseCommitSink<T, Txn>` 与 runtime 的 `TwoPhaseCommitCoordinator` 按 `beginTxn -> invoke -> preCommit -> storeCheckpoint(txn 句柄) -> commit -> markSinkCommitted -> ack` 运行。
- 恢复补偿已实现：restore 后按留存句柄回放 `recoverAndCommit`。
- 故障注入覆盖：`TwoPhaseCommitFaultInjectionTest`（11 例，含 commit 抛错、store 后 commit 前崩溃、store 失败）、`TwoPhaseCommitRecoveryCompensationTest`（4 例）；真 Redis 重启恢复的集成测试尚未提供。

### v3：扩展到更多 sink（JDBC/Kafka/HTTP）

- JDBC：支持 XA/本地事务 + exactly-once（取决于目标库能力）
- Kafka：如果 sink 是 Kafka，需要与 Kafka producer transactions 结合；但“source=Redis Streams”时仍是跨系统边界，通常仍需 outbox/2PC 或业务幂等。
- HTTP：通常只能做“幂等请求 + 重试 + 去重 token”，很难做严格 exactly-once。

## 与当前配置/运维的关系

- `deferAckUntilCheckpoint=true` 时，消息会在 Redis Streams pending list 中停留更久：
  - `mq.claimIdleMs`（`MqOptions.claimIdleMs`，默认 `300000` 即 5 分钟；Builder 把 ≤0 钳为 1）必须大于 checkpoint 周期（interval + drain），否则 pending 可能被 claim 导致重复处理。Redis runtime 启动时校验该关系，不满足则打 warn 日志（`RedisStreamExecutionEnvironment` 中 `interval + drain >= claimIdleMs` 的分支）。
- exactly-once 的工程落地需要：
  - 明确 checkpoint 周期与故障恢复策略
  - 明确 sink 幂等键的生命周期（TTL、存储成本、回收）
  - 为故障窗口增加可观测性（metrics + structured logs）
