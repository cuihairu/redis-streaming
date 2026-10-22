# SourceContext.getCheckpointLock 真实接入与检查点屏障协议边界

本文供评审,不含代码改动。目标:`SourceContext.getCheckpointLock()` 目前是死契约(唯一生产实现是引擎自己,无任何快照方在这把锁上同步);厘清两引擎检查点模型,给锁语义真实化的最小路径,并论证 Flink 式跨算子屏障协议在 Redis 引擎上的真实收益边界。

> 落地状态:P1 快照屏障已落地 2026-10-11、P2 注册面泛化已落地 2026-10-12、逐记录屏障已落地 2026-10-13、窗口累加器注册已落地 2026-10-14(见「方案 A」①②与「落地记录」);A③ Redis unaligned v2 评审结论已出 2026-10-14(方向正确、不在本批实施,拆独立批次,见「方案 A」③ 评审结论),其前置第一步 CAS 门两级拆分已于同日落地(见「方案 A」③ 落地注记)。下文「现状」是落地前的事实盘点,与当前代码的差异以落地记录为准。

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
>
> **落地注记(2026-10-14,P1014B 批次)**:上述落地顺序的第一步已执行——原单一 `AtomicBoolean checkpointing` 重构为 `runtime/redis/internal/CheckpointGate` 两级门(serialization 级:同一时刻至多一个控制操作;full-silence 级:消费者暂停/在途静默)。对齐检查点(周期与手动触发)与 `scaleParallelism` 仍同取两级(先 serialization 后 full-silence,逆序释放,类不变量 `fullSilence ⇒ serialization` 保证无死锁),**行为与拆分前逐点等价**——原门的三类持有者本就两两互斥,拆分只是把「互斥」与「全静默」两个关注点显式化,给未来 unaligned 检查点留出「只取 serialization 级、不停消费」的入口。诊断面 `checkpointing` 键改为 serialization 视图并新增 `fullSilence` 键。门晶格由 `CheckpointGateTest` 4 测试钉住:检查点/扩缩容互斥、二次 begin 拒绝、拒绝的 begin 不滞留 serialization 级(防卡死不变量)、4 线程 2000 次争用零重叠。第二步评估结论见下节「epoch 对齐与 frontier 去重评估」。

#### epoch 对齐与 frontier 去重评估(2026-10-14,P1014B 批次)

代码走读(以 `runtime/.../redis/RedisStreamExecutionEnvironment`、`.../internal/RedisRuntimeCheckpointManager`、`mq/.../impl/RedisMessageConsumer` 为准)显示草图有四处前提与实际不符,先纠正:

1. **pause 与 drain 是尽力而为,且 drain 超时常照进行**。`RedisRuntimeCheckpointManager.triggerCheckpoint` 本身只有纯快照+存储,不含 pause/drain/2PC;编排全在 `RedisStreamExecutionEnvironment.triggerCheckpointWhilePaused`——先 pause **全部** consumer,再按 `checkpointDrainTimeout`(默认 30s)轮询 `Σ inFlightBarrier()==0`,**超时只 `log.warn` 然后照常继续**(不中止、不失败)。今日正确性依赖的不是"静默",而是 2PC abort/recover 路径兜底 drain 超时下的在途记录。
2. **快照原子性是单次 Lua 调用级,不是作业级**。全 pipeline 的 offsets 与 state 在一个 Lua 事务内写同一 checkpoint id 与同一 `timestamp`,但作业语义上并非单一一致切面:`stateKeys` 的 SCAN 发生在 Lua **之前且之外**,快照读到的是 Redis 现值(+`stateSchema` 字符串),不是"某一刻的算子状态"。
3. **「keyed state = 现值 + 快照版本号」不成立**。`meta` 只有 `jobName/jobInstanceId/stateKeyPrefix/sinkCommitted/fencingToken`,没有 per-partition/per-key 版本字段,「恢复时只重放 id > frontier」无处挂靠——恢复的 keyed state 是整键空间**拷贝覆盖**(staging 名 + 原子 `RENAME` 翻牌),无 delta、无 replay 语义。
4. **「offsets 复用既有 defer-ack 快照语义」只在 `deferAckUntilCheckpoint=true`(默认 false)下成立**。默认路径读的是 **commit frontier hash**(最大**已 ack** id),既不读 `last-delivered-id` 也不读 PEL;defer-ack 模式下存的是该模式下记录过的最大 id。

**两个待评问题的结论**

- **frontier 去重:今天已经存在——对齐路径的恢复语义本就是「回卷到 frontier 重放」(2026-10-15 勘误:本条初稿称「缺的只有两段管道」,与同一句已陈述的事实自相矛盾,系初稿笔误)**。这两段管道并非缺口,而是既有实现:快照已把 per-partition frontier **显式序列化**进 payload(`snapshotOffsets` 写 `SNAPSHOT_KEY_OFFSETS`,按 partition 存 Stream id,`(topic, cg, pid) → "ms-seq"`,defer-ack 最大 id 或 commit frontier),恢复也已读出来作为消费起点(`restoreFromCheckpoint → restoreOffsets` 用 `XGROUP DESTROY + XGROUP CREATE` 把 group 重建在 frontier 上,此后 `XREADGROUP >` 只投 `id > frontier`)。注意这**不是**消费者层的 `id <= frontier` 过滤(消费层无任何 id 过滤,跳是由 group 边界隐式完成的,且会一并丢弃 PEL);恢复 = 「快照状态 + 回卷到 frontier 重放」,其正确性前提是「effect ≤ frontier 的记录其效果已全在快照里」——对齐路径靠 fullSilence(尽力而为,见上文第 1 条)+ 2PC 兜底勉强满足;而这正是 unaligned 想回避的性质。
- **多并行度 epoch 对齐:当前快照结构下不可行,不是小改**。checkpoint id 是**每 env 实例**的 JVM `AtomicLong`(非 Redis 分配、非 per-subtask);快照是一次 Lua 全局写入,只能代表**一个全局时刻**;没有任何 per-partition effect fence 或随记录传播的 epoch。已存在的 `inFlightBarrier()`(`inFlight + readsInFlight`,已修过「在 pause 窗口内被调用」的竞态)是 consumer **本地**视图——不 per-partition、不可跨实例探测,不能替代跨 subtask 栅栏。要做到 unaligned 且正确,必须新增 **Redis 可见的 per-partition in-flight/effect fence**(每个 subtask 处理 partition p 前 incr、ack 决定后 decr,协调者跨全部 subtask 等 `inFlight(p)==0`),并把快照 Lua 拆成 per-partition。

**因此 A③ 草图的核心前提(「epoch 可对齐」)不成立**:今天没有 epoch 可对齐;在没有 per-partition effect fence 的前提下做 frontier 去重,会退化成「回卷重放」,恰好丢掉设计想避免暂停的那些跨切面记录(其效果落在快照之后、id 却在 frontier 之内,既无版本字段可判、也无 replay 可补)。

**建议(供下一批次决策)**

- unaligned 的最小一致点应重新定义为 **per-partition barrier + per-partition 快照切分 + 恢复期排他重放**,而非「preCommit + defer-ack freeze」。(2026-10-15 勘误:初稿此处称「frontier 三段可独立落地、不依赖 epoch 对齐、纯管道」——不成立。frontier 序列化与恢复期 seek 本就存在(见上文 frontier 去重条),不存在可独立落地的「frontier 三段」;「恢复期排他」依赖作业级全局门,即上文缺陷(2),也不是纯管道。唯一真正缺失的原语是 per-partition effect fence + 把快照 Lua 拆到 per-partition。)决策因此收敛为二选一:**设计并实现 fence**(独立设计批次,MQ 层新原语,与 2PC 交互为主要风险,须专项评审 + 集成测试),或**维持对齐检查点为唯一一致路径**。不存在不动 fence 的廉价中间路径。
- per-partition fence 是 MQ 层新原语(与 2PC 交互为主要风险),需独立设计;在此之前**对齐检查点仍是唯一一致路径**,unaligned 若要先行,只能定位为「允许 RPO > 0 的最佳努力」,须在 API 与文档上明示。
- 另记三个本批顺带发现的既有缺陷(不属 A③ 范畴,**未列入 todo**,供决定是否立项):(1) `triggerCheckpoint` 全程不调 `renewLeadership()`,leader 丢租约后仍可写快照→ 无 fencing 的孤儿快照;(2) 控制门是每 env 实例的而非作业级全局锁,同名 job 的两个 `LaunchedJobClient` 可并行打快照(leader election 只协调周期触发,不保护手动 `triggerCheckpointNow`);(3) `DeferredAcks` 存在一个无生产调用方的 drain-and-ack 便捷重载(`ackAll(client, keys)`),在冻结集之外无条件 ack,与「检查点已完成」无同步——若在检查点流程之外调用,会把下个 epoch 的 id 提前计入 commit frontier。**处置(2026-10-15,巡检代决,三项均立项)**:(1) 已修——store 前续租门:曾持租约但 renew 失败的实例拒绝写快照(返回 null、不落盘;从未持租约的 follower 手动路径保留,`RedisRuntimeCheckpointManagerLeadershipGateTest` 4 测试 + `RedisLeaderElectionIntegrationTest` 集成钉住);(3) 已修——删除无调用方的 drain-and-ack 重载,唯一 ack 面收窄为「冻结快照 + 检查点成功路径」(`ackAll(client, frozenSnapshot, keys)`,DeferredAcks 相关 3 个测试类同步);(2) 已修——作业级控制门落地：`runtime/redis/internal/JobControlGate` 两级 Redis 租约门（`ctl:excl`/`ctl:silence`，TTL 自愈 + Lua CAS 释放 + Redis 不可达拒绝），接入 `triggerCheckpointInternal` 与 `scaleParallelism`，开关随 `leaderElectionEnabled`（`jobControlGateEnabled` 可双向覆盖，`controlGateWait` 默认快速失败），`JobControlGateIntegrationTest` 集成钉住双客户端互斥/TTL 自愈/跨实例晶格（见 `docs/Job-Global-Control-Gate-Design.md` §7 落地记录）。**至此三项缺陷全部修复（2026-10-15）。**

**A③ 收口(2026-10-15,巡检代决,事后可推翻)**:选择**维持对齐检查点为唯一一致路径**,unaligned 不立项。理由:唯一缺失原语是 per-partition effect fence(正确性关键路径 + 2PC 交互风险),而收益面窄(仅长 drain timeout/高吞吐作业可感知)。`RedisRuntimeConfig` 的 unaligned 开关不再需要(无特性可开);P3(迁移路径)保持「未实施」。重启条件:生产负载出现真实 RPO 需求,且届时以 per-partition fence 设计批次重启,不复活本草图。

### 方案 B:两引擎全面对齐屏障(Flink 式)

算子异步快照 + barrier 随记录流传播。不可行/不划算:runtime 无算子队列(barrier 无处栖身,只能骑记录线程=回到 stop-the-world)、in-memory 状态是进程内可变堆(异步快照要 COW 全套 store)、Redis 状态快照本身廉价。工程量远超 ①+②,不推荐。

### 方案 C:删除 `getCheckpointLock`(契约收缩)

省掉一个误导性方法,但丢掉 ① 的天然挂点与用户侧锁习惯;`SourceContext` 是已发布 SPI 面,删除属破坏性变更且无对冲收益。不推荐。

## 迁移路径(方案 A 分期)

1. **P1 快照屏障**(已落地 2026-10-11,见方案 A① 的修正记录):协调器 `checkpointLock()` + 注册 store 绑定 + 快照/恢复持锁切面;3 个并发测试钉死,背压场景由既有流式测试(慢 sink/10 万条顺序)覆盖。
2. **P2 注册面泛化**(已落地 2026-10-12):WatermarkState 经 `registerSnapshotSource` 注册为快照源;窗口累加器已按算子状态生命周期契约注册(2026-10-14,见方案 A② 与 docs/InMemory-Operator-State-Lifecycle-Design.md)。
3. **P3 unaligned v2**:**已收口,不实施**(2026-10-15 巡检代决——草图「epoch 可对齐」前提不成立,维持对齐检查点为唯一一致路径;重启条件见上文 A③ 收口段)。两级 CAS 门已作为前置独立落地(2026-10-14,`CheckpointGate`)。
4. 文档同步(已落地 2026-10-11):`docs/checkpoint.md` 已补"in-memory 快照与流水线互斥"语义(双锁契约);`docs/Deployment.md:97` 的 stop-theWorld 记载在 P3 落地前保持不变。
