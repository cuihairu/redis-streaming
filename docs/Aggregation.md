# Aggregation

Module: `aggregation/`

## 模块职责

core `AggregateFunction` 接口（`io.github.cuihairu.redis.streaming.api.stream.AggregateFunction`）之上的实现库，分两层：

- **`incremental`**：窗口内精确聚合的 `AggregateFunction` 实现（PV / UV / Top-K / 分位数），喂进 `WindowedStream.aggregate` 参与流管线，状态随 checkpoint 走。
- **`analytics`**：独立的 Redis 滚动窗口分析服务（PVCounter / UVCounter / TopKAnalyzer / QuantileAnalyzer），自带存储、查询 API、TTL 与清理线程，跨进程可查询，不需要流执行环境。

两层互补：**窗口内计算走 `incremental`，跨进程/可恢复查询走 `analytics`**。

模块共 8 个主源码文件：`incremental` 4 个、`analytics` 4 个。历史上本模块还有一套批式 API（`AggregationFunction`、`aggregation.TimeWindow/TumblingWindow/SlidingWindow`、`functions/`、`WindowAggregator`），与 core 增量接口互不兼容且无法接入窗口管线，已于 2026-10-12 删除（统一方案见 [Aggregation-Unification-Design.md](Aggregation-Unification-Design.md)）。

## 增量聚合（`aggregation.incremental`）

全部实现 core `AggregateFunction<IN, R>`（4 方法 + merge，accumulator 可 JSON 序列化），可直接作为 `KeyedStream.window(...).aggregate(fn)` 的入参：

| 类 | 输出 | 语义 | 备注 |
|---|---|---|---|
| `PVAggregateFunction<T>` | `Long` | 窗口内记录数 | 计数 |
| `UVAggregateFunction<T>` | `Long` | 窗口内不同 key 数 | HashSet 精确去重 |
| `TopKAggregateFunction<T>` | `List<TopKEntry<T>>` | 窗口内计数 Top-K | HashMap 精确计数 + 触发时排序；并列按 item 升序（与 `TopKAnalyzer` tie 政策一致）；`TopKEntry(item, count)` |
| `QuantileAggregateFunction` | `Double` | 窗口内分位数 | nearest-rank `floor(q×(n-1))`，与 `QuantileAnalyzer.quantile` 同一定义；空窗口返回 `null`；构造参数 `q` 被截断到 [0,1] |

```java
KeyedStream<String, Event> keyed = env.fromElements(events).keyBy(Event::getPage);
keyed.window(TumblingWindow.ofMinutes(1))
        .aggregate(new PVAggregateFunction<>())
        .addSink(result -> log.info("pv={} ", result));
```

Top-K / UV 对元素的要求：`keyBy` 的 key 即计数/去重对象（TopK 对 key 计数、UV 对 key 去重）；需要以其他字段为对象时先 `map` 投影。

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
// 1) 窗口管线：core AggregateFunction 实现，跟随流执行环境
KeyedStream<String, String> keyed = new InMemoryKeyedStream<>(pages::iterator, p -> p);
keyed.window(TumblingWindow.ofMillis(60_000))
        .aggregate(new TopKAggregateFunction<String>(5))
        .addSink(top -> log.info("top: {}", top));

// 2) 跨进程滚动查询：analytics 服务直连 Redis
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

完整可运行样例见 `examples/.../aggregation/StreamAggregationExample.java`（两层一起演示，需 Redis）；增量函数单测见 `aggregation/.../incremental/IncrementalAggregatePipelineTest.java`（经公开 InMemory 入口真实喂进 `WindowedStream.aggregate`）。

## References

- [Architecture](Architecture.md)
- [window](window.md)
