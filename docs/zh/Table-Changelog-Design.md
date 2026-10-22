# KTable Changelog 设计(toStream 持续变更流)

> 决策日期:2026-10-10。对应 todo:`RedisKTable.toStream() 由静态快照导出改为持续 changelog`。
> 关联:[Control-Plane-Design.md](Control-Plane-Design.md)(同为期望状态/事件流思想)、[Table.md](Table.md)。

## 背景

`KTable.toStream()` 原实现(两种引擎)都是**静态快照**:调用时刻的 `getState()` 物化成一次性 DataStream。Flink 语义里 `KTable.toStream()` 输出的是**变更事件流**(changelog:INSERT/UPDATE/DELETE 按应用顺序),消费者折叠事件即得表状态。本仓 RedisKTable 因此与 KStream 生态割裂:表更新无法流入下游流管道。

## 决策

1. **事件流承载:MQ 层**(`MessageProducer` → `fromMqTopic`),不引入裸 RStream 自定义消费循环。理由:
   - MQ 的 consumer group 首建即从 `0-0` 重放全历史(`RedisMessageConsumer.subscribe` 的 ensure-group Lua 固定 `ARGV[2]="0-0"`)——**全历史重放 = 状态完整重建**,changelog 语义免费获得;
   - 分区、lease、重平衡、pending 接管、checkpoint 偏移全部复用现成基础设施;
   - `toStream()` 消费端就是一条普通 MQ 管道,可与 runtime 全部算子组合。
2. **事件格式**(payload JSON):`{"op":"PUT","k":<keyJson>,"v":<valueJson>}` / `{"op":"DEL","k":<keyJson>}`。`k`/`v` 是表主存的原始 JSON 编码(与 RMap key/value 同编码,双层编码:外层 JSON 字符串值 = 内层 JSON 文本)。事件 `KeyValue<K,V>`:PUT → `(k, v)`;DEL → `(k, null)`。
3. **开关:默认关,`withChangelog()` 显式开启**。理由:每次 put 多一次 MQ append 的写放大,对查询型表(配置/维表)是错误取舍;changelog 是给"做流的表"用的。未开启时 `toStream()` 保持静态快照(行为不变,现有调用方零影响)。
4. **emit 为 best-effort**:主存已更新,事件发送失败(异步回调异常/同步序列化异常)仅 WARN 不抛——与控制面审计流同一取舍:缺事件使消费者持旧值(最终由后续事件收敛),绝不阻断主路径。
5. **消费者组语义与执行模型**:默认组 `table-changelog-group:<tableName>`(组内单播 = 多消费端分流负载);`toStream(env, group)` 重载可传自定义组,每组各得全量(跨组广播)。**执行走调用方的环境**:Redis 引擎的管道只能经 `RedisStreamExecutionEnvironment.executeAsync()` 启动, therefore changelog 模式提供 `toStream(env)` / `toStream(env, group)` 重载把管道挂到调用方环境,由调用方 `executeAsync()` 启动并持 `RedisJobClient` 管理生命周期;因此无参 `toStream()` 在 changelog 模式下抛 `IllegalStateException`(隐藏环境里的 DataStream 永远无法启动——CI 集成测试以 CCE 实证过此坑),静态快照路径保持无参可用。
6. **clear()/delete() 不写 changelog**:`clear` 逐 key 补发 DEL 事件的代价是全表读取,且"清空"在事件流里应显式可辨(引入 CLEAR 事件则下游折叠逻辑分叉);`delete` 连表带 lineage 整体回收,消费者应自行停止。javadoc 注明:流式场景用逐 key `put(k, null)` 代替 `clear()`。
7. **InMemoryKTable 不改**:无分布式状态,changelog 无意义;静态快照即正确语义。

## Redis/MQ 布局

- changelog topic:`table-changelog:<tableName>`(经 MQ 常规布局落 `stream:topic:table-changelog:<tableName>:<partition>` 等)
- 事件顺序:消息 key = `tableName`(`send(topic, key, payload)` 的 key 走 `HashPartitioner` 的 `key.hashCode()` 路由)——同一张表的全部事件恒落同一分区,分区数不变前提下全序;分区扩容会导致跨分区乱序(MQ 层既有行为,与普通 topic 一致,非本设计新增风险)。

## 已知取舍 / 非目标

- **DEL 的 value 语义**:消费者收到 `(k, null)` 表示删除;`KeyValue` 允许 null value,下游 `filter` 需自行处理(与 Flink changelog 的 -D/+I 标记相比缺少显式 op 类型——事件类型可从 null 值区分,足够本仓场景;若未来需要 op 显式化,可升级 payload 带 op 字段透传,DataStream 元素类型改为事件包装类,属破坏性演进,本批不做)。
- **快照+增量混合流**(先发当前状态再跟随增量)不做:与"事件流"语义冲突,消费者折叠全历史即得状态,无需混合。
- **changelog 保留期**沿用 MQ 层 retention(`retentionMaxLenPerPartition`),不单独治理。
- **Derived 表**(mapValues/filter/join 结果)不继承 changelog:派生表由父表事件驱动重建,事件流双写徒增成本;后续如需,在 `newDerivedChild` 传播开关即可。

## 实现落点

- `table` 模块新增 `implementation project(':mq')`(changelog 事件经 MQ 层)。
- `RedisKTable`:`withChangelog()`/`isChangelogEnabled()`/`changelogTopic()`/`defaultChangelogGroup()`/`toStream(env)`/`toStream(env, group)`;`put` 双写 PUT/DEL 事件;`toStream(env)` 挂管道 `env.fromMqTopic(topic, group).map(parse)`,启动由调用方 `env.executeAsync()`。

## 验证

- 单测:事件 JSON 编解码往返(PUT/DEL/未知 op 拒绝/坏 payload 包装)、emit 全链(生产者注入 seam:PUT/DEL 事件内容、失败 future 吞、send 抛吞)、开关与环境语义(未开启 toStream 走快照、changelog 开启后无参 toStream 抛 ISE、toStream(env[, group]) 参数校验、enabled 惰性构建不触 Redis)。
- 集成测试(真 Redis):put a→put b→put(a,null) 后 `toStream(env)`+`executeAsync()` 全历史重放断言全事件到达且 a 的 DEL 在 PUT 之后(跨分区到达顺序不精确断言——分区扩容可乱序),追加 put c 后新事件到达;`toStream(envX, "grp-x")`/`toStream(envY, "grp-y")` 各自环境各自全量(排序比较)。
