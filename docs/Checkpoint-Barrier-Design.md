# SourceContext.getCheckpointLock 真实接入与检查点屏障协议边界

本文供评审,不含代码改动。目标:`SourceContext.getCheckpointLock()` 目前是死契约(唯一生产实现是引擎自己,无任何快照方在这把锁上同步);厘清两引擎检查点模型,给锁语义真实化的最小路径,并论证 Flink 式跨算子屏障协议在 Redis 引擎上的真实收益边界。

> 落地状态:P1 快照屏障已落地 2026-10-11、P2 注册面泛化已落地 2026-10-12、逐记录屏障已落地 2026-10-13、窗口累加器注册已落地 2026-10-14(见「方案 A」①②与「落地记录」);A③ Redis unaligned v2 评审结论已出 2026-10-14(方向正确、不在本批实施,拆独立批次,见「方案 A」③ 评审结论)。下文「现状」是落地前的事实盘点,与当前代码的差异以落地记录为准。

## 现状(事实盘点)

### SourceContext:契约与实现

- 契约:`core/.../api/stream/StreamSource.java` L57-82 嵌套接口——`collect/collectWithTimestamp/getCheckpointLock/isStopped`。
- 唯一生产实现:in-memory 引擎 `runtime/.../StreamExecutionEnvironment.addSource`(L89-125)。`emit`(L226-240)在该锁内 `queue.put`(07539c7 起);消费线程从不取锁(防背压死锁)。
- **没有任何快照方在这把锁上同步**:`InMemoryCheckpointCoordinator.triggerCheckpoint`(internal,单监视器 `synchronized`)直接复制已注册 store 的快照,不取 source 锁;Redis 引擎根本没有 SourceContext——其"源"是 MQ 消费组(`fromMqTopic*`),记录经 MQ 消费者线程进 `RedisPipelineRunner.handle`。
- 5 个 `StreamSource` 实现(CDCSource/CollectionSource/FileSource/GeneratorSource/RedisStreamSource)没有一个调用 `getCheckpointLock()`;除测试外全仓库无调用方。

### 两引擎检查点模型

| 维度 | in-memory 引擎 | Redis 引擎 |
| --- | --- | --- |
| 协调器 | `InMemoryCheckpointCoordinator`(仅注册 `InMemoryKeyedStateStore`) | `RedisRuntimeCheckpointManager`(直用 `RedisCheckpointStorage`,不经 `RedisCheckpointCoordinator`) |
| 触发 | 用户手动 `triggerCheckpoint()`,无调度 | `checkpointInterval` 周期(仅 leader)/`triggerCheckpointNow()` |
| 静默方式 | **无**——快照与流水线线程竞跑 | stop-the-world:pause 全部消费者 → drain 至 `inFlightBarrier()==0`(默认 30s 上限) |
| 快照内容 | 已注册 store 的 `HashMap` 拷贝 | offsets(commit frontier 或 defer-ack 覆盖)+ stateKeys 索引内全部 Redis 键现值 + schema + 2PC 句柄 |
| 一致性 | **撕裂风险**:消费线程在算子内更新 `InMemoryKeyedStateStore` 时(不持任何锁)第三线程快照会看到中间态(CME/脏读) | pause+drain 保证无 fetched-but-unaccounted 记录;defer-ack freeze + 2PC preCommit/commit/markSinkCommitted 全链路已验证 |

`checkpoint` 模块的 `RedisCheckpointCoordinator` 是 **ack 计数**协调(pending 表进程内,凑满 `requiredTaskAcks` 自动 complete),runtime 两侧都不用它;全仓库唯一"barrier"概念是 MQ 层 `inFlightBarrier()`(消费侧在途视图,服务 pause+drain,不是数据流屏障)。`docs/Deployment.md:97` 已如实记载:"checkpoint 为单进程 stop-the-world(不跨实例 barrier)"。

## 问题

- `getCheckpointLock()` 是死契约:引擎造锁、无人用锁。用户按 Flink 习惯取锁自旋保护外部资源时得不到任何保证;文档也不得不说"该锁目前只护入队"。
- in-memory 检查点真撕裂:`triggerCheckpoint` 与消费线程的算子状态更新无 happens-before。当前它只能配合"流水线已停"使用,这一前提无任何机制保证,API 形状在邀请误用。
- Redis 引擎 stop-the-world 代价:检查点期间吞吐归零,pause 轮询粒度 50ms 起;`checkpointInterval` 调小则延迟放大,调大则 RPO 变差。defer-ack/2PC 缓解的是正确性,不是静默成本。
- 屏障协议的真实约束:Redis 引擎算子间**无队列**(`RedisOperatorNode.process` 单线程链),记录线上"对齐屏障"与 pause+drain 在单并行度下数学等价——屏障在这里能买的不是对齐,是**不停消费**。

## 可选方案

### 方案 A:锁语义真实化(小步)+ stop-the-world 保持默认 + unaligned 屏障作为 v2 可选(推荐)

理由:① 是在已验证模型上修真缺陷;② Redis 状态本就是外置现值(快照=拷贝,不是序列化算子堆),异步快照收益天然低,全面屏障工程量大收益小;③ v2 只在"消费停顿不可接受"场景才值得做。

**① in-memory 快照屏障(P1,已落地 2026-10-11)**:落地时修正了本文初版方案——「消费者逐条处理持锁」会引入死锁:用户按 Flink 习惯把 `collect()` 包在 `getCheckpointLock()` 里时,生产者可持该锁阻塞于满队列;若消费者的状态写入也要等同一把锁,队列永不腾空。真实屏障因此是**双锁契约**:协调器新增 `checkpointLock()`(独立监视器),注册 store 的全部变更(put/remove/setCurrentKey/snapshot/restore)与协调器的快照/恢复都同步在它上,`triggerCheckpoint` 跨全部 store 持锁 → 一次检查点是单一一致切面,撕裂(CME/脏拷贝)消除;该锁只在有界非阻塞内存操作上持有。`SourceContext.getCheckpointLock()` 保持返回源级发射锁(生产者可跨满队列阻塞持有,消费者绝不取)——两锁不相交,背压永不死锁。逐记录屏障已落地 2026-10-13:record body 整体在 store 的 `fenceLock()`(= 注册后的 `checkpointLock`)内执行,中途触发的检查点阻塞到记录写完所有状态,不再劈开同一记录的多次写入;`synchronized` 可重入,store 自身每次写的加锁在栅栏内空转。窗口累加器注册(②)后其整表快照天然原子。测试钉死:持锁写入期间快照必等且见写入值、并发写+连拍 200 快照零撕裂、活流水线旁路并发检查点 2 万条零丢失。

**② registerStore 泛化(P2,已落地 2026-10-12)**:注册面已从 `registerStore(InMemoryKeyedStateStore)` 泛化为 `registerSnapshotSource(name, snapshot, restore)`——任意 `(名称, snapshot, restore)` 三元组均可入检查点切面,与 keyed store 同受 ① 的锁保护;重复名拒绝,`latestRestoredStateByStoreId` 的延迟恢复语义对快照源同样生效。`WatermarkState` 补了 `Snapshot(watermark, idle)` 载荷与 `snapshot/restoreSnapshot`,`assignTimestampsAndWatermarks` 两个重载在持协调器的流水线段上自动以无冲突生成的 `watermark-N` 名注册(命名撞车时跳号)。**窗口累加器已注册 2026-10-14**(契约见 docs/InMemory-Operator-State-Lifecycle-Design.md,按推荐项 D1-a/D2-a/D3-a/D4-a/D5-a/D6-a 实现):每次终端算子消费持有一份算子状态——桶表从 drive() 局部变量提升为该实例的字段,消费开始时以无冲突生成的 `window-N` 名惰性注册(未消费的终端算子不注册);drive() 循环体与 end-of-input flush 整体在 `fenceLock()` 内(= 协调器的 checkpoint lock,与 keyed 路径逐记录屏障同契约;源迭代在栅栏外——阻塞源不得冻结检查点);快照载荷按桶记录 key/窗口边界/lastTimestamp/累积内容(增量路径的当前 fire 结果或 raw 元素拷贝),Trigger 无快照 API、实例不进快照;恢复默认 FRESH——载荷只写只读检查镜像、不注入活动桶,内存源全量重放,采纳恢复桶会双计(D3 的正确性陷阱从契约层面堵死);无注销 API,已完成运行的窗口状态留在后续检查点切面中(该次运行的最终状态)。

**③ Redis 引擎 v2 unaligned 检查点(评审结论已出 2026-10-14)**:不停消费,epoch 冻结点从"全量 drain"缩到"2PC preCommit + defer-ack freeze"(这两步本来就不需要 pause 全场)——offsets 用 defer-ack 快照语义已存在;keyed 状态取 Redis 现值 + 快照版本号,恢复时按"记录 id > 快照 frontier 才重放"去重(幂等 sink 键已具备)。必须先解决:与 `scaleParallelism` 共享的 `checkpointing` CAS 门要拆成两级(扩缩容仍需全静默,检查点不再需要);多并行度下 preCommit 的 epoch 对齐。风险集中在 2PC 交互,收益只在长 `checkpointDrainTimeout`/高吞吐作业,不默认启用。

> **评审结论(2026-10-14,巡检代决)**:方向正确但**不在本批实施**。草图自洽——preCommit + defer-ack freeze 是最小一致点,offsets 复用既有 defer-ack 快照语义,keyed 状态取 Redis 现值 + 版本号、按 frontier 去重重放均成立;但它是正确性关键路径且收益面窄(仅长 `checkpointDrainTimeout`/高吞吐作业可感知),前置有两项硬骨头:与 `scaleParallelism` 共享的 `checkpointing` CAS 门两级拆分、多并行度 preCommit epoch 对齐。**建议拆为独立批次、默认关**(`RedisRuntimeConfig` 新开关),落地顺序:先做 CAS 门两级拆分(独立可测,对既有对齐路径亦有收益),再评 epoch 对齐与 frontier 去重;2PC 交互为主要风险,需专项集成测试覆盖。本批不动代码。

### 方案 B:两引擎全面对齐屏障(Flink 式)

算子异步快照 + barrier 随记录流传播。不可行/不划算:runtime 无算子队列(barrier 无处栖身,只能骑记录线程=回到 stop-the-world)、in-memory 状态是进程内可变堆(异步快照要 COW 全套 store)、Redis 状态快照本身廉价。工程量远超 ①+②,不推荐。

### 方案 C:删除 `getCheckpointLock`(契约收缩)

省掉一个误导性方法,但丢掉 ① 的天然挂点与用户侧锁习惯;`SourceContext` 是已发布 SPI 面,删除属破坏性变更且无对冲收益。不推荐。

## 迁移路径(方案 A 分期)

1. **P1 快照屏障**(已落地 2026-10-11,见方案 A① 的修正记录):协调器 `checkpointLock()` + 注册 store 绑定 + 快照/恢复持锁切面;3 个并发测试钉死,背压场景由既有流式测试(慢 sink/10 万条顺序)覆盖。
2. **P2 注册面泛化**(已落地 2026-10-12):WatermarkState 经 `registerSnapshotSource` 注册为快照源;窗口累加器已按算子状态生命周期契约注册(2026-10-14,见方案 A② 与 docs/InMemory-Operator-State-Lifecycle-Design.md)。
3. **P3 unaligned v2**(独立评审):两级 CAS 门 + epoch 对齐 + frontier 重放去重,默认关,`RedisRuntimeConfig` 新开关;与 metrics 的 checkpoint 时延指标同批观察收益。
4. 文档同步(已落地 2026-10-11):`docs/checkpoint.md` 已补"in-memory 快照与流水线互斥"语义(双锁契约);`docs/Deployment.md:97` 的 stop-theWorld 记载在 P3 落地前保持不变。
