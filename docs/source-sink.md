# Source & Sink 模块

模块目录:`source/`、`sink/`。连接器实现 core 的 `StreamSource` / `StreamSink` 契约(含 `open()/close()` 生命周期,见 [Core.md](Core.md))。

## Sources(source 模块)

| 类 | 说明 |
|---|---|
| `source.collection.CollectionSource` | 从 `Collection` 一次性发射(实现 `StreamSource`) |
| `source.generator.GeneratorSource` | 按函数迭代生成元素 |
| `source.file.FileSource` | 逐行读取文件 |
| `source.http.HttpApiSource` | 轮询 HTTP API(回调式,`AutoCloseable`,非 `StreamSource`) |
| `source.kafka.KafkaSource` | Kafka 消费(回调式,`AutoCloseable`) |
| `source.redis.RedisListSource` | Redis **List** 轮询(LINDEX/LPOP 语义,回调式,`AutoCloseable`) |
| `source.redis.RedisStreamSource` | ✅ 实现 `StreamSource`,Redis **Stream** XREADGROUP 消费(与 `RedisStreamSink` 配对) |

### RedisStreamSource(推荐)

XREADGROUP 消费 Redis Stream;条目约定单字段 JSON 载荷(默认字段名 `value`,与 `RedisStreamSink` 对称)。`run()` 为**有界排空**:连续 `maxIdlePolls` 次空读后返回,以适配拉式引擎。

```java
import io.github.cuihairu.redis.streaming.source.redis.RedisStreamSource;

RedisStreamSource<String> source =
        new RedisStreamSource<>(redissonClient, "events", "my-group", "consumer-1", String.class);

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.addSource(source).map(String::toUpperCase).print();
```

> 消费者组自动创建使用显式 `0-0` 起始 id(而非 `StreamMessageId.MIN` 的 `-`,后者要求 Redis ≥ 7.0)。条目 `collect` 后即 `XACK`。

## Sinks(sink 模块)

| 类 | 说明 |
|---|---|
| `sink.print.PrintSink` | 控制台输出 |
| `sink.collection.CollectionSink` | 收集到 `List`(测试常用) |
| `sink.file.FileSink` | 逐条写文件(`close()` 释放句柄) |
| `sink.kafka.KafkaSink` | Kafka 生产者 |
| `sink.redis.RedisStreamSink` | ✅ 实现 `StreamSink`,真实 **XADD**;载荷 JSON 化存入可配置字段(默认 `value`) |
| `sink.redis.RedisListSink` | ✅ 实现 `StreamSink`,RPUSH 到 Redis List(由历史误名的 RedisStreamSink 更名而来) |
| `sink.redis.RedisHashSink` | 写 Redis Hash |

### RedisStreamSink / RedisListSink

```java
import io.github.cuihairu.redis.streaming.sink.redis.RedisStreamSink;
import io.github.cuihairu.redis.streaming.sink.redis.RedisListSink;

// XADD:每条记录一个 stream entry
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream"));
// 自定义字段名 + 对象 JSON 序列化
stream.addSink(new RedisStreamSink<>(redissonClient, "out-stream", "payload", mapper));
// RPUSH:Redis List 语义
stream.addSink(new RedisListSink<>(redissonClient, "out-list"));
```

> 破坏性变更提示:`RedisStreamSink` 在 0.3 之前名为 "Stream" 实际写 List;现语义已改真 XADD,如需原 List 行为请改用 `RedisListSink`。

## 与 Redis 运行时的关系

Redis 运行时引擎自身另有一套内置 sink(`runtime/redis/sink`:幂等/检查点 list sink)用于 exactly-once 演示,与 sink 模块互不依赖。

## References
- [Core.md](Core.md) · [runtime.md](runtime.md) · [MQ.md](MQ.md)
