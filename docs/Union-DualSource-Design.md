# Union / 双源汇合原语设计（Blocker B 留档，2026-10-12）

状态：**设计稿；推荐路径 step 1（union 原语）已落地 2026-10-14**。本文档回应 `docs/Join-CEP-Operators-Design.md` §5.2 的卡点 B——`DataStream.join(other)` 语法糖需要先把左右流汇入同一输入，而 `DataStream` API 没有汇合原语。本文把可选方案、取舍、推荐路径与未实施清单定下来。

## 1. 问题

- `DataStream<T>` 的变换全部是**单源链式**（map/filter/keyBy/…），没有 `union(other)` 或等价的双源汇合；
- 内存引擎是**拉式迭代器链**（`Supplier<Iterator<InMemoryRecord<T>>>`），没有 topic 基底；
- Redis 引擎的 `RedisPipelineDefinition` 是**单 topic + 单 consumer group**，`RedisStreamExecutionEnvironment.executeAsync()` 虽然能并发跑**多条** pipeline，但 pipeline 之间没有数据交换通道——两条 pipeline 各自的算子链无法合流进同一个 keyed 算子；
- 因此 join 语法糖「编译期展开为信封 + 桥接」缺的不是代码而是**汇合点语义**：在哪汇、怎么保序、watermark/checkpoint 怎么对齐。

## 2. 约束（必须同时满足）

1. **信封封装不变**：汇合产物仍走 `Envelope<K, L, R>`（side + joinKey + timestamp），算子端（`StreamJoinOperator`）零改动；
2. **确定性状态键不破坏**：Redis 引擎 state 键含 `topic:p:pid`；合流后 join 算子落在**单条 pipeline 的单 keyBy** 上（`process()` 返回 `DataStream` 非 `KeyedStream`），stateName 固定（`__operator:join:buffers`）不受影响——汇合方案不得把 join 算子拆到多条 pipeline；
3. **跨源顺序天然无定义**：左右流之间不存在全序，汇合只需保证**源内有序**（Redis Streams 单流读序、内存迭代器序都满足）；join 语义本身不依赖跨源顺序（窗口谓词是区间判定，不是时序判定）；
4. **checkpoint/watermark 通道按 pipeline 隔离**：汇合若引入第二条读取源，水位与 barrier 的对齐必须显式设计（Phase 3 event-time join 的前置），本批不承诺；
5. **引擎公开面变更最小**：新增 API 必须同时有内存与 Redis 两引擎的落点，不出现只有单引擎能用的口子（既有默认方法抛不支持的模式可过渡，但须写明）。

## 3. 可选方案

### 方案 U1：`DataStream.union(DataStream other)` 引擎原语

- 内存引擎：迭代器交错合并（round-robin 或拉平），无状态，成本低；
- Redis 引擎：**动 `RedisPipelineDefinition` 架构**——单源假设贯穿 runner 的提交/水位/barrier 通道，改为多源读取器需要重新设计分区定位、消费组协调与 checkpoint 对齐；这不是一个默认方法能过渡的缺口；
- 结论：内存侧便宜，Redis 侧是 Phase 3「引擎级双源 pipeline（方案 A）」的实质内容，提前实现等于把 Phase 3 的核心工作拉进来。

### 方案 U2：MQ topic 作为汇合点（自动多 pipeline 编排 + 合流消费）

- 语法糖展开为：左源 map 成 `Envelope` 写入 join 输入 topic、右源同样、再从该 topic 起一条消费 pipeline 承载 join 算子；
- Redis 引擎：**零引擎改动**——汇合点就是 Redis Stream 本身（单流 = 全序，源内有序天然满足），`fromMqTopic` 起一条 pipeline 即可；代价是多出一个用户可感知的 topic（生命周期、trim 归属要定义）；
- 内存引擎：没有 topic，糖展开为迭代器交错（与 U1 内存侧相同）；
- 结论：Redis 侧最省，但**两侧展开产物不对称**（Redis 走 topic、内存走迭代器），且要求左右源本身是能写 topic 的生产者——若左右源是另一条 pipeline 的输出，仍缺 pipeline→topic 的桥。

### 方案 U3：双 pipeline 生产 + 单 pipeline 消费（编排器合并 U2）

- 同一 `executeAsync()` 里注册**三条** pipeline：左源 pipeline（map 成信封 → MQ sink 写 join topic）、右源 pipeline（同）、消费 pipeline（`fromMqTopic(joinTopic)` → keyBy → join 算子）；
- 引擎零改动，且左右源可以是任意 pipeline（桥接问题解决）；代价是信封落地一次 Redis（延迟 + 一份存储），join topic 的 trim/TTL 需要策略；
- 结论：**当前引擎架构下唯一能完整承载语法糖语义的路径**，也是 U2 的严格化。

## 4. 推荐路径

1. **第一步（小原语，先评审）**：`DataStream.union(DataStream<? extends T>)` 只做内存引擎（迭代器交错），Redis 引擎默认抛不支持并写明「用 join topic 模式」——原语先立契约，Redis 落点指向 U3；
2. **第二步（语法糖，U3 展开）**：`DataStream.join(other, config, fn)` 在 Redis 引擎展开为 U3 三段编排（自动生成 join topic 名、信封 map、合流消费 pipeline），内存引擎展开为 map + union + keyBy + 算子；join topic 生命周期跟随 job（trim 走 MQ 的 stream 保留治理）。实施注记（2026-10-14）：`Envelope`/`StreamJoinOperator` 在 join 模块而 `DataStream` 在 core——糖的签名不能直接引用算子类型，落点需在 join 模块扩展或运行时/starter 层。**落点已定（同日）**：内存侧糖落在 join 模块 `StreamJoinOperator.join(left, right, config, fn)`（静态工厂，只用 core 公开面组合 map→union→keyBy→process，信封键即 keyBy 键，键选择器不一致的静默失配在糖路径上结构性不可能）；Redis 侧 U3 三段编排仍待实施；
3. **第三步（Phase 3 合并设计）**：Redis 引擎原生多源读取（U1 的 Redis 侧）与 event-time 水位对齐一起做——那才是免中转 topic 的最终形态；U3 的语法糖届时切换底层实现，用户 API 不变。

## 5. 未实施清单（本批留档，待评审后另批实施）

- [x] `DataStream.union(...)` 契约 + 内存引擎实现 + Redis 引擎显式不支持（默认方法模式）（2026-10-14，11 测试）；
- [ ] `DataStream.join(other, ...)` 语法糖，Redis 侧：U3 三段编排（join topic 命名/trim/生命周期、合流消费 pipeline 注册）。实施注记（2026-10-14）：静态工厂签名无法表达 Redis 侧——`DataStream` 公开面不暴露执行环境（`RedisStreamBuilder.env` 为私有字段），糖拿不到 env 就无法注册左右两条「信封落地 join topic」pipeline；架构卡点（2026-10-14 复核）：`runtime` 主源码**刻意不依赖 join 模块**（join 在其 `testImplementation` 里），而 `RedisStreamBuilder.map()` 返回 `DataStream<R>` 而非 builder——糖若落在 runtime 会反转「引擎→算子库」的依赖分层，若落在 join 模块则拿不到 Redis 引擎的 env。三个候选落点（runtime 主依赖 join / 新建 join-runtime 桥接模块 / `DataStream` 暴露 env 的环境级 `env.join(...)`）各有一次架构改动，属设计取舍，**待评审**；join topic 生命周期规则（命名、trim/TTL、随 job 清理）亦需一并定；
- [x] `DataStream.join(other, ...)` 语法糖，内存侧：`StreamJoinOperator.join(left, right, config, fn)` 静态工厂（信封 map + union + keyBy + 算子，时间戳走 config 提取器、缺省落系统时间与 `StreamJoiner` 一致）（2026-10-14，7 测试，与手工信封路径差分对照）；
- [ ] 双引擎集成测试：join 糖的差分对照（直接信封路径 vs 糖展开路径输出一致）；
- [ ] event-time join 窗口评估（依赖 `TimestampAssigner` 重载与水位对齐，随 Phase 3）。

## 6. 与既有文档的关系

- `docs/Join-CEP-Operators-Design.md` §2（语义定位）、§5.2（卡点 B 记录）：本文是其展开；
- `docs/Join.md` 算子化章节：汇合点落地后补充「语法糖用法」；
- `docs/Join-CEP-Operators-Design.md` §3.4：汇合不改变状态布局（join 算子仍单 pipeline 单 keyBy）。
