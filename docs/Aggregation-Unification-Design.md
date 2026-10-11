# 聚合体系统一设计（三套 → 一套）

本文供评审，不含代码改动。目标：把仓库里三套互不相干的"聚合/窗口"抽象收敛为一套，消除"同一概念三处定义、语义互不兼容"的状态。

## 现状（事实盘点）

| 位置 | 抽象 | 形态 | 语义 |
| --- | --- | --- | --- |
| `core` `api/stream/AggregateFunction<IN,OUT>` | 聚合函数 | 接口，4 方法 + 嵌套 `Accumulator`（`createAccumulator/add/getResult/merge`），`Serializable` | **增量**：逐条喂入，随窗口触发取结果；merge 支持跨分区合并 |
| `aggregation` `AggregationFunction<T,R>` | 聚合函数 | `@FunctionalInterface`，`apply(Collection<T>)` + default `getName()` | **批式**：一次性收齐集合再算；无法增量、无 merge |
| `window` `TimeWindow` | 时间窗 | class（`long start/end` 毫秒，`maxTimestamp()=end-1`、`contains`），实现 `WindowAssigner.Window` | 毫秒区间，与 runtime/window 引擎配合 |
| `aggregation` `TimeWindow` | 时间窗 | 接口（`Duration getSize()/getSlide()`、default `isSliding()`） | Duration 语义，与上者**同名不同型** |

关键事实：

1. **三套零耦合**：`core` 增量接口、`aggregation` 批式接口、`window` 窗口类型互不引用，也没有桥接。
2. **`aggregation` 模块生产侧无消费者**：除 `examples` 外，没有任何生产代码依赖 `AggregationFunction`/`aggregation.TimeWindow`；它自成体系（`functions/`、`analytics/`、`WindowAggregator`、`TumblingWindow/SlidingWindow`）。
3. **`core` 增量接口是"活的"**：runtime 窗口算子走的是 core 的 `AggregateFunction` + `window` 的 assigner/trigger。
4. 同名冲突：`TimeWindow` 在两个包下含义不同（毫秒区间 vs Duration 规格），阅读与检索都易踩坑。

## 问题

- 用户想写一个聚合函数时面对两个不兼容接口，选错一套就接不进 runtime 窗口管线。
- `aggregation` 里的高价值组件（TopK、分位数、PV/UV 等 `analytics/`）因为接口不兼容，无法直接被 runtime 窗口使用，等于重复建设。
- `aggregation.TimeWindow` 与 `window.TimeWindow` 同名冲突。

## 可选方案

### 方案 A：core 增量接口为唯一标准，aggregation 改造为高阶库（推荐）

- **标准**：聚合函数只认 `core` 的增量 `AggregateFunction`（4 方法 + merge），因为窗口引擎、checkpoint 语义都建立在增量模型上，批式接口无法表达 merge（跨分区/跨窗口合并）。
- **aggregation 模块重新定位**：不再是"另一套 API"，而是 core 接口之上的**高阶实现库**——TopK/分位数/PVUV 改写为 `AggregateFunction` 实现（或提供增量 accumulator 的经典结构：Sketch/Heap/HHyperLogLog++/CountMin）。
- **批式接口退役**：`AggregationFunction` 标记 `@Deprecated`（附迁移说明：批式场景可用 `Stream.collect` 或先落 `AggregateFunction` 再 `getResult`），一个大版本后删除。
- **窗口类型收敛**：`aggregation.TimeWindow`（接口）删除，统一用 `window.TimeWindow`；`aggregation` 的 `TumblingWindow/SlidingWindow`（Duration 规格）保留为"规格"对象，但转换为 assigner 产出 `window.TimeWindow`。

### 方案 B：双轨共存 + 适配器

保留两套接口，`aggregation` 提供 `AggregateFunctionAdapter`（把批式函数包成增量：内部攒 List，getResult 时 apply）。改动最小，但把 O(n) 内存攒批的语义伪装成增量，窗口大时爆内存——**不推荐**，仅作为 v1 迁移期的临时桥。

### 方案 C：反向统一（批式为标准）

不可行。窗口触发、checkpoint、乱序容错都要求增量语义，批式接口无法承载。

## 迁移路径（方案 A 分期）

1. **v1（兼容期，已落地 2026-10-11）**：`analytics/` 高阶聚合逐个补 `AggregateFunction` 实现（新代码，不动旧类）；补齐文档指向 core 接口；`AggregationFunction`、`aggregation.TimeWindow` 标记 `@Deprecated`。

   落地记录：新增 `aggregation.incremental` 包四个 core `AggregateFunction` 实现——`TopKAggregateFunction`（HashMap 精确计数 + 触发时排序，tie 政策与 `TopKAnalyzer` 一致：分数降序、item 升序，结果 `TopKEntry(item,count)`）、`QuantileAggregateFunction`（nearest-rank `floor(q×(n-1))`，与 `QuantileAnalyzer.quantile` 同一定义，空窗口返回 `null`）、`PVAggregateFunction`（计数）、`UVAggregateFunction`（HashSet 精确去重）。设计修正一处事实偏差：analytics 四类并非经典累加器（Sketch/Heap）而是 Redis 持久化滚动窗口服务（自带存储/查询 API/TTL/清理线程），按方案 A 的括号备选"提供增量 accumulator 的经典结构"落地为窗口内精确计算的新实现，而非改造 Redis 服务本身——两者定位互补：窗口内走 incremental 包，跨进程/可恢复查询走 analytics 包。批式接口退役：`AggregationFunction` 与 `aggregation.TimeWindow` 标记 `@Deprecated`（javadoc 指向 core `AggregateFunction` 与 `window` 模块 assigners）；使用 deprecated API 的存量代码（`functions/` 五类、`WindowAggregator`、`TumblingWindow/SlidingWindow`、examples 的 `StreamAggregationExample`）按类级 `@SuppressWarnings("deprecation")` 压警（-Werror 门禁下必须），注释标注随 v2 一并处理。测试：四个函数单测（含 tie 政策/nearest-rank 等值/merge 语义/序列化往返）+ `IncrementalAggregatePipelineTest` 4 用例经公开 `InMemoryKeyedStream` + `window.assigners.TumblingWindow` 真实喂进 `WindowedStream.aggregate`（钉死"批式接口喂不进窗口管线"这一统一动机）；`aggregation:check`（单测+集成）全绿。v2（examples 迁移、删 deprecated 类、WindowAggregator 改造）随 major 周期。
2. **v2（major）**：`examples` 全部改用新接口；删除 deprecated 类；`aggregation` 模块 javadoc 明确"core 接口的实现库"定位。

   落地记录（2026-10-12）：examples 的 `StreamAggregationExample` 重写为两层演示——窗口管线走 `incremental` 四实现（经公开 `InMemoryKeyedStream` + `window.assigners.TumblingWindow`，每管线一发一结果），跨进程查询保留 `analytics` 的 `PVCounter`/`TopKAnalyzer` 直连用法；测试改为对最终快照的可观测断言（pv≥1、uv∈[1,页面数]、top 命中页面集、sum>0、p90≥50ms）。**删除范围比 v1 计划更彻底**：不只删两个 `@Deprecated` 类，而是整面批式 API——根包 `AggregationFunction`/`TimeWindow`/`TumblingWindow`/`SlidingWindow`/`WindowAggregator`、`functions/` 五类及其 14 个测试（`git rm` 24 文件）。理由：方案 A 的终态是"core 接口的实现库"，批式接口无 merge 语义、无法接入窗口管线，保留 `WindowAggregator`/Duration 规格窗口只会维持第三套抽象（`WindowAggregator` 的"改造"目标经盘点无真实消费者，其能力已被 `analytics` 服务（可查询滚动窗口）+ `incremental` 包（窗口内计算）完全覆盖，故删除而非改造）；`Storms.grandStorm` 种子类从已删的 `WindowAggregator` 换为存活的 `PVCounter`。文档同步：`docs/Aggregation.md` 全文重写为两层定位（8 主源码文件）、README/README.zh 模块条目与用法示例改新 API、POTENTIAL_BUGS B-37 追记存档。`aggregation:check` + `examples:test` 全绿。

## 非目标

- 不改 core `AggregateFunction` 的形状（4 方法 + merge 已覆盖需求，无扩展必要）。
- 不引入新的窗口 assigner 抽象；`window` 模块保持现状。
- 不做聚合结果持久化/Redis 序列化格式的变更。
