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

1. **v1（兼容期）**：`analytics/` 高阶聚合逐个补 `AggregateFunction` 实现（新代码，不动旧类）；补齐文档指向 core 接口；`AggregationFunction`、`aggregation.TimeWindow` 标记 `@Deprecated`。
2. **v2**：`examples` 全部改用新接口；删除 deprecated 类（走 major 版本）；`aggregation` 模块 javadoc 明确"core 接口的实现库"定位。

## 非目标

- 不改 core `AggregateFunction` 的形状（4 方法 + merge 已覆盖需求，无扩展必要）。
- 不引入新的窗口 assigner 抽象；`window` 模块保持现状。
- 不做聚合结果持久化/Redis 序列化格式的变更。
