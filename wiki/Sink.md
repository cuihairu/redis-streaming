# Sink

Module: `sink/`

Data output connectors (Redis Stream/Hash/File/Collection, etc.).

## 1. Scope
- 将流数据写出到外部系统（占位）

## 2. Key Classes
- `sink.redis.RedisStreamSink`, `RedisHashSink`, `sink.file.FileSink`, `sink.collection.CollectionSink`

## 3. Minimal Sample (placeholder)
```java
StreamSink<String> sink = new RedisStreamSink(redisson, "topicA");
```

## References
- Design.md
