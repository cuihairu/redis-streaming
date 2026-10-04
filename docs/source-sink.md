# Source & Sink 模块

模块目录:`source/`、`sink/`。各 7 个主源码类。

两类契约(见 [Core.md](Core.md)):
- **流式契约** `core` 的 `StreamSource`(`run(SourceContext)` + `cancel()`,含默认 `open()/close()` 生命周期)与 `StreamSink`(`invoke(T)`,同含默认生命周期)——由内存 runtime `StreamExecutionEnvironment.addSource(...)` / `DataStream.addSink(...)` 驱动;
- **回调式 `AutoCloseable`**:自带轮询线程/消费者循环,不经运行时,`try-with-resources` 或 `close()` 收尾。

## Sources(source 模块,7 个)

| 类 | 契约 | 说明 |
|---|---|---|
| `source.collection.CollectionSource` | `StreamSource` | 从 `Collection` 一次性发射(`collect`),`cancel()` 停止 |
| `source.generator.GeneratorSource` | `StreamSource` | 按 `Supplier` 生成 `count` 个元素,可选 `delayMillis` 间隔 |
| `source.file.FileSource` | `StreamSource` | 逐行读取文本文件,每行一个 `String` |
| `source.http.HttpApiSource` | `AutoCloseable`(回调式) | 定时轮询 REST API,`fetch()` 单对象 / `fetchList()` 列表 |
| `source.kafka.KafkaSource` | `AutoCloseable`(回调式) | Kafka 消费者,`poll(timeout)` / `consume(handler)` |
| `source.redis.RedisListSource` | `AutoCloseable`(回调式) | Redis **List** 头部弹出(`RList.remove(0)`,即 LPOP 语义) |
| `source.redis.RedisStreamSource` | `StreamSource` | Redis **Stream** XREADGROUP 消费(与 `RedisStreamSink` 配对) |

### RedisStreamSource

XREADGROUP 消费 Redis Stream;条目约定单字段 JSON 载荷(默认字段名 `value`,常量 `DEFAULT_VALUE_FIELD`,与 `RedisStreamSink` 对称)。`String` 载荷直传,其他类型经 Jackson 反序列化为 `valueClass`。

```java
// 5 参构造:valueField="value", batchCount=32, pollTimeoutMs=200ms, maxIdlePolls=3
RedisStreamSource<String> source =
        new RedisStreamSource<>(redissonClient, "events", "my-group", "consumer-1", String.class);
// 全参构造
new RedisStreamSource<>(redissonClient, "events", "my-group", "consumer-1",
        "payload", Order.class, 64, 500L, 10);   // valueField, valueClass, batchCount, pollTimeoutMs, maxIdlePolls
```

行为:
- `run()` 启动时创建消费者组(不存在时),使用显式 `0-0` 起始 id 而非 `StreamMessageId.MIN` 的 `-`(后者要求 Redis ≥ 7.0,见 CHANGELOG);组已存在则跳过。
- `run()` 为**有界排空**:连续 `maxIdlePolls` 次空读后返回,适配拉式引擎(内存 runtime 的 `addSource` 即如此驱动,立即执行并缓存记录)。
- 条目 `collectWithTimestamp`(时间戳取 entry id 的 ms 部分)后立即 `XACK`;缺 `value` 字段的条目打 warn 并跳过、同样 `XACK`。
- 参数校验:`batchCount >= 1`、`maxIdlePolls >= 1`,否则抛 `IllegalArgumentException`。
- getter:`getStreamName()`、`getConsumerGroup()`。

### 其余 Sources 要点

- `HttpApiSource<T>`:构造 `(apiUrl, valueClass)` 或 `(apiUrl, valueClass, objectMapper, pollInterval, headers)`;默认 `pollInterval=10s`、连接超时 5s、读超时 10s、自动补 `Accept: application/json` 头。`poll(handler)` 按 `fetch()` 周期轮询,`pollList(handler)` 按 `fetchList()` 逐条回调;请求失败 `fetch()` 返回 null / `fetchList()` 返回空列表(不抛出)。getter:`getApiUrl()`、`getPollInterval()`、`isRunning()`。
- `KafkaSource<T>`:构造 `(bootstrapServers, groupId, topic, valueClass)`,或传 `Properties` / 现成 `Consumer`。默认消费参数:`auto.offset.reset=earliest`、`enable.auto.commit=true`(间隔 1000ms)、`max.poll.records=500`、String 反序列化器。方法:`poll(Duration)`、`consume(handler)`、`consumeAsync(handler)`(返回守护线程)、`stop()`、`seekToBeginning()`、`seekToEnd()`(seek 前会先短 poll 以取得分区分配)、`commitSync()`、`getTopic()`、`isRunning()`、`close()`。
- `RedisListSource<T>`:构造 `(redissonClient, listName, valueClass)` 或加 `objectMapper`。方法:`readOne()`(LPOP)、`readBatch(count)`、`readAll()`、`consume(handler)`(连续拉取,空列表睡 100ms)、`poll(handler, pollInterval)`、`pollBatch(handler, batchSize, pollInterval)`(batchSize 必须 > 0)、`stop()`、`getSize()`、`isEmpty()`、`getListName()`、`isRunning()`、`close()`。反序列化失败返回 null(该条被丢弃并记 error 日志)。
- `CollectionSource<T>` / `GeneratorSource<T>` / `FileSource`(三者均为 `StreamSource`):
  - `GeneratorSource`:构造 `(generator)`(无限,直到 `cancel()`)、`(generator, count)`、`(generator, count, delayMillis)`;静态工厂 `GeneratorSource.sequence(count)`、`sequence(start, count)` 产出 `Long` 序列。
  - `FileSource`:构造 `(String filePath)` 或 `(Path)`;读文件失败包装为 `RuntimeException`。

## Sinks(sink 模块,7 个)

| 类 | 契约 | 说明 |
|---|---|---|
| `sink.print.PrintSink` | `StreamSink` | 输出到 `PrintStream`(默认 `System.out`),可选前缀/时间戳 |
| `sink.collection.CollectionSink` | `StreamSink` | 收集到 `Collection`(默认 `ArrayList`,测试常用) |
| `sink.file.FileSink` | `StreamSink` | 逐条写文件,每元素一行,写后即 flush;`close()` 释放句柄 |
| `sink.kafka.KafkaSink` | `AutoCloseable`(非 `StreamSink`) | Kafka 生产者,同步/异步写,可选 `KeyExtractor` |
| `sink.redis.RedisStreamSink` | `StreamSink` | 真实 **XADD** 到 Redis Stream |
| `sink.redis.RedisListSink` | `StreamSink` | `RList.add`(RPUSH)到 Redis List |
| `sink.redis.RedisHashSink` | 普通类(非 `StreamSink`、非 `AutoCloseable`) | 写 Redis Hash(HSET) |

### RedisStreamSink / RedisListSink

```java
import io.github.cuihairu.redis.streaming.sink.redis.RedisStreamSink;
import io.github.cuihairu.redis.streaming.sink.redis.RedisListSink;

// XADD:每条记录一个 stream entry,载荷存字段 "value"(String 直存,其余 JSON 序列化)
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream"));
// 自定义字段名 + ObjectMapper
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream", "payload", mapper));
// RPUSH:Redis List 语义
stream.addSink(new RedisListSink<>(redissonClient, "out-list"));
```

- `RedisStreamSink<T>`:字段名默认 `"value"`(`DEFAULT_VALUE_FIELD`);方法 `write(T)`(失败抛 `RuntimeException`)、`writeAsync(T)`、`writeBatch(Iterable<T>)`(返回成功条数)、`getSize()`(XLEN)、`clear()`(删流)、`getStreamName()`、`getValueField()`。构造参数任一为 null 抛 NPE。
- `RedisListSink<T>`:`write` 用 `RList.add`(追加到列表尾部,RPUSH);其余方法与 `RedisStreamSink` 对称,另有 `deleteList()`、`getListName()`。
- 历史变更(见 CHANGELOG「Unreleased」):旧版 `RedisStreamSink` 名为 "Stream" 实际写 List;现为真 XADD,原 List 行为由 `RedisListSink` 承接。

### RedisHashSink<K, V>

```java
RedisHashSink<String, Order> hash = new RedisHashSink<>(redissonClient, "orders-by-id", Order.class);
hash.write("id-1", order);                       // HSET(键值均 JSON 序列化,字符串直存)
hash.writeBatch(Map.of("id-2", order2));         // HSET 批量
Order removed = hash.delete("id-1");             // HDEL,返回旧值(按 valueClass 反序列化)
```

- 构造:`(redissonClient, hashName)`、`(redissonClient, hashName, valueClass)`(typed 版:`delete` 反序列化为该类型)、`(redissonClient, hashName, objectMapper, ttl)`(每次写后对整个 hash `EXPIRE`)。
- 方法:`write(key, value)`、`writeAsync`、`writeBatch(Map)`、`delete(key)`(未命中返回 null)、`getHashSize()`、`clear()`、`deleteHash()`、`getHashName()`。
- `writeWithFieldTTL(key, value, fieldTtl)`:字段级 TTL 需要 Redis 7.4+ 的 HPEXPIRE,当前 Redisson 版本未支持——方法只写值并打 warn,且仅当构造时配置了 `ttl` 才对整个 hash 应用 `expire(fieldTtl)`(会覆盖构造 TTL);未配置 `ttl` 时除 warn 外无 TTL 效果。

### KafkaSink / PrintSink / CollectionSink / FileSink

- `KafkaSink<T>`:构造 `(bootstrapServers, topic)`、`(bootstrapServers, topic, objectMapper, keyExtractor)` 或 `(Properties, topic, objectMapper, keyExtractor)`。默认生产参数:`acks=1`、`retries=3`、`linger.ms=10`、`batch.size=16384`、`compression.type=snappy`、String 序列化器。方法:`write(T)`(同步,返回 `RecordMetadata`)、`writeAsync(T)`、`writeToPartition(T, int)`、`flush()`、`getTopic()`、`close()`。`KeyExtractor<T>` 为函数式接口 `String extractKey(T)`;`write(null)` 抛 NPE。
- `PrintSink<T>`:构造 `()`、`(prefix)`、`(PrintStream)`、`(PrintStream, prefix, withTimestamp)`;输出格式 `[时间戳] 前缀: 元素`;静态工厂 `PrintSink.withTimestamp()`、`withPrefixAndTimestamp(prefix)`。
- `CollectionSink<T>`:构造 `()`(新 `ArrayList`)或 `(Collection<T>)`;方法 `getCollection()`、`getList()`(非 List 返回 null)、`size()`、`clear()`。
- `FileSink<T>`:构造 `(filePath)`(覆盖)、`(Path)`、`(filePath, append)`;`invoke` 每条 `value.toString()` + 换行并 flush;`close()` 释放句柄(持有 writer 的流式用法请显式调用)。

## 与 Redis 运行时的关系

Redis 运行时引擎(`runtime/redis/sink`)另有一套内置 sink——`RedisIdempotentListSink`、`RedisCheckpointedIdempotentListSink`、`RedisAtomicCheckpointListSink`、`RedisOutboxSink` 等,用于 exactly-once 演示,与本模块互不依赖。

## References
- [Core.md](Core.md) · [runtime.md](runtime.md) · [MQ.md](MQ.md)
