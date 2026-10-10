# 状态后端统一设计（state 模块 ↔ runtime keyed store）与水位线逻辑收敛

本文供评审，不含代码改动。目标：仓库有两套互不兼容的 Redis 状态实现，水位线计算逻辑存在三处分叉；收敛为一套标准。

## 现状（事实盘点）

### 状态：两套实现

| 维度 | `runtime` `RedisKeyedStateStore` | `state` 模块 `RedisStateBackend` |
| --- | --- | --- |
| key 布局 | `{stateKeyPrefix}:{job}:cg:{group}:topic:{topic}:p:{pid}:state:{operatorId}:{stateName}[:shard:N]`（按 job/group/topic/partition/operator 全维度键控） | `{keyPrefix}{descriptor.getName()}` 扁平 key（默认前缀 `state:`，**无 job/分区维度**） |
| codec | StringCodec + JSON | 按 `Class` 类型的 codec（二进制） |
| 状态类型 | 仅 ValueState | Value/Map/List/Set 四种 |
| 附加能力 | TTL、schema 登记（`:{job}:stateSchema`）、stateKeys 索引（`:{job}:stateKeys`，供 checkpoint 枚举）、热 key 观测 | 无 |
| 依赖关系 | checkpoint 模块依赖其 stateKeys 索引做恢复枚举——**它是有下游契约的** | 无生产侧依赖（除 examples/文档） |

### 水位线：三处逻辑

1. `runtime` `internal/WatermarkState`：`volatile long + idle` 标志的单调水位线状态容器（只管"只增不减"）。
2. `runtime` `RedisPipelineRunner.candidateFor`：`maxEventTimeMs - outOfOrdernessMs`（守卫 `!= Long.MIN_VALUE`），逐条更新。
3. `watermark` 模块 `BoundedOutOfOrdernessWatermarkGenerator`：`maxTimestamp - maxOutOfOrderness - 1`（Flink 语义：watermark `w` 表示"不会再有 ≤ w 的事件"），初始 `maxTimestamp = MIN_VALUE + ooo + 1`。

**2 与 3 语义不同**：runtime 少减 1（watermark 恰等于 `max - ooo`，边界事件会被误判为"未迟到"与否取决于下游比较符），且初始值/守卫各自处理。同一配置在两处产出不同的边界行为。

## 问题

- 用户面 API（`state` 模块 `StateDescriptor`）产出的状态与 runtime 引擎实际使用的 keyed 状态**不能互访**：key 布局与 codec 都不同，写进去的数据另一套读不到。
- 扁平 key 无 job 维度：同进程跑两个作业用同名 state 即串数据（与 `StreamKeys` 静态全局是同类问题，见多租户设计）。
- 无 TTL：`state` 模块状态永不过期；runtime 侧有 TTL 但只支持 ValueState。
- 水位线 `-1` 分叉是隐性语义 bug 源：改一处不改另一处，下游窗口触发的边界行为就漂移。

## 可选方案

### 方案 A：以 runtime keyed store 为标准实现，state 模块退为 API 面 + 适配层（推荐）

理由：runtime store 被 checkpoint 恢复契约依赖（stateKeys 索引），有 TTL/schema/热键治理，key 布局带全维度——它才是"活的"标准；state 模块的 `StateBackend/StateDescriptor` API 形状好（四种状态类型、类型描述符），适合保留为用户面。

- **保留**：`state` 模块的 API（`StateBackend` 接口、`StateDescriptor`、四种状态接口）不动。
- **替换实现**：`RedisStateBackend` 改为薄适配层——key 生成委托 keyed store 的布局函数（job 维度必填），codec 统一 StringCodec+JSON；Map/List/Set 在 keyed store 原语上补齐（Map→Hash per key、List→List per key、Set→Set per key，键控进 `{...}:state:{operatorId}:{name}` 布局，stateKeys 索引同步登记）。
- **TTL/schema 对齐**：四种状态都走 TTL 与 schema 登记（schema 只对 Value/复杂类型有意义，Map/List/Set 登记元素类型）。
- **兼容性**：key 布局与 codec 变更 = 不兼容变更，走 major + 迁移说明（老数据无生产消费者，风险低）。

### 方案 B：双轨保留，只写文档划清边界（"state 模块=独立用例，runtime store=引擎内部"）

零改动，但两套 codec/key 长期并存，checkpoint 与用户状态的互操作永远是坑。**不推荐**为终态；可作为 v0 的临时文档口径。

### 方案 C：反向统一（state 模块实现为标准，runtime 迁过去）

不可行：checkpoint 的 stateKeys 契约、按分区键控、TTL/热键都要重做，破坏面远大于方案 A。

## 水位线收敛（独立小项，先做）

把"bounded-out-of-orderness 计算"收敛到 `watermark` 模块一处：

- `BoundedOutOfOrdernessWatermarkGenerator` 的 `max - ooo - 1` 是 Flink 标准语义，**以它为准**。
- `RedisPipelineRunner.candidateFor` 改为复用同一计算（提取公共静态函数或直接内嵌 generator 实例），消除 `-1` 分叉与初始值差异。
- `WatermarkState` 保留（它是"只增不减 + idle"的容器，与计算逻辑正交）。
- 边界语义变更（`-1`）需要在文档标注：watermark `w` 从此表示"≤ w 的事件不再来"。

## 迁移路径（方案 A 分期）

1. **第 1 步（最小）**：水位线收敛（上面的独立小项），纯 runtime 内部 + watermark 模块，无外部兼容性影响。
2. **第 2 步**：keyed store 补 Map/List/Set 状态类型 + schema 登记；补单测（含 checkpoint 枚举新状态类型）。
3. **第 3 步（major）**：`RedisStateBackend` 改为适配层委托 keyed store；删除 `RedisValueState/RedisMapState/RedisListState/RedisSetState` 旧实现；迁移文档给 key/codec 对照表。

## 非目标

- 不做 RocksDB/堆内存等新后端抽象（`StateBackend` 接口已足够承载）。
- 不改 checkpoint 协议与 stateKeys 索引格式。
- 不在本设计内做状态迁移工具（老 key 数据量小，文档给重建指引即可）。
