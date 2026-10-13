# 内存引擎算子状态生命周期与窗口累加器注册设计

本文供评审，不含代码改动。目标：为内存引擎定义算子状态生命周期与重启语义契约，使窗口累加器能作为快照源注册进检查点协调器（todo 首项），并从契约层面堵死「注册时惰性建表 + 恢复时重放会把陈旧桶注入新窗口」的正确性陷阱。

> 状态：设计已落稿 2026-10-13，待评审。评审通过前不实现。

## 背景

检查点协议 v1/v2 已落地：协调器 `InMemoryCheckpointCoordinator` 的 `checkpointLock()` 是单一一致切面，注册 store 的全部变更与快照/恢复都同步其上；逐记录屏障让 record body 整体在 store 的 `fenceLock()`（= 注册后的 `checkpointLock`）内执行。注册面已泛化为 `registerSnapshotSource(name, snapshot, restore)`，`WatermarkState` 已注册为 `watermark-N` 快照源。

窗口累加器（`InMemoryWindowedStream` 的桶表）是唯一还没注册的算子状态。todo 首项「内存引擎窗口累加器注册进检查点协调器」的依赖即本文。

## 现状事实盘点

1. **桶表是 `drive()` 的方法局部变量**：`drive()`（L264-315）每次调用新建 `Map<WindowKey<K>, Bucket<T, R>> buckets = new LinkedHashMap<>()`（L268），跑完整个输入后返回完整结果列表。终端算子（reduce/sum/count/aggregate/apply）的匿名迭代器在首次 `hasNext()` 时调用一次 `*All` 并缓存结果——**一次迭代 = 一次 drive() = 一张桶表**。
2. **一次 sink = 一次完整遍历**：`addSink`（L192-208）经 `iterator()`（L79-89）取 `recordIteratorSupplier.get()`，每个 sink 各取一个新迭代器。同一 DataStream 挂两个 sink = 两次独立 drive() = 两张独立桶表，各自完整发射——这是既有的「按消费者重放」语义。
3. **无协调器引用、无锁**：`InMemoryWindowedStream` 构造只接 `(keyedIteratorSupplier, windowAssigner, watermarkState)`（L64-75）；`InMemoryKeyedStream.window()`（L302-308）调用点持有 `checkpointCoordinator` 但未透传。桶表上没有任何同步。
4. **`R` 按终端算子泛型**：reduce/sum → T，count → Long，aggregate/apply → R。流级字段无法泛型化。
5. **end-of-input flush 不清理**：输入耗尽后对每个剩余桶调 `trigger.onEventTime(window.getEnd(), window)` + `fire(...)`（L308-313），**不 purge、不 remove**——桶留在表里。中途关闭路径（`closeReachedWindows` L322-344 / `closeReachedWindowsByProcessingTime` L353-377）在 FIRE_AND_PURGE/PURGE 时才 `it.remove()`。
6. **Trigger 无快照 API**：`WindowAssigner.Trigger` 只有 onElement/onEventTime/onProcessingTime，无 snapshot/restore。会话合并路径已有先例：合并桶保留第一个被吸收桶的 trigger 实例，其余 trigger 状态丢弃（L418-419「the trigger API has no merge callback」）。
7. **内存引擎的源是全量重放**：`Supplier<Iterator>` 每次 `get()` 从头迭代。没有 offset/position 概念，无法跳过已消费记录。

## 决策空间

### D1 生命周期归属：每次 drive() 一张桶表（推荐）vs 终端算子共享一张

- **每次 drive() 一张**：把桶表从 `drive()` 局部提升为「每次终端算子消费」的算子实例字段，`drive()` 接收该实例而非新建。语义与现状完全一致：每个 sink 一张表、各自完整发射、end-of-input flush 后表随实例一起不再被引用。
- **终端算子共享一张**：同一 DataStream 的多个 sink 共享桶表。要保住「每个 sink 完整发射」就必须 flush 后清表，但清表后崩溃/检查点看到的状态就没了；且并发消费同一 DataStream 在内存引擎里没有定义。

推荐 **D1-a：每次 drive() 一张**。理由：零语义变更（现状即如此），注册面只需在实例创建时挂一次。

### D2 注册时机：首次 drive() 时惰性注册（推荐）vs `window()` 调用时立即注册

- **惰性**：与 `WatermarkState` 同构——`assignTimestampsAndWatermarks` 的流水线段在持协调器时自动注册。窗口算子在首次 drive() 时创建实例并注册，未消费的终端算子不产生注册。
- **立即**：`window()` 调用即注册。即使终端算子从未被消费也会产生注册与快照条目，且恢复时无人认领。

推荐 **D2-a：惰性**。协调器的 `latestRestoredStateByStoreId` 延迟恢复语义对快照源同样生效——恢复发生在注册之前时，注册点取最近一次恢复的状态。

### D3 恢复语义：FRESH（默认）vs ADOPT（显式 opt-in）

这是本文的核心陷阱。内存引擎的源是全量重放：一次「重启」= 新协调器 + 同一源再跑一遍。若恢复时把陈旧桶注入新窗口（ADOPT），新 drive() 会把**整段历史**再累加进恢复的桶——同一记录被计两次。

- **FRESH（默认）**：恢复的窗口状态写入实例的「只读检查镜像」（`restoredView`），不注入活动桶表；drive() 从空表开始。恢复语义 = 可观测（检查点含窗口状态、可查询）+ 可审计，但不改变计算结果。
- **ADOPT（显式 opt-in）**：仅当源可重放后缀（带 position、能跳过已消费记录）时才有意义。内存引擎当前所有源都是全量重放，故 ADOPT 默认关闭；未来引入可恢复源时再开。

推荐 **D3-a：默认 FRESH，ADOPT 留接口不实现**。这样「注册进检查点协调器」的收益（一致切面、可观测、可审计）立即拿到，而正确性陷阱从契约层面堵死。

### D4 快照粒度：逐记录栅栏（推荐）vs 整段 drive() 栅栏

- **逐记录**：drive() 的循环体（assignWindows + 桶更新 + trigger 回调 + 中途关闭扫描）整体在 `fenceLock()` 内执行，与 keyed 路径的逐记录屏障同构。中途触发的检查点阻塞到当前记录写完，看到的是整记录效果。
- **整段**：整个 drive() 持栅栏。长流上检查点被阻塞整段运行时间，等于退化成 stop-the-world。

推荐 **D4-a：逐记录**。注意 drive() 循环体里 trigger 回调可能触发用户代码（onElement 里调 fire），栅栏持有时间包含用户 trigger 代码——与 keyed 路径持有用户 processElement 代码同性质，可接受。end-of-input flush 同样入栅栏（与 keyed 路径的 `drainAllTimers()` 同理）。

### D5 快照内容：桶内容拷贝，trigger 状态不拷贝

- 桶表拷贝：`LinkedHashMap` 浅拷贝 + 每桶的累加器值（`FoldState.acc` / `AggregateState.acc` / `SumState.total+sample` / `CountState.count`）与 `lastTimestamp`。raw 路径拷 `ArrayList`；增量路径的 `BucketState` 按引用拷贝——内存协调器存的是活引用，与 keyed store 的 HashMap 拷贝同性质。用户 `AggregateFunction.Accumulator` 对象不可深拷贝，契约上要求其在快照点之后不被原地修改（与 keyed store 的 value 对象同性质）。
- **trigger 实例不拷贝**：无快照 API。恢复的桶拿全新 `getDefaultTrigger()` 实例——trigger 状态（如 CountTrigger 的计数）跨恢复丢失。这与会话合并路径的既有边界一致（trigger 无 merge callback），契约里显式声明。

推荐 **D5-a**。

### D6 命名与注销

- 每次 drive() 一个实例，需要一个不撞名的注册名。沿用 `watermark-N` 的跳号模式：`window-N`，撞名跳号。
- 协调器当前**没有注销 API**。运行结束的实例状态留在后续检查点的切面里——这是可接受的：它是该次运行的最终状态，是正确的历史。不引入注销 API（避免「检查点切面里窗口状态消失」的语义混乱）。

推荐 **D6-a**。

## 推荐契约（汇总）

1. `InMemoryWindowedStream` 的终端算子返回的 DataStream 携带一个「每次消费创建」的算子实例（含桶表 + 注册名 + 只读 `restoredView`）。
2. 实例在首次 drive() 时创建并 `registerSnapshotSource("window-N", snapshot, restore)`；`window-N` 跳号避撞。
3. drive() 循环体与 end-of-input flush 整体在 `fenceLock()` 内执行（逐记录栅栏）。
4. `snapshot()`：栅栏内拷贝桶表（内容 + lastTimestamp），trigger 实例不拷贝。
5. `restore()`：默认 FRESH——写入只读 `restoredView`，不注入活动桶表；ADOPT 留接口不实现。
6. 无注销 API；已完成运行的窗口状态留在后续检查点切面中。

## 正确性论证

- **陷阱堵死**：FRESH 语义下恢复不注入活动桶，全量重放不会双计。
- **无死锁**：栅栏 = 注册后的 `checkpointLock`，与发射锁不相交（双锁契约）；`synchronized` 可重入，桶更新自身的同步（若有）在栅栏内空转。
- **无撕裂**：逐记录栅栏 + 栅栏内拷贝 = 检查点看到整记录效果，与 keyed 路径同构。
- **语义零变更**：每 sink 一张表、各自完整发射、end-of-input flush 不清理——全部保持现状。

## 落地步骤（评审通过后）

1. TDD：先写测试钉死「检查点切面含窗口桶内容」「恢复默认 FRESH 不注入」「逐记录栅栏阻塞中途检查点」「trigger 状态跨恢复丢失」。
2. 实现：`InMemoryWindowedStream` 透传协调器；终端算子实例化 + 惰性注册；drive() 逐记录栅栏；snapshot/restore。
3. 文档：`docs/checkpoint.md` 补窗口累加器注册语义；`Checkpoint-Barrier-Design.md` 方案 A② 卡点关闭。

## 非目标

- Redis 引擎的窗口状态（Redis 引擎有独立窗口路径，不在本文范围）。
- trigger 状态快照（无 API，留待 Trigger 接口演进）。
- 可重放后缀的内存源（ADOPT 的前提，留待源接口演进）。
- 注销 API。
