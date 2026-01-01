# Exactly-once 路线设计（Redis Runtime）

本文描述 `runtime`（尤其是 `runtime` 的 Redis runtime）要达到“端到端 exactly-once side effects”的路线与边界，给出可选方案与推荐的分阶段落地策略。

> 术语说明：这里的 exactly-once 指 **sink 侧效果（side effects）恰好一次**。在分布式系统里，“消息被消费恰好一次”往往不可达或成本极高；工程上通常追求“效果恰好一次”，实现方式是 **事务性提交** 或 **幂等写入**。

## 现状（当前实现能力）

Redis runtime 当前提供的是 “at-least-once + 更强对齐”的一致性基础：

- **处理语义**：默认 `SUCCESS` 后 ACK（at-least-once）。
- **端到端 checkpoint（实验）**：`deferAckUntilCheckpoint=true` 时，消息处理成功后不 ACK，直到 checkpoint 完成后再统一 ACK；checkpoint 元数据包含 `sinkCommitted`，恢复时会跳过未提交的 checkpoint。
- **sink 钩子（实验）**：`CheckpointAwareSink` 支持 `onCheckpointStart/onCheckpointComplete/onCheckpointAbort/onCheckpointRestore`，用于将 side effects 延迟到 checkpoint 完成点。
- **sink 去重（best-effort）**：`sinkDeduplicationEnabled=true` 基于 `x-original-message-id` 做“运行时去重”，降低重放/重试导致的重复写入概率，但不构成严格 exactly-once 保证。

这些能力使得“**checkpoint = (state + offsets) 的一致恢复点**”更可信，但仍未提供严格的 exactly-once side effects（因为 side effects 可能在 checkpoint 前/后非原子发生）。

## 约束与难点（为什么 exactly-once 很难）

1. **跨系统原子性不可得**：要 exactly-once，必须把“写 sink + 提交 source offset（或 ACK）”做成一个不可分割的原子动作；当 sink 在 Redis 之外（JDBC/Kafka/HTTP）时，天然跨系统。
2. **Redis Streams ACK 与外部事务不绑定**：Redis 的 `XACK` 无法与外部系统事务同一个事务域提交。
3. **故障窗口**：常见的“最难”窗口是：
   - checkpoint 已写入（state+offsets）但 sink commit 失败/未执行；
   - sink commit 已执行但 ACK 未执行；
   - ACK 已执行但 sink commit 未执行（这是必须避免的）。

因此，路线设计的核心就是：**在任何故障窗口里，都能通过恢复逻辑保证 side effects 最终恰好一次**（要么做到事务性 commit，要么保证幂等写入）。

## 可选方案（从易到难）

### 方案 A：幂等 sink（推荐作为 v1 基线）

**思路**：把“去重/幂等”下沉到 sink 的目标存储，通过唯一键/幂等写接口确保重复写入不会产生重复效果。

- **幂等键**：使用稳定的 event-id（理想），或 `mq.Message.id`/`x-original-message-id`（次选），或业务主键（最常用）。
- **实现方式示例**：
  - JDBC：`INSERT ... ON CONFLICT DO NOTHING` / `INSERT IGNORE` / 唯一索引 + upsert
  - Redis：Lua “check-and-set” 或 `SETNX`/`HSETNX` + TTL
  - Kafka：幂等 producer（注意：这保证的是 producer 侧幂等，不等价于端到端 exactly-once）

**与当前 runtime 的配合**：
- 继续使用 `deferAckUntilCheckpoint=true`（让 ACK 对齐到 checkpoint 完成点）。
- `CheckpointAwareSink` 将 side effects 延迟到 `onCheckpointComplete` 触发。
- `sinkDeduplicationEnabled` 可保留作为“运行时层面的额外保险”，但不能替代真正的幂等 sink。

**优点**：实现成本低，适配面广；可在不改动 runtime 核心协议的情况下逐步落地。  
**缺点**：exactly-once 依赖外部存储的幂等能力；对业务建模有要求（必须有唯一键）。

### 方案 B：Two-Phase Commit Sink（2PC，推荐作为 v2）

**思路**：提供类似 Flink 的两阶段提交 sink，将 checkpoint 作为事务边界：

1) `beginTransaction(checkpointId)`：开始一个 sink 事务（或“可提交但不可见”的写入会话）  
2) `preCommit()`：把本次 checkpoint 之前的 side effects 准备好（flush 到事务/缓冲区）  
3) **写 checkpoint（state+offsets+txn-handle）**：将事务句柄持久化进 checkpoint  
4) `commit()`：checkpoint 成功后提交 sink 事务；再将 checkpoint 标记 `sinkCommitted=true`；最后 ACK/推进 offsets

**需要的新 API（建议）**：
- 在 `core` 定义 `TwoPhaseCommitSink<T, Txn>`（或类似命名），核心方法：
  - `Txn beginTransaction(long checkpointId)`
  - `void invoke(T value, Txn txn)`
  - `void preCommit(Txn txn)`
  - `void commit(Txn txn)`
  - `void abort(Txn txn)`
  - `Txn recoverAndCommit(Txn serializedTxn)` / `Txn recoverAndAbort(...)`（恢复与补偿）
- Txn 需要可序列化（写入 checkpoint state snapshot）。

**恢复语义（关键）**：
- 恢复时读取最近的 `sinkCommitted=true` checkpoint：
  - 若存在“已写 checkpoint 但未 commit”的事务句柄：调用 `recoverAndCommit`（或 `abort`，取决于语义）来完成补偿。
  - 确保不会出现“ACK 已推进但 sink 未提交”的状态。

**优点**：在支持事务的 sink 上可获得严格 exactly-once side effects。  
**缺点**：实现复杂；每个 sink 都要实现事务协议；外部系统不支持事务时无法使用。

### 方案 C：Outbox / WAL（推荐作为 v2.5 或特定场景）

**思路**：把 side effects 写入一个“可恢复的 outbox”（例如 Redis Stream/Hash/表），outbox 写入与 checkpoint 同域（Redis 内部），然后由异步 dispatcher 把 outbox 投递到外部系统。

- **优势**：把“外部投递”变成可重试的后处理；checkpoint 只需要保证 outbox 记录不丢不重。
- **要求**：dispatcher 必须具备幂等投递能力（通常仍需要外部幂等或投递去重表）。
- **代价**：增加延迟与存储；系统变成“最终一致”。

### 方案 D：Redis 内部 exactly-once（单 Redis 实例/同槽位键）

**思路**：若 sink 也落在 Redis 中（并且 keys 可保证同一 Redis 实例/同 hash slot），可用 Lua 将：

- 写 sink（如 HSET/SET/Stream add）
- 更新 commit frontier（offset）
- 执行 `XACK`

打包成一个 Lua 脚本原子执行，获得“Redis 视角”的 exactly-once。

**限制**：
- Redis Cluster 下跨 slot key 无法在同一个 Lua 脚本原子执行（除非使用 hash tags 强制同槽位）。
- 这类 exactly-once 仅在“sink 也在 Redis 内”时才成立。
- 进一步增强（与 checkpoint 对齐）：可以将 sink side effects 延迟到 checkpoint 完成点，并在 checkpoint complete 之后统一提交（例如 `RedisCheckpointedIdempotentListSink` 这种 “commit-on-checkpoint” 模式）。
- Redis-only 原子提交（单 Lua）：可以在 checkpoint complete 时用 Lua 将 “写入 sink + XACK + 推进 commit frontier” 打包成单脚本原子执行（参考：`RedisAtomicCheckpointListSink` + `RedisExactlyOnceRecord`）。

## 推荐落地路线（分阶段）

### v1：Exactly-once by Idempotency（先上线、可运维）

- 明确对外语义：`deferAckUntilCheckpoint=true` + `CheckpointAwareSink` + “幂等 sink” = 端到端效果恰好一次（依赖 sink 端幂等）。
- 给出标准幂等键建议：优先业务主键；其次 `x-original-message-id`；需要 TTL/清理策略。
- 提供至少一个“幂等 sink 示例”（如 Redis idempotent writer 或 JDBC upsert sink）。
  - 参考实现：`core` 提供 `IdempotentRecord<T>`，`runtime` 提供 `RedisIdempotentListSink<T>`（Lua 原子去重 + RPUSH）。
  - 注意：在 Redis Cluster 下，Lua 脚本要求所有 KEYS 在同一 hash slot；建议对 dedup key 与 sink key 使用相同 hash tag（如 `xxx:{job}:seen`、`xxx:{job}:list`）。

### v2：Two-Phase Commit Sink（严格 exactly-once）

- 引入 `TwoPhaseCommitSink` API，并在 Redis runtime checkpoint 流程中实现：
  - `begin -> invoke -> preCommit -> storeCheckpoint(txnHandle) -> commit -> mark sinkCommitted -> ack`
- 增加恢复补偿：读取 checkpoint 中的 txnHandle 并 `recoverAndCommit`。
- 增加故障注入集成测试：
  - 在 `onCheckpointComplete/commit` 抛异常
  - 在 “checkpoint 已写入但 commit 未执行” 的窗口重启，验证不会丢/不会重复效果

### v3：扩展到更多 sink（JDBC/Kafka/HTTP）

- JDBC：支持 XA/本地事务 + exactly-once（取决于目标库能力）
- Kafka：如果 sink 是 Kafka，需要与 Kafka producer transactions 结合；但“source=Redis Streams”时仍是跨系统边界，通常仍需 outbox/2PC 或业务幂等。
- HTTP：通常只能做“幂等请求 + 重试 + 去重 token”，很难做严格 exactly-once。

## 与当前配置/运维的关系（重要）

- `deferAckUntilCheckpoint=true` 时，消息会在 Redis Streams pending list 中停留更久：
  - `mq.claimIdleMs` 必须大于 checkpoint 周期（interval + drain），否则 pending 可能被 claim 导致重复处理。
- exactly-once 的工程落地需要：
  - 明确 checkpoint 周期与故障恢复策略
  - 明确 sink 幂等键的生命周期（TTL、存储成本、回收）
  - 为故障窗口增加可观测性（metrics + structured logs）
