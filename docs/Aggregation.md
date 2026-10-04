# Aggregation

Module: `aggregation/`

## 模块职责

基于 Redis 数据结构（Sorted Set / HyperLogLog / Set）的时间窗口聚合工具：窗口对象、通用聚合函数、以及 PV / UV / Top-K / 分位数四个分析器。

说明：该模块是独立的聚合引擎，与 `runtime` 的 `WindowedStream`（见 [window](window.md)）无关，也不依赖流执行环境；所有状态都存在 Redis 里，方法在调用线程上同步执行。注意本模块的 `TimeWindow` / `TumblingWindow` / `SlidingWindow` 与 window 模块的同名类是完全不同的类型（本模块为 `aggregation` 包下的接口/值对象，非 `WindowAssigner`）。

模块共 14 个主源码文件：根包 5 个（`AggregationFunction`、`TimeWindow`、`TumblingWindow`、`SlidingWindow`、`WindowAggregator`）、`functions` 5 个、`analytics` 4 个。

## 窗口对象（aggregation 根包）

`TimeWindow` 是接口：

```java
Duration getSize();                              // 窗口长度
Duration getSlide();                             // 滑动步长，滚动窗口返回 getSize()
default boolean isSliding();                     // slide != null 且 slide != size
Instant getWindowStart(Instant timestamp);       // 该时刻所在窗口的起点
Instant getWindowEnd(Instant timestamp);         // 该时刻所在窗口的终点
default boolean contains(Instant ts, Instant windowStart); // [start, start+size)
```

- `TumblingWindow`：`of(Duration)`、`ofSeconds(long)`、`ofMinutes(long)`、`ofHours(long)`；`size == null` 或非正抛 `IllegalArgumentException`；`getSlide()` 返回 `size`。
- `SlidingWindow`：`of(Duration size, Duration slide)`、`ofSeconds(long, long)`、`ofMinutes(long, long)`；`size`/`slide` 非正、或 `slide > size` 抛 `IllegalArgumentException`；`getOverlappingWindows(Instant)` 返回覆盖该时刻的所有窗口起点（`List<Instant>`）。

## 聚合函数（`aggregation.functions`）

全部实现 `AggregationFunction<T, R>`（函数式接口：`R apply(Collection<T> values)`，`getName()` 默认返回类名）：

| 类 | `getName()` | 输入 → 输出 | 获取实例 | 空集合行为 |
|---|---|---|---|---|
| `CountFunction` | `COUNT` | `Object` → `Long` | `getInstance()`（单例） | `0` |
| `SumFunction` | `SUM` | `Number` → `BigDecimal` | `getInstance()`（单例） | `BigDecimal.ZERO` |
| `AverageFunction` | `AVERAGE` | `Number` → `BigDecimal` | `getInstance()`（单例） | `BigDecimal.ZERO`（非空时 10 位小数、`HALF_UP`） |
| `MinFunction<T>` | `MIN` | `Comparable` → `T` | `create()` | `null` |
| `MaxFunction<T>` | `MAX` | `Comparable` → `T` | `create()` | `null` |

## WindowAggregator（Redis 通用窗口聚合）

```java
public WindowAggregator(RedissonClient redissonClient, String keyPrefix)

<T, R> void registerFunction(String name, AggregationFunction<T, R> function)
void addValue(TimeWindow window, String key, Object value, Instant timestamp)   // score=时间戳写入 zset，并裁剪窗口起点之前的数据
<R> R getAggregatedResult(TimeWindow window, String key, String functionName, Instant timestamp)
                                 // 读取 [windowStart, windowEnd) 内的值并套用函数；函数名未注册抛 IllegalArgumentException
WindowStatistics getWindowStatistics(TimeWindow window, String key, Instant timestamp)
PVCounter createPVCounter(Duration windowSize)
TopKAnalyzer createTopKAnalyzer(int k, Duration windowSize)
void clearKey(String key)   // 注意：当前实现只记日志，并不删除任何数据
```

- Redis 键：`<keyPrefix>:window:<key>:<windowStartMillis>:<Window类简名>:<sizeMillis>`。键里含窗口类名与大小，因此同一个 key 上的 1 分钟窗口和 1 小时窗口互不串数据。
- `WindowStatistics` 是 `WindowAggregator` 的静态数据类（Lombok getter）：`getWindowStart()` / `getWindowEnd()` / `getValueCount()` / `getWindowSize()` / `getTimestamp()`。

```java
RedissonClient redisson = /* 见 state.md */;
WindowAggregator agg = new WindowAggregator(redisson, "demo:agg");
agg.registerFunction("COUNT", CountFunction.getInstance());

TumblingWindow win = TumblingWindow.ofSeconds(10);
Instant now = Instant.now();
agg.addValue(win, "events", "id-1", now);
Long cnt = agg.getAggregatedResult(win, "events", "COUNT", now);
WindowAggregator.WindowStatistics stats = agg.getWindowStatistics(win, "events", now);
```

## 分析器（`aggregation.analytics`）

### PVCounter（页面浏览计数）

基于每个页面的 Sorted Set（score=事件时间戳），统计**滚动窗口** `[now - windowSize, now]` 内的 PV。构造时启动一个清理线程（周期 `max(1s, windowSize)`），用完须 `close()`。

```java
PVCounter pv = new PVCounter(redisson, "demo:agg", Duration.ofMinutes(10));
long n = pv.recordPageView("home");                    // 记录并返回当前窗口计数
long m = pv.recordPageView("home", timestamp);         // 带事件时间；早于窗口起点的事件被拒绝（不写入），未来时间戳只存不计
long c = pv.getPageViewCount("home");                  // 当前滚动窗口计数
long r = pv.getPageViewCount("home", start, end);      // 指定区间，start 含、end 不含
pv.resetPageViewCount("home");                         // 清空该页并从索引移除
PVCounter.PVStatistics st = pv.getStatistics();        // getTotalPages() / getTotalViews() / getTimestamp()
pv.close();                                            // 停止清理线程
```

- `page` 为 null/空白时计数方法返回 0、写方法不做任何事。
- Redis 键：`<keyPrefix>:pv:<page>`（zset）、`<keyPrefix>:pv:pages`（页面索引 set）。

### UVCounter（独立访客计数）

按时间桶（默认 1 分钟）分桶的 HyperLogLog，查询时对覆盖滚动窗口的桶做 `countWith` 并集。实现 `AutoCloseable`（构造即启动清理线程）。

```java
UVCounter uv = new UVCounter(redisson, "demo:agg", Duration.ofMinutes(10));                    // bucketSize=1 分钟
UVCounter uv2 = new UVCounter(redisson, "demo:agg", Duration.ofMinutes(10), Duration.ofSeconds(30));

boolean isNew = uv.add("home", "visitor-1", Instant.now());   // 返回是否为该桶新访客
long uvCount = uv.count("home");                              // 滚动窗口（到当前时刻）
long uvAt   = uv.count("home", now);                          // 滚动窗口（到指定时刻）
long uvRange = uv.count("home", start, end);                  // 指定区间（桶对齐的近似值）
uv.reset("home");
uv.close();
```

- `bucketSize` 为 null/零/负时回落为 1 分钟；`windowSize` 展开的桶数超过 10000 时构造抛 `IllegalArgumentException`。
- `page` 或 `visitorId` 为 null/空白时 `add` 返回 false。
- Redis 键：`<keyPrefix>:uv:<page>:<bucketStartMillis>`（HLL）、`<keyPrefix>:uv:pages`、`<keyPrefix>:uv:<page>:buckets`（索引 set），键的过期时间为 `windowSize + 2*bucketSize`。

### TopKAnalyzer（窗口内 Top-K）

按固定桶（每窗口 10 桶，桶长 `windowSize/10`，至少 1ms）记录 item 权重，查询时汇总覆盖尾部窗口的桶。并列时按名称升序排名。

```java
TopKAnalyzer top = new TopKAnalyzer(redisson, "demo:agg", 10, Duration.ofMinutes(5));  // k=10
top.recordItem("pages", "/home");                       // 权重 +1，返回该桶当前分数
top.recordItem("pages", "/home", 2.5);                  // 自定义权重
List<TopKAnalyzer.TopKItem> items = top.getTopK("pages");       // item/score/timestamp，分数降序
List<TopKAnalyzer.TopKItemWithRank> ranked = top.getTopKWithRanks("pages"); // 附 rank（1 起）
int rank = top.getRank("pages", "/home");               // 1 起的名次，窗口内无分数返回 -1
double score = top.getScore("pages", "/home");          // 窗口内总分，无则 0.0
top.removeItem("pages", "/home");                       // 从所有存活桶移除，返回是否移除过
top.reset("pages");                                     // 删除窗口覆盖的所有桶
```

- 构造约束：`k <= 0` 或 `windowSize` 非正抛 `IllegalArgumentException`；`redissonClient`/`keyPrefix` 为 null 抛 NPE。
- 每个桶内最多保留 `2k` 个条目（超出按分数升序、并列按名称降序淘汰）；桶 TTL 为 `windowSize + 2` 个桶长。
- Redis 键：`<keyPrefix>:topk:<category>:b:<bucketIndex>`。

### QuantileAnalyzer（滚动窗口分位数）

每个 metric 两个 Sorted Set（时间索引 score=时间戳、值索引 score=观测值），构造即启动清理线程，实现 `AutoCloseable`。

```java
QuantileAnalyzer q = new QuantileAnalyzer(redisson, "demo:agg", Duration.ofMinutes(5));
q.record("latency", 12.3);                       // 以当前时间记录
q.record("latency", 8.1, timestamp);             // 带时间戳；早于窗口起点或晚于当前时间的时间戳被拒绝（不写入）
Double p50 = q.quantile("latency", 0.5);         // q 被截断到 [0,1]；无样本返回 null
Double p95 = q.p95("latency");                   // p50()/p95()/p99() 快捷方法
q.close();
```

- Redis 键：`<keyPrefix>:quantile:<metric>:ts`、`<keyPrefix>:quantile:<metric>:v`、索引 `<keyPrefix>:quantile:metrics`。
- 分位数取值是对值索引按名次取单条（`floor(q*(n-1))`），不做插值。

## 配置项

模块没有配置文件键；所有参数都是构造参数（上文各构造约束即校验规则）。Redis 键前缀由各构造器的 `keyPrefix` 决定。

## 最小示例（Redis 可用时）

```java
PVCounter pv = new PVCounter(redisson, "example", Duration.ofMinutes(10));
pv.recordPageView("home");

TopKAnalyzer top = new TopKAnalyzer(redisson, "example", 5, Duration.ofMinutes(5));
top.recordItem("pages", "home");

QuantileAnalyzer q = new QuantileAnalyzer(redisson, "example", Duration.ofMinutes(5));
q.record("latency", 20.0);
Double p95 = q.p95("latency");

pv.close();
q.close();
```

完整可运行样例见 `aggregation/src/test/java/io/github/cuihairu/redis/streaming/aggregation/AggregationIntegrationExample.java`（标记 `@Tag("integration")`，需要 Redis）。

## References

- [Design](Design.md)
- [window](window.md)
