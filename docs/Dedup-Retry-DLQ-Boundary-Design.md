# 重试 / 死信 / 去重职责划界

本文是**决策记录**（无代码改动）。仓库里"重试""死信""去重"三个概念各出现两到三处，且互不依赖；本文明确三层的归属与交接契约，供后续实现按此收敛。

## 现状（事实盘点）

三层设施**互不接线**：`mq` 与 `runtime` 的 main 代码对 `reliability` 零 import（grep 证实）；跨层引用只存在于 `spring-boot-starter`（`ReliabilityMetrics`/`RateLimitMetrics` 到 Micrometer 的桥接与配置类）与 `examples`（ratelimit 用例），且都只碰 `reliability.metrics`/`reliability.ratelimit`，不碰重试与死信。即两套重试/死信抽象并行存在、各自为政。

| 设施 | 位置 | 形态 | 失败语义 |
| --- | --- | --- | --- |
| 投递重试 | `mq.retry.RetryPolicy`（接口：`getMaxAttempts` / `nextBackoffMs`）+ `ExponentialBackoffRetryPolicy` | 投递层重试决策，配 lease/pending 接管 | 未达上限重投，达上限转死信 |
| 投递死信 | `mq.dlq` 包（`DeadLetterService` / `RedisDeadLetterService` / `DeadLetterConsumer` / `ReplayHandler` / `DeadLetterAdmin`）+ `DeadLetterQueueManager` | Redis stream 持久化 | 写成功才 ack；**写失败则保持 PEL 未 ack 等待重投（不丢事件）** |
| 用户侧重试 | `reliability.RetryExecutor` + `RetryPolicy`（POJO 配置：maxAttempts/initialDelay/backoff） | 对 `Function` 的 JVM 内同步重试 | 用尽后抛给调用方 |
| 用户侧死信 | `reliability.DeadLetterQueue`（`ConcurrentLinkedQueue` + `FailedElement`，`Serializable`，maxSize 封顶） | **进程内内存** | 崩溃即丢；仅适合 JVM 内工具链 |
| 用户侧去重 | `reliability.Deduplicator`：`SetDeduplicator`（Redis `RSet`）/ `BloomFilterDeduplicator`（Redis `RBloomFilter`）/ `WindowedDeduplicator`（Redisson + 窗口） | 可跨进程（Redis 后端）或窗口内 | 由调用方按返回处理 |
| Sink 幂等 | `runtime.redis.sink.RedisIdempotentListSink`（`dedupSetKey` + `dedupTtl`）/ `RedisCheckpointedIdempotentListSink`（记录缓冲随 checkpoint 刷出） | Sink 端恰好一次输出 | 重放幂等；缓冲随 checkpoint 原子落 |
| ACK 控制 | `MqHeaders.DEFER_ACK` | 消费端延迟确认 | 与重试/死信正交 |

## 决策：三层职责

1. **投递层（mq）唯一负责"消息没消费成功怎么办"**：重试（lease/pending 接管 + 退避）、死信持久化、重放。**不做内容去重**——它是传输层，看不懂业务语义。
2. **用户/算子层（reliability）负责"业务逻辑重试与内容去重"**：`RetryExecutor` 包用户函数、`Deduplicator` 按业务键去重。**不接管 MQ 投递失败**——投递失败的语义（PEL、replay、DLQ 持久化）必须留在 mq。
3. **Sink 端（runtime）负责"至少一次投递 → 恰好一次效果"**：用 `dedupSetKey` 幂等键 + checkpoint 同步刷出，重放不产生重复输出。**Sink 内部不再叠 `Deduplicator`**（幂等键本身就是 sink 层的去重）。

交接契约（现有代码已遵守，记录为不变量）：

- MQ 消费结果三态：成功 ack；`RETRY` 未达上限重投；`FAIL`/`DEAD_LETTER` 转死信**且仅写成功才 ack**——DLQ 写失败保持 PEL 未 ack 等待重投，保证不丢事件。
- 用户侧 `DeadLetterQueue` 仅用于 JVM 内工具链（例如离线重放、批处理辅助），**不得作为 MQ 消费失败的处理出口**：崩溃会丢事件。
- 需要跨 JVM 的去重（多实例消费同一 topic 时去重业务键）→ 用 `SetDeduplicator`/`BloomFilterDeduplicator` 的 Redis 后端，不用 `WindowedDeduplicator` 或进程内 `DeadLetterQueue`。

## 反模式（不要做）

- 在 MQ 消费循环里调 `RetryExecutor` 包业务函数再自己管 PEL：PEL/replay 语义必须只有一份（mq 的）。
- 把 `reliability.DeadLetterQueue` 接进 MQ 投递路径：内存队列与持久化 DLQ 语义冲突。
- Sink 端同时挂 `Deduplicator` 与幂等 sink：两层去重键不同源，反而制造重复或漏判。
- 用 `reliability.RetryPolicy`（配置 POJO）驱动 mq 投递重试：两者是不同类型，mq 侧只认 `mq.retry.RetryPolicy` 接口。

## 已知债（不在本批处理）

- **`RetryPolicy` 同名不同型**：`mq.retry.RetryPolicy`（接口）与 `reliability.RetryPolicy`（配置 POJO）同名，检索与阅读易混。建议 v2 把 mq 侧改名为 `DeliveryRetryPolicy`——破坏性重命名，与"指标体系统一"同一 major 周期处理（见 [Metrics-Unification-Design.md](docs/Metrics-Unification-Design.md)）。
- `reliability` 的 `RetryPolicy` 是 `@Builder` 配置而 mq 侧是接口，统一为"接口 + 配置"同型需要 API 评审，本批不动。

## 非目标

- 本批不合并/不删除任何重试或死信类（保留向后兼容）。
- 不引入新的重试调度组件（背压式重试调度属多租户配额设计范畴，见 [Multi-Tenancy-Design.md](docs/Multi-Tenancy-Design.md)）。
- 不改变 DLQ 写失败的"不 ack 等重投"语义。
