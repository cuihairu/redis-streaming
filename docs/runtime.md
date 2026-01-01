# Runtime 模块

## 概述

Runtime 模块提供流处理框架的运行时环境，包括内存运行时和 Redis 运行时。内存运行时用于开发和测试，Redis 运行时用于生产环境。

## 核心组件

### 1. StreamExecutionEnvironment

流处理执行环境，是构建流处理应用的入口。

```java
// 创建执行环境
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// 从源创建数据流
DataStream<String> stream = env.fromElements("a", "b", "c");

// 执行转换
stream.map(String::toUpperCase)
      .print();

// 执行作业
env.execute("MyJob");
```

### 2. DataStream

数据流核心接口，提供各种转换操作。

```java
DataStream<String> stream = env.fromElements(...);

// 转换操作
stream.filter(s -> s.length() > 0)
      .map(String::toUpperCase)
      .flatMap(s -> Arrays.asList(s.split("")))
      .keyBy(s -> s.substring(0, 1));
```

### 3. RedisRuntime

基于 Redis 的分布式运行时，支持：
- 分布式状态管理
- Checkpoint 协调
- 水位线传播
- 并行处理

**配置**:
```java
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
    .redisUrl("redis://localhost:6379")
    .checkpointInterval(Duration.ofMinutes(1))
    .stateTtl(Duration.ofHours(24))
    .build();

RedisRuntime runtime = new RedisRuntime(config);
```

## 使用方式

### 1. 基本流处理作业

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.fromElements("hello", "world", "redis", "streaming")
   .map(String::toUpperCase)
   .filter(s -> s.startsWith("R"))
   .collect()
   .forEach(System.out::println);
```

### 2. 键控流处理

```java
env.fromElements(
        new Order("user1", "item1", 100),
        new Order("user1", "item2", 200),
        new Order("user2", "item1", 150)
    )
    .keyBy(Order::getUserId)
    .sum("amount")
    .print();
```

### 3. 窗口聚合

```java
env.fromElements(events)
    .assignTimestamps(e -> e.getTimestamp())
    .keyBy(Event::getKey)
    .window(TumblingWindow.of(Duration.ofMinutes(1)))
    .aggregate(Aggregates.sum("value"))
    .print();
```

### 4. 使用状态

```java
env.fromElements(words)
    .keyBy(w -> w)
    .process((key, value, ctx, out) -> {
        StateDescriptor<Long> descriptor = new StateDescriptor<>(
            "count", Long.class, 0L
        );
        ValueState<Long> countState = ctx.getState(descriptor);

        Long count = countState.value() + 1;
        countState.update(count);

        out.collect(key + ": " + count);
    });
```

### 5. Redis 运行时

```java
// 创建 Redis 运行时
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
    .redisUrl("redis://localhost:6379")
    .build();

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);

// 构建作业
env.fromKafka("orders")
    .keyBy(Order::getUserId)
    .sum("amount")
    .sinkToRedis("user-totals");

// 执行
env.execute("OrderAggregation");
```

## 运行模式

### 内存模式

- **用途**: 开发、测试
- **特点**: 单线程、内存状态
- **启动**: 默认模式

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
```

### Redis 模式

- **用途**: 生产环境
- **特点**: 分布式、持久化状态
- **启动**: 需要配置

```java
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
    .redisUrl("redis://localhost:6379")
    .build();

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
```

## 并行处理

### 并行度

设置作业的并行度，实现水平扩展。

```java
env.fromKafka("events")
    .setParallelism(4)  // 4个并行子任务
    .process(new MyProcessor())
    .sinkToRedis("results");
```

### 分区策略

- **KeyedStream**: 按 key 分区
- **DataStream**: 轮询分区

```java
// KeyedStream 自动按 key 分区
stream.keyBy(Event::getKey)
      .map(Event::getValue);
```

## Checkpoint 集成

### 启用 Checkpoint

```java
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
    .checkpointInterval(Duration.ofMinutes(1))
    .checkpointsToKeep(3)
    .build();

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
env.enableCheckpointing();
```

### 故障恢复

```java
env.fromKafka("events")
    .enableCheckpointing()
    .process(new MyProcessor())
    .execute();

// 故障后重启
env.execute("MyJob");  // 自动从最近的 Checkpoint 恢复
```

## 监控指标

Runtime 模块暴露以下指标：

- **吞吐量**: 每秒处理的事件数
- **延迟**: 端到端处理延迟
- **Checkpoint**: Checkpoint 成功率、耗时
- **状态**: 状态读写次数、大小

### Prometheus 集成

```java
// 指标自动暴露到 Prometheus
RedisRuntimeConfig config = RedisRuntimeConfig.builder()
    .enableMetrics(true)
    .metricsPort(9090)
    .build();
```

## 注意事项

1. **线程安全**: DataStream 操作是线程安全的
2. **资源清理**: 执行完成后自动清理资源
3. **异常处理**: 算子中的异常会导致作业失败
4. **状态大小**: 避免单个状态过大，影响性能

## 相关文档

- [State 模块](State.md) - 状态管理
- [Checkpoint 模块](Checkpoint.md) - 检查点机制
- [Core API](Core.md) - 核心接口
