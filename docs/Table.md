# Table

Module: `table/`

## 模块职责

Stream-Table duality：把 key-based 更新流解释为「表」的当前状态（物化视图）。每个 key 在任意时刻至多一个 value，`value == null` 表示删除该 key。

模块共 8 个主源码文件：接口 3 个（`KTable`、`KGroupedTable`、`TableAggregator`）、工具 1 个（`StreamTableConverter`）、实现 4 个（`impl/InMemoryKTable`、`impl/RedisKTable`、`impl/InMemoryKGroupedTable`、`impl/RedisKGroupedTable`，其中 Redis 分组表经 `groupBy` 获得）。

## 对外接口

### KTable&lt;K, V&gt;（`io.github.cuihairu.redis.streaming.table`）

```java
<VR> KTable<K, VR> mapValues(Function<V, VR> mapper);        // 变换值
<VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper);   // 变换值（带 key）
KTable<K, V> filter(BiFunction<K, V, Boolean> predicate);    // 过滤行
<VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);      // 内连接：对端无值则丢行
<VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner);  // 左连接：保留左行，右值可为 null
DataStream<KeyValue<K, V>> toStream();                       // 当前快照导出为流
<KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector); // 按新 key 分组
```

嵌套类型 `KTable.KeyValue<K, V>`（`getKey()` / `getValue()`，静态工厂 `KeyValue.of(key, value)`，默认实现 `DefaultKeyValue`）。

### KGroupedTable&lt;K, V&gt;

```java
<VR> KTable<K, VR> aggregate(Supplier<VR> initializer,
                             TableAggregator<K, V, VR> adder,
                             TableAggregator<K, V, VR> subtractor);
KTable<K, Long> count();
KTable<K, V> reduce(BiFunction<V, V, V> adder, BiFunction<V, V, V> subtractor);
```

`TableAggregator<K, V, VR>` 是函数式接口：`VR apply(K key, V value, VR aggregate)`（把一条记录折入当前聚合值）。

### StreamTableConverter（静态工具）

```java
static <K, V> KTable<K, V> toTable(DataStream<KTable.KeyValue<K, V>> stream);
static <T, K, V> KTable<K, V> toTable(DataStream<T> stream, Function<T, K> keyExtractor, Function<T, V> valueExtractor);
static <T, K, V> TableBuilder<T, K, V> tableBuilder(Function<T, K> keyExtractor, Function<T, V> valueExtractor);
// TableBuilder.build(DataStream<T>) 等价于上面的三参 toTable
```

## 两个实现

| | `InMemoryKTable` | `RedisKTable` |
|---|---|---|
| 定位 | 测试与简单用例 | 生产（状态存 Redis Hash，JSON 序列化 key/value） |
| 构造 | `new InMemoryKTable<>()` 或传入初始 `Map<K,V>` | `new RedisKTable<>(redissonClient, tableName, keyClass, valueClass)`，`tableName` 即 Redis Hash key |
| 额外方法 | `put` / `get` / `getState` / `size` / `clear` | `put` / `get` / `getState` / `size` / `clear` / `delete`（删除整个 Hash）/ `getTableName()` |
| `join`/`leftJoin` 对端 | 仅 `InMemoryKTable` 或 `RedisKTable`（其他类型抛 `UnsupportedOperationException`） | 同左 |

两个实现的共同行为：

- `put(key, null)` 删除该 key（Redis 版删除 Hash field）。
- `toStream()` 是**快照**导出：调用时把当前状态物化成一条集合流（非持续 changelog）。
- `mapValues` / `filter` / `join` / `groupBy` 的结果是一张**新表**：InMemory 版返回新内存表；Redis 版写入派生表 `<tableName>:<op>:<millis>-<uuid>`（如 `...:mapValues:...`、`...:join:...`，分组结果为 `<tableName>:groupBy:count:...` 等），原表不变。
- `groupBy` 的 keySelector 返回 `null` 的行被跳过（两个实现一致）。

## 语义边界（当前实现）

- `aggregate` / `count` / `reduce` 是对当前快照的**一次性重算**：按分组逐行折入 adder 得到结果表；`subtractor` 参数在现有实现中不会被调用（无回撤步骤）。
- `reduce` 对某分组的第一个值直接采用，不做 adder 调用。

## 配置项

模块没有配置文件键。`RedisKTable` 的全部配置就是构造参数：`redissonClient`、`tableName`（Redis Hash key）、`keyClass` / `valueClass`（Jackson 反序列化目标类型）。

## 用法示例（均可在模块测试中找到原型）

```java
// 1) 内存表：put / null 删除 / get（InMemoryKTableTest）
InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
table.put("a", 1);
table.put("b", 2);
table.put("a", null);          // 删除 a
table.get("b");                // 2
table.size();                  // 1

// 2) 流 -> 表 -> 流（StreamTableConverterTest）
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
KTable<String, Integer> built = StreamTableConverter.toTable(
        env.fromElements(KTable.KeyValue.of("a", 1), KTable.KeyValue.of("a", 2),
                         KTable.KeyValue.of("b", 3), KTable.KeyValue.of("a", null)));
// （转型 InMemoryKTable<String, Integer> 后）size() == 1；get("a") == null（null 覆盖为删除），get("b") == 3

List<String> out = new ArrayList<>();
built.toStream()
        .map(kv -> kv.getKey() + "=" + kv.getValue())
        .addSink(out::add);

// 3) 分组聚合（InMemoryKGroupedTableTest）
KGroupedTable<String, Integer> grouped = built.groupBy(kv -> kv.getKey().substring(0, 1));
KTable<String, Long> counts = grouped.count();
KTable<String, Integer> summed = grouped.aggregate(
        () -> 0,
        (key, value, agg) -> agg + value,
        (key, value, agg) -> agg);   // subtractor 当前不会被调用

// 4) Redis 表（RedisKTableTest / RedisKTableGroupingUpdateIntegrationTest）
RedisKTable<String, Integer> users = new RedisKTable<>(redissonClient, "users", String.class, Integer.class);
users.put("eu-1", 100);
var byRegion = users.groupBy(kv -> kv.getKey().startsWith("eu-") ? "EU" : "OTHER");
RedisKTable<String, Long> regionCounts = (RedisKTable<String, Long>) byRegion.count();
regionCounts.getTableName();     // 形如 "users:groupBy:count:<millis>-<uuid>"
```

## References

- [Architecture](Architecture.md)
- [Join](Join.md) - 流-流连接（与本模块的 KTable 表连接是两套 API）
