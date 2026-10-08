# Join

Module: `join/`

## 模块职责

基于时间窗口的 stream-stream join。`StreamJoiner` 把左右两条流（以「逐个喂元素」的方式接入）按 join key 与时间窗口配对，输出 `JoinFunction` 的结果。

定位：纯内存、单实例实现（类 javadoc 自述「for testing and simple use cases」）。状态在进程内 `ConcurrentHashMap`，不落 Redis，不支持多实例分布式 join，也不消费水位线。

模块共 6 个主源码文件：`JoinConfig`、`JoinWindow`、`JoinType`、`JoinFunction`、`JoinedElement`、`StreamJoiner`。

## 对外接口

### StreamJoiner&lt;L, R, K, O&gt;

```java
public StreamJoiner(JoinConfig<L, R, K> config, JoinFunction<L, R, O> joinFunction) // 构造即校验 config

synchronized List<O> processLeft(L element) throws Exception;   // 喂入左流元素，返回本次产生的输出
synchronized List<O> processRight(R element) throws Exception;
synchronized int getLeftBufferSize();     // 当前左侧缓冲条数
synchronized int getRightBufferSize();
synchronized void clear();                // 清空两侧缓冲
```

### JoinConfig&lt;L, R, K&gt;（Lombok `@Data` + `@Builder`，可序列化）

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `joinType` | `JoinType` | 必填 | `INNER` / `LEFT` / `RIGHT` / `FULL_OUTER` |
| `joinWindow` | `JoinWindow` | 必填 | 时间窗口 |
| `leftKeySelector` | `Function<L, K>` | 必填 | 左流取 key |
| `rightKeySelector` | `Function<R, K>` | 必填 | 右流取 key |
| `leftTimestampExtractor` | `Function<L, Long>` | `null`（缺省用 `System.currentTimeMillis()`） | 左流取时间戳 |
| `rightTimestampExtractor` | `Function<R, Long>` | 同上 | 右流取时间戳 |
| `maxStateSize` | `int` | `10000` | 两侧缓冲总数上限，超出按最旧时间戳逐条淘汰 |
| `stateRetentionTime` | `long`（毫秒） | `3600000`（1 小时） | 缓冲保留时长 |

`validate()` 校验：四个必填项非空；`maxStateSize > 0`；`stateRetentionTime > 0`；且 `stateRetentionTime >= before + after`（窗口跨度）——保留时间短于窗口会让仍在窗口内的缓冲元素先被清掉。

便捷工厂只有两个：`JoinConfig.innerJoin(leftKey, rightKey, window)` 与 `JoinConfig.leftJoin(leftKey, rightKey, window)`；`RIGHT` / `FULL_OUTER` 用 builder 构造。

### JoinWindow

```java
static JoinWindow of(Duration before, Duration after);  // 非对称
static JoinWindow ofSize(Duration size);                // 对称：before = after = size
static JoinWindow afterOnly(Duration after);            // 只向前看
static JoinWindow beforeOnly(Duration before);          // 只向后看
boolean contains(long referenceTimestamp, long candidateTimestamp);
long getBeforeMillis();
long getAfterMillis();
```

匹配条件：`candidateTs - referenceTs ∈ [-before, +after]`。在 join 中 reference 恒为**左**元素时间戳、candidate 为右元素时间戳，两侧按同一谓词判定，与到达顺序无关（见 `StreamJoiner` 实现注释）。

### JoinFunction&lt;L, R, O&gt; / JoinedElement&lt;L, R&gt;

```java
@FunctionalInterface
public interface JoinFunction<L, R, O> extends Serializable {
    O join(L left, R right) throws Exception;          // 左或右可能为 null（外连接）
    static <L, R, O> JoinFunction<L, R, O> of(BiFunction<L, R, O> function); // 从 BiFunction 包装
}
```

`JoinedElement<L, R>` 是可选的结果值对象（`getLeft()` / `getRight()` / `getTimestamp()`，`isLeftOnly()` / `isRightOnly()` / `isBothPresent()`，静态工厂 `of` / `leftOnly` / `rightOnly`）。注意 `StreamJoiner` **不会**自动产出 `JoinedElement`：它直接把 `joinFunction.join(left, right)` 的结果作为输出；要不要包装成 `JoinedElement` 由你的 JoinFunction 决定。

## 四种 JoinType 的输出行为

| 类型 | 匹配成功 | 元素无匹配 |
|---|---|---|
| `INNER` | 输出 `join(L, R)` | 不输出 |
| `LEFT` | 输出 `join(L, R)` | `processLeft` 立即输出 `join(L, null)` |
| `RIGHT` | 输出 `join(L, R)` | `processRight` 立即输出 `join(null, R)` |
| `FULL_OUTER` | 输出 `join(L, R)` | 两侧各自立即输出 `join(L, null)` / `join(null, R)` |

外连接的重要语义（源码 B-36 注释）：无匹配元素**立即**发射，没有水位线屏障、没有等待期、没有回撤。若对端元素随后才到达（仍在窗口内），这对元素会作为第二条记录再次输出——下游会看到同一元素先「未匹配」后「已匹配」两条记录，需要自行容忍或去重。等待后决策/回撤式实现不在本 joiner 的目标范围内。

## 内存与清理行为

- `maxStateSize`：每次缓冲后检查总条数，超限则从两侧缓冲里按时间戳最旧的元素开始逐条淘汰。
- `stateRetentionTime`：每次 `processLeft`/`processRight` 末尾清理；基准是两侧缓冲中见过的**最大元素时间戳**（不是墙钟，便于用任意历史时间戳测试），早于 `maxTs - stateRetentionTime` 的元素被清除。
- join key 为 `null` 抛 `IllegalArgumentException`（消息标明是左还是右的选择器产生）。
- 时间戳缺省时用 `System.currentTimeMillis()`，即仅按到达时间配对。

## 用法示例

### 工厂 + 简单字符串（可用 `JoinConfigConvenienceTest` / `StreamJoinerTest` 核对）

```java
var cfg = JoinConfig.innerJoin(
        (String s) -> s,                       // 左 key
        (String s) -> s,                       // 右 key
        JoinWindow.ofSize(java.time.Duration.ofSeconds(5)));

StreamJoiner<String, String, String, String> joiner =
        new StreamJoiner<>(cfg, (l, r) -> l + "+" + r);

joiner.processRight("k1");
var out = joiner.processLeft("k1");            // ["k1+k1"]
```

### 完整 builder（来自 StreamJoinerTest 的 Order/User 原型）

```java
JoinConfig<Order, User, String> config = JoinConfig.<Order, User, String>builder()
        .joinType(JoinType.INNER)
        .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(10)))
        .leftKeySelector(Order::getUserId)
        .rightKeySelector(User::getUserId)
        .leftTimestampExtractor(Order::getTimestamp)
        .rightTimestampExtractor(User::getTimestamp)
        .maxStateSize(1000)                    // 可选，默认 10000
        .stateRetentionTime(Duration.ofMinutes(30).toMillis()) // 可选，默认 1 小时
        .build();

StreamJoiner<Order, User, String, EnrichedOrder> joiner =
        new StreamJoiner<>(config, (order, user) ->
                new EnrichedOrder(order.getOrderId(), user.getName(), order.getAmount()));

List<EnrichedOrder> r1 = joiner.processRight(user);   // 无匹配左元素 -> []（INNER）
List<EnrichedOrder> r2 = joiner.processLeft(order);   // 窗口内配对 -> 1 条
```

### 外连接与去重提示

```java
JoinConfig<String, Integer, String> cfg = JoinConfig.<String, Integer, String>builder()
        .joinType(JoinType.FULL_OUTER)
        .joinWindow(JoinWindow.ofSize(Duration.ofMinutes(1)))
        .leftKeySelector(s -> s)
        .rightKeySelector(i -> String.valueOf(i))
        .build();

StreamJoiner<String, Integer, String, String> joiner =
        new StreamJoiner<>(cfg, (l, r) -> l + "|" + r);
// 先到的无匹配元素立即输出 "x|null"；对端 1 之后到达且在窗口内时，
// 该 key 会再输出一条 "x|1" —— 下游需容忍或去重（见上文外连接语义）。
```

## 算子化：在 DataStream pipeline 里跑 join

`StreamJoinOperator` 把上面的 join 语义装进 `KeyedProcessFunction`，两条流经**信封多路复用**接入同一个 keyed pipeline（设计细节与分阶段路线见 [Join/CEP 算子化设计](Join-CEP-Operators-Design.md)）：

```java
// 左右两源各自 map 成 Envelope（joinKey/时间戳随信封携带，是算子路径的权威值），
// 合并进同一个 join 输入 topic（内存引擎直接合并两个 source），然后：
env.fromMqTopic(joinInputTopic, "join-group")
    .map(m -> parseEnvelope(String.valueOf(m.getPayload())))
    .keyBy(Envelope::getJoinKey)
    .process(StreamJoinOperator.asKeyedProcessFunction(config, (l, r) -> l + "+" + r))
    .addSink(out);
```

- 语义与 `StreamJoiner` 逐点一致（算子内部就是委托一个 joiner 实例）：左锚定窗口谓词、外连接立即发射 + 后到补配对、retention/maxStateSize 淘汰；
- 信封 key 须与 `JoinConfig` 的 selector 一致——不一致不会报错，只会让配对路由到互不相见的分区而**静默失配**；
- Phase 1 边界：算子缓冲在实例内存（按 MQ 分区隔离），**不参与 checkpoint 快照**；failover 后窗口内历史缓冲丢失（输出对 failover at-most-once），缓冲入 keyed state 是 Phase 2。

## References

- [Architecture](Architecture.md)
- [Table](Table.md) - KTable 表连接（`KTable.join`/`leftJoin`，与本模块是两套独立 API）
