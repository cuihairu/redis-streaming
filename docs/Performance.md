# 性能与调优（Performance）

## 分区与吞吐
- P 个分区可近似线性提升吞吐（取决于 CPU/实例与 Redis 容量）；单分区内串行保证顺序
- Redis Cluster 下，不同分区键分散到不同 slot，有利于水平扩展
- 热点隔离：热点 key 只堵在其分区，不影响其他分区

## 建议设置
- Producer：必要时批量 `XADD`/pipeline；谨慎控制消息大小
- Consumer：`COUNT > 1`，`BLOCK 100~500ms`；限制单 worker in-flight 条数（如 100~1000）
- 重试：指数退避 + 抖动，避免重试风暴；使用 ZSET + Lua 搬运（已默认）
- 保留：`XTRIM MAXLEN ~ N` 控制内存；结合时间边界（MINID）清理

## Runtime 调优要点（Redis runtime）
- 并行度：`RedisRuntimeConfig.pipelineParallelism(n)`（单进程子任务）+ 多实例（同 consumer group）水平扩展
- 背压：`MqOptions.maxInFlight(n)`（全局并发上限）+ `workerThreads`（执行线程数）
- 线程资源：`timerThreads`（processing-time timers）/`checkpointThreads`（checkpoint 调度/执行）
- 队列容量：`eventTimeTimerMaxSize`（event-time timer 队列上限，防止无界增长）
- Window：`windowMaxFiresPerRecord`（每条消息最多 fire N 个窗口，避免单条消息拖垮延迟）
- Watermark：`watermarkOutOfOrderness`（乱序容忍，影响窗口触发延迟与迟到判定）

## 压测建议
- 使用接近真实的 payload；分别测 P、batchSize、并行度的影响
- 关注指标：生产/消费速率、p99 处理延迟、DLQ 速率、Redis CPU/内存/网络

## 取舍
- 分区越多并行越强，但总 PEL/worker 也增加；结合硬件与负载选择合适 P
- 批越大吞吐越高，但单次延迟与内存占用也会提高

更多原理与实现细节见 `MQ-Design.md` 与 `MQ-Broker-Interaction.md`。
