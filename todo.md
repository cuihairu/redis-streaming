# 待办清单

## 未完成

- [ ] Redis 引擎 unaligned checkpoint 方案 A③：评审结论已出 2026-10-14（方向正确、不在本批实施，拆独立批次、默认关，先做 CAS 门两级拆分再评 epoch 对齐与 frontier 去重，见 docs/Checkpoint-Barrier-Design.md 方案 A③ 评审结论）；实施待另立批次
- [ ] DataStream.join(other) 语法糖：union 原语（2026-10-14，11 测试）与内存侧糖 StreamJoinOperator.join（2026-10-14，7 测试）已落地，可跑示例 StreamJoinSugarExample + docs/Join.md 接入三步已交付；剩 Redis U3 三段编排（join topic 命名/trim/生命周期），架构卡点见 docs/Union-DualSource-Design.md §5（runtime 主依赖刻意不含 join、RedisStreamBuilder.map 返回 DataStream，糖落点三选一待评审）
- [ ] join/cep Phase 3：引擎级双源 pipeline、event-time 水位对齐、retract 语义（设计标注远期，与 P4 动态伸缩合并设计）
- [ ] 多租户 v2：线程/内存/in-flight 配额、指标 tenant 维度、registry/config/state/checkpoint/cdc/sink 前缀租户注入、租户级迁移与运维 CLI，设计见 docs/Multi-Tenancy-Design.md（配额与 CLI 无规格；指标 tenant 维度需破坏性改 StreamingMetricsCollector SPI）

## 已完成

### 测试覆盖率

- [x] 15 个零/低覆盖包单测补齐：cdc.impl、window、starter.autoconfigure、checkpoint、runtime.internal、source.kafka、cdc 接口层、source.redis、sink.kafka、mq.impl、mq.dlq、config.impl、registry.impl、mq.config、table.impl
- [x] 集成测试收口：cdc 8、starter 6、checkpoint 4、runtime 30、source 3、sink 2、mq 62、registry 30、config 16、table 6 个集成测试文件
- [x] 示例包测试化：examples 0% 至 93% 指令/83% 分支，新增 7 测试类 10 用例，Redis 不可达自动跳过
- [x] Kafka source/sink 集成测试接入 broker，无 broker 自动跳过，kafka 包排除出覆盖率门禁
- [x] 覆盖率水位：指令 99.2%、分支 90.9%；mq 96%、registry 98%、cdc 99%（2026-10-11 并集口径）
- [x] 门禁口径：单测 INSTRUCTION 不低于 0.95 且 CLASS 不低于 0.99（2026-10-05 起）
- [x] CI 集成测试通过率 100%，全量门禁绿（2026-10-11）

### Runtime 企业级能力

- [x] KeyedStream map/reduce/sum 与失败策略 RETRY/DEAD_LETTER/FAIL、poison message 直入 DLQ
- [x] 作业生命周期 executeAsync/cancel 幂等、启动失败清理；RedisJobClient pause/resume/inFlight/diagnostics
- [x] 状态 key 稳定化（显式 sourceId）、按 partition 隔离、TTL、大小上报、热点 key 治理
- [x] 端到端 checkpoint：offset+state 快照、周期 checkpoint、drain、restore、sink 去重、CheckpointAwareSink defer-ack
- [x] Exactly-once v1 幂等 sink：IdempotentRecord 与 RedisIdempotentListSink
- [x] Exactly-once v2 两阶段提交 sink：TwoPhaseCommitSink、协调器、故障注入与集成测试（commits 31d1579、5817958）
- [x] Exactly-once v2.5 Outbox/WAL：RedisOutboxSink 与异步投递器，28 用例
- [x] Exactly-once v3 Redis-only 原子提交：RedisCheckpointedIdempotentListSink 与 RedisAtomicCheckpointListSink
- [x] 状态 schema 演进：StateDescriptor.schemaVersion 与 stateSchemaMismatchPolicy FAIL/CLEAR/IGNORE，迁移对照表见 docs/state.md
- [x] 并行度模型 pipelineParallelism、背压 maxInFlight、分区 lease 上限、timer/checkpoint 线程隔离
- [x] 窗口运行时：watermark 乱序容忍、allowedLateness、windowMaxFiresPerRecord
- [x] 可观测：Micrometer 全链路指标、MDC 日志采样、diagnostics、Actuator health 与 prometheus 导出
- [x] leader election 与 fencing token：RedisRuntimeConfig.leaderElectionEnabled，默认 false
- [x] 作业 HA 接管：checkpoint id 对齐 storage 防覆盖，故障注入集成测试钉住
- [x] 动态伸缩：updatePartitionAssignment 再均衡、scaleParallelism、checkpoint 向前兼容，1 到 3 到 2 三轮伸缩计数守恒
- [x] 控制面 v1.0：submit/upgrade/rollback/audit 与 Redis 布局，26 单测 + 6 集成
- [x] 控制面 v1.1：JobAgent 对账与 JobLauncher，17+4 单测 + 9 集成
- [x] 控制面 v1.2：starter 自动装配两级开关，5 单测
- [x] 多租户 v1：StreamKeys 租户布局、SendQuota、控制面租户分段、TenantQuotaPolicy（commits a8f8a18、fe73aa5、290e020）
- [x] 安全 v1：Redis ACL username、env 惰性凭证、ConfigCustomizer SPI，7 单测
- [x] secret 引用：JobSpec secret.* 运行期解析与审计脱敏（SecretRefs）

### 架构评审重构

- [x] 仓库清理：移除入库 node_modules 与垃圾文件，报告类文档移入 docs/archive
- [x] 版本统一：libs.versions.toml、slf4j 2.0.17、Redisson 3.52 至 4.7 API 迁移（至 commit a841acf）
- [x] StreamSink/StreamSource open/close 生命周期与快照屏障，3 并发测试
- [x] 吞异常治理、NumberAggregationUtils 去重、executeAsync 拆分 400 至 125 行
- [x] RedisStreamSink 改真 XADD；CDCSource 接入主 API；MessagingProtocol 删除无实现常量
- [x] RedisStreamBuilder 窗口方法模板化 1123 至 1008 行（commit 7f2550a）；RedisMessageConsumer 双路径合并 1140 至 1082 行（commit 0188d31）
- [x] starter @AutoConfiguration 拆分（commit c84c5f5）；首次真实启动修复 4 个 bug
- [x] 内存引擎无界源流式化、增量窗口、watermark 中途关窗（commit 07539c7，15 测试）
- [x] Redis 引擎接入 WatermarkGenerator 与 window 模块 Trigger，5 单测 + 1 集成
- [x] 状态统一三步：水位线收敛（0d1079b）、keyed store 补齐（ee00fe8）、RedisStateBackend 适配层（3618c37、1dea93a）
- [x] aggregation 统一 v1+v2：incremental 四实现，删除第三套批式抽象
- [x] join/cep 算子化 Phase 1+2：Envelope、StreamJoinOperator、PatternSequenceProcessFunction 与 keyed state 缓冲
- [x] KTable changelog：withChangelog 与 toStream(env) 全历史重放，12 单测 + 2 集成
- [x] metrics 统一 v1+v2：DLQ 重放指标、CDCMetricsMicrometerBinder、删 metrics 模块、core StreamingMetricsCollector SPI（commits 5630fef、6f246ff、8ba68a1、ec6b60e、9c99494）
- [x] 去重/重试/DLQ 职责划界；mq RetryPolicy 改名 DeliveryRetryPolicy（commit 2ff417c）
- [x] registry 瘦身：删 client/、metrics/ APM、WebSocketHealthChecker，ServiceRegistry/ServiceDiscovery 合并（commits 46288b9、c82dc40）
- [x] config 接口收敛：ConfigService 唯一接口，删 ConfigManager/ConfigCenter/RedisConfigCenter
- [x] CDC 拉取模型修复：调度器交付 listener，snapshot 语义对齐
- [x] 双引擎公共抽象：决策不定义，公开面交集仅 3 方法（2026-10-11 拍板）

### 检查点协议与窗口运行时

- [x] 内存引擎窗口累加器注册进检查点协调器：每次消费一份算子状态 + 惰性注册 window-N + 逐记录栅栏 + 默认 FRESH 恢复（2026-10-14，3 测试）
- [x] 两引擎接入 processing-time 窗口定时器，Trigger.onProcessingTime 已接线（commit 258b694，19 测试）
- [x] 检查点协议 v2：逐记录屏障，消除单记录多次状态写被快照劈开（commit 431572b，1 测试）
- [x] 安全 v2 收尾：日志脱敏护栏 LogRedaction 落地，审计与 specHash 脱敏已落地（commit d639ad4，8 测试）
