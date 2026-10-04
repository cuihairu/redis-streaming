# 性能与调优（Performance）

配置默认值取自源码（`RedisRuntimeConfig`、`MqOptions`）。

## 分区与吞吐
- P 个分区可近似线性提升吞吐（取决于 CPU/实例与 Redis 容量）；单分区内串行保证顺序
- Redis Cluster 下，不同分区键分散到不同 slot，有利于水平扩展
- 热点隔离：热点 key 只堵在其分区，不影响其他分区

## 建议设置
- Producer：必要时批量 `XADD`/pipeline；谨慎控制消息大小
- Consumer：`COUNT > 1`，`BLOCK 100~500ms`；限制单 worker in-flight 条数（如 100~1000，可用 `MqOptions.maxInFlight`，默认 `0` = 不限制）
- 重试：指数退避（基数 `retryBaseBackoffMs` 默认 1000ms，封顶 `retryMaxBackoffMs` 默认 60000ms，饱和计算不溢出）；延迟重试用 ZSET + Lua 搬运（已默认）。抖动（jitter）非内置，需调用方在策略外自行叠加
- 保留：`XTRIM MAXLEN ~ N` 控制内存（`retentionMaxLenPerPartition` 默认 100000，`trimIntervalSec` 默认 60s）；结合时间边界（`retentionMs`，默认 0 = 不启用）清理

## Runtime 调优要点（Redis runtime）
- 并行度：`RedisRuntimeConfig.pipelineParallelism(n)`（默认 1；单进程内按 `partitionId % parallelism` 固定分配子任务）+ 多实例（同 consumer group）水平扩展
- 背压：`MqOptions.maxInFlight(n)`（全局并发上限，默认 0 = 不限制）+ `workerThreads`（执行线程数，默认 8）
- 线程资源：`timerThreads`（processing-time timers，默认 1）/`checkpointThreads`（checkpoint 调度/执行，默认 1，单 job 内仍串行）
- 队列容量：`eventTimeTimerMaxSize`（event-time timer 队列上限，默认 100000，0 = 不限制，防止无界增长）
- Window：`windowMaxFiresPerRecord`（每条消息最多 fire N 个窗口，默认 256，避免单条消息拖垮延迟）；`windowAllowedLateness`（默认 `Duration.ZERO`，best-effort 迟到容忍）
- Watermark：`watermarkOutOfOrderness`（乱序容忍，默认 `Duration.ZERO`；watermark = 已见最大事件时间 − 该值，影响窗口触发延迟与迟到判定）

## 压测建议
- 使用接近真实的 payload；分别测 P、batchSize、并行度的影响
- 关注指标：生产/消费速率、p99 处理延迟、DLQ 速率、Redis CPU/内存/网络（指标清单见 [Metrics](/Metrics)）

## 取舍
- 分区越多并行越强，但总 PEL/worker 也增加；结合硬件与负载选择合适 P
- 批越大吞吐越高，但单次延迟与内存占用也会提高

更多原理与实现细节见 `MQ-Design.md` 与 `MQ-Broker-Interaction.md`。
