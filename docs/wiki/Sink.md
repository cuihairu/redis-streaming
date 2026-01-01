# Sink

Module: `sink/`

Data output connectors (Redis Stream/Hash/File/Collection, etc.).

## Scope
- 将数据写出到外部系统（文件、集合、Redis 等）
- 说明：部分 Redis sink 是独立 connector（不直接实现 `StreamSink`），可用 adapter 接入 runtime

## 2. Key Classes
- `sink.redis.RedisStreamSink`, `RedisHashSink`, `sink.file.FileSink`, `sink.collection.CollectionSink`

## Minimal Sample (runtime + FileSink)
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.sink.file.FileSink;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.fromElements("a", "b", "c")
        .addSink(new FileSink<>("out.txt", true));
```

## References
- Design.md
