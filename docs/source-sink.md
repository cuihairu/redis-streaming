# Source & Sink 模块

## 概述

Source 和 Sink 模块提供数据源连接器和数据汇连接器，用于与外部系统集成。

## Source 模块

### 功能

从外部系统读取数据，创建数据流。

### 内置 Source

#### 1. RedisListSource

从 Redis List 读取数据。

```java
RedisListSource<String> source = new RedisListSource<>(
    redissonClient,
    "my-list",      // List 键
    "LPOP",         // 弹出方式 (LPOP/RPOP/LPOP/RPOP)
    new StringCodec()
);

DataStream<String> stream = env.fromSource(source);
```

#### 2. KafkaSource

从 Kafka Topic 读取数据。

```java
KafkaSource<String> source = new KafkaSource<>(
    "localhost:9092",
    "my-topic",
    "my-group",
    new StringDeserializer()
);

DataStream<String> stream = env.fromSource(source);
```

#### 3. HttpSource

从 HTTP 端点轮询数据。

```java
HttpSource<String> source = new HttpSource<>(
    "http://api.example.com/data",
    Duration.ofSeconds(10),  // 轮询间隔
    new StringCodec()
);

DataStream<String> stream = env.fromSource(source);
```

### 自定义 Source

实现 `StreamSource` 接口创建自定义数据源。

```java
public class MySource implements StreamSource<String> {
    private final Iterator<String> iterator;

    public MySource(Iterator<String> iterator) {
        this.iterator = iterator;
    }

    @Override
    public void open() {
        // 初始化资源
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public String next() {
        return iterator.next();
    }

    @Override
    public void close() {
        // 释放资源
    }
}
```

## Sink 模块

### 功能

将处理结果写入外部系统。

### 内置 Sink

#### 1. RedisHashSink

写入 Redis Hash。

```java
stream.sinkToRedisHash(
    "results",           // Hash 键
    (key, value) -> Map.of(
        "key", key,
        "value", value.toString(),
        "timestamp", String.valueOf(System.currentTimeMillis())
    )
);
```

#### 2. RedisStreamSink

写入 Redis Stream。

```java
stream.sinkToRedisStream(
    "output-stream",
    message -> new Message(
        message.getKey(),
        message.getValue(),
        Map.of("timestamp", String.valueOf(System.currentTimeMillis()))
    )
);
```

#### 3. RedisListSink

写入 Redis List。

```java
stream.sinkToRedisList(
    "output-list",
    value -> value.toString(),
    "RPUSH"  // LPUSH/RPUSH
);
```

#### 4. KafkaSink

写入 Kafka Topic。

```java
stream.sinkToKafka(
    "localhost:9092",
    "output-topic",
    new StringSerializer()
);
```

### 自定义 Sink

实现 `StreamSink` 接口创建自定义数据汇。

```java
public class MySink implements StreamSink<Result> {
    private final Connection connection;

    public MySink(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void open() {
        // 初始化资源
    }

    @Override
    public void write(Result value) {
        // 写入数据
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO results VALUES ("
                + "'" + value.getKey() + "', "
                + value.getValue() + ")");
        }
    }

    @Override
    public void close() {
        // 释放资源
    }
}
```

## 端到端示例

### Kafka 到 Redis

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.fromKafka("localhost:9092", "input-topic", "group", new StringDeserializer())
    .map(String::toUpperCase)
    .keyBy(s -> s.substring(0, 1))
    .sum("value")
    .sinkToRedisStream("output-stream");

env.execute("KafkaToRedis");
```

### HTTP 到 Redis

```java
env.fromHttp("http://api.example.com/data", Duration.ofMinutes(1))
    .filter(data -> data.isValid())
    .map(Data::transform)
    .sinkToRedisHash("processed-data");
```

## 事务支持

支持 Exactly-Once 语义的 Sink。

### IdempotentSink

幂等 Sink，重复写入不产生副作用。

```java
stream.sinkTo(new IdempotentRedisListSink<>(
    redissonClient,
    "output",
    record -> record.getId(),  // 幂等键
    record::toString
));
```

### CheckpointAwareSink

与 Checkpoint 集成的 Sink。

```java
stream.sinkTo(new CheckpointAwareSink<Result>() {
    @Override
    public void write(Result value) {
        // 写入数据
    }

    @Override
    public void onCheckpointRestore(long checkpointId) {
        // 从 Checkpoint 恢复
    }
});
```

## 并行处理

### 分区写入

Sink 支持并行写入多个分区。

```java
stream.setParallelism(4)
    .keyBy(Result::getKey)
    .sinkToRedisHash("results");
```

### 负载均衡

使用分区键实现负载均衡。

```java
stream.keyBy(record -> record.getKey().hashCode() % 10)
    .sinkTo(new MultiPartitionSink(partitionSinks));
```

## 相关文档

- [MQ 模块](MQ.md) - 消息队列
- [Runtime 模块](Runtime.md) - 运行时环境
- [Checkpoint 模块](Checkpoint.md) - Checkpoint 机制
