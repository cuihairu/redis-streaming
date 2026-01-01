# Source

Module: `source/`

Data input connectors and `core` `StreamSource` implementations.

## Scope
- 提供可直接接入 `runtime` 的 `StreamSource<T>`（有限流/测试数据）
- 提供各类外部系统连接器（例如 Kafka/HTTP 等），可按需适配到 `StreamSource`

## Key Classes
- `io.github.cuihairu.redis.streaming.api.stream.StreamSource`（core API）
- `io.github.cuihairu.redis.streaming.source.collection.CollectionSource`（有限集合）
- `io.github.cuihairu.redis.streaming.source.generator.GeneratorSource`（生成器）
- `io.github.cuihairu.redis.streaming.source.file.FileSource`（文件行读取）
- `io.github.cuihairu.redis.streaming.source.kafka.KafkaSource`（Kafka consumer 工具类）

## Minimal Sample (runtime + StreamSource)
```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.source.collection.CollectionSource;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.addSource(new CollectionSource<>(java.util.List.of("a", "b", "c")))
        .map(String::toUpperCase)
        .print("source> ");
```

## References
- Design.md
