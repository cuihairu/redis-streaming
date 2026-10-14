# 作业级控制门设计（缺陷 (2) 立项，2026-10-15 巡检代决；仅设计、未实施）

状态：**设计稿，待评审后另批实施**。本设计回应 `docs/Checkpoint-Barrier-Design.md` 评审中顺带发现的缺陷 (2)——控制门是每 env 实例的而非作业级全局锁。

## 1. 问题

`runtime/redis/internal/CheckpointGate`（2026-10-14 两级拆分）是 **JVM 内的 `AtomicBoolean`**：它的互斥范围是一个 `RedisStreamExecutionEnvironment` 实例内的控制操作（对齐检查点、扩缩容）。跨实例没有约束：

- leader election 只协调**周期触发**（`leaderRenewExecutor` 决定谁开周期检查点），不保护手动 `triggerCheckpointNow()`，也不保护 `scaleParallelism()`；
- 因此同名 job 的两个 `LaunchedJobClient`（HA 接管交叠、运维工具另起客户端、控制面双 agent 等）可以**同时**各跑一个对齐检查点。两个实例各自 pause 各自的消费者、各自 drain、各自快照入库：
  - **撕裂快照**：实例 A 的消费者在实例 B 的快照窗口内照常处理记录（A 看不见 B 的 full-silence）——B 的 offsets+state 快照覆盖的不是一个全作业静默切面；
  - **乱序入库**：两个写者各自 `allocateCheckpointId`，先启后至时大 id 未必后写；follower 手动检查点本就是被设计的合法路径（携带共享 epoch token），restore 侧的 max-fencing-token 挑选**无法**区分两个并发写者（token 相同）；
  - 2026-10-15 已落地的 store 前续租门（缺陷 (1) 修复）只拦「**曾持租约但租约已丢**」的写者，不拦两个**同时合法**的写者。

## 2. 目标与非目标

**目标**：把 `CheckpointGate` 已有的两级互斥（serialization 级 + full-silence 级）扩展到**作业全局**（Redis 命名空间），使得同一 jobName 任意时刻至多一个控制操作在任意实例上进行。

**非目标**：
- 不替换 `CheckpointGate`——它保留为进程内层（跨实例门是它的外层，两级取法嵌套顺序不变）；
- 不重设计 leader election——跨实例门与它正交：leader 协调「谁开周期检查点」，跨实例门保证「任一时刻至多一个控制操作」；
- 不改变 follower 手动检查点的合法地位（`RedisLeaderElectionIntegrationTest` 钉住的路径）。

## 3. 可选方案

### 方案 A：每作业 Redis 租约门（推荐）

控制操作 begin 时原子竞争两把**带 TTL 的租约锁**，end 时 Lua compare-and-release：

- 键：`<stateKeyPrefix>:<jobName>:ctl:excl` 与 `ctl:silence`，值为 `jobInstanceId:opId`；
- 获取顺序与 `CheckpointGate` 一致：先 `excl` 后 `silence`，逆序释放；类不变量 `silence ⇒ excl` 跨实例同样成立，故无死锁；
- TTL 取 `checkpointDrainTimeout + 快照余量`（同一量级，实现批细化），持锁期间由续租线程保活（与 leader 租约同法）；持锁者崩溃由 TTL 过期自愈；
- Redis 不可达 → 拒绝控制操作（fail-safe：宁无检查点，不做撕裂检查点）；
- 与 store 前续租门（缺陷 (1)）叠加为纵深防御：跨实例门管「同时合法」的并发，续租门管「租约已丢」的写者。

### 方案 B：storage 侧 fencing-token 严格化

让 `storeCheckpoint` 校验 token == 当前 fence 计数器顶且 leader 键持有者为写者。**否**：follower 手动检查点设计上就携带共享 epoch token（非任期 token），B 会把它一起拦掉，与 §2 非目标冲突；且 token 是 per-epoch 非 per-write，不能表达「本次写仍在本切面内」。

### 方案 C：follower 禁止手动触发

`triggerCheckpointNow` 在 `isLeader()==false` 时拒绝。**否**：只削写者数量，不消交叠窗口（旧 leader 收尾时新 leader 已开始）；且直接废除被测试钉住的 follower 手动路径。不采用。

## 4. 与既有控制面的交互

- **2PC**：跨实例门必须覆盖 `onCheckpointStart → prepareTwoPhaseCommits → storeCheckpoint → commitTwoPhaseCommits → markSinkCommitted` 全程（即 exclusive 租约持到 phase 2 完成），否则两个实例可能对同一 sink 并发 preCommit/commit；
- **defer-ack**：`drainForAck` 冻结与 `ackAll` 必须在同一把 exclusive 租约内（今日单实例内已由 `triggerCheckpointWhilePaused` 的顺序保证，跨实例后同样成立）；
- **`refreshCheckpointIdFromStorage`**：跨实例门消除「两个写者分配交错 id」的主要来源，接管对齐逻辑保持不变。

## 5. 风险

| 风险 | 缓解 |
| --- | --- |
| 控制操作超过 TTL，锁被他人夺走 | 持锁续租线程 + TTL ≈ drain 超时的倍数；夺走方 begin 前置校验（旧持有者 instance 已不在 leader 键上才可抢） |
| 持锁者崩溃滞留 | TTL 过期自愈（恢复路径本就容忍 TTL 级滞后） |
| Redis 不可达 | 拒绝控制操作；检查点在 outage 下本就失败，无新增故障面 |
| 死锁 | 两级顺序获取 + 逆序释放，`silence ⇒ excl` 不变量跨实例保持；`CheckpointGateTest` 的晶格测试模式在实施批复制为跨实例版 |

## 6. 测试计划（实施批次，先补测试再实现）

1. **双客户端集成**：同 jobName 两个 `LaunchedJobClient`，A 的 `triggerCheckpointNow()` 进行中，B 的并发触发必须等待/快速失败（语义：新增 `controlGateWait` 配置，默认快速失败并给出明确诊断）；
2. **扩缩容互斥**：A 的 `scaleParallelism` 进行中，B 的检查点与 B 的扩缩容都被串行化；
3. **TTL 自愈**：持锁实例被 kill（不发 end），第二个客户端在 TTL 过期后成功 begin；
4. **Redis 不可达**：begin 拒绝，作业数据面不受影响；
5. **晶格不变量**：跨实例版争用测试（多线程/多客户端混合 begin，零重叠）。

## 7. 落地顺序（实施批，未排期）

(i) Redis 租约门原语 + 单测；(ii) 接入 `triggerCheckpointWhilePaused` 与 `scaleParallelism` 的 begin/end；(iii) §6 的双客户端集成；(iv) 文档同步（checkpoint.md、本仓库 CLAUDE.md 的运维语义如需）。开关策略（随 leaderElectionEnabled 自动启用 / 独立 `jobControlGateEnabled`）在实施批与 §6 测试一并定。
