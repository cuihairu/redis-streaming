# Watermark 模块

## 概述

Watermark 模块提供水位线生成机制，用于处理事件时间和乱序事件。水位线表示事件时间的进度，用于触发窗口计算和保证结果的正确性。

## 核心概念

### Watermark (水位线)

水位线是一个时间戳，表示所有时间戳小于该值的事件都已经到达。用于：
- 触发窗口计算
- 处理乱序事件
- 保证结果完整性

### 事件时间 vs 处理时间

- **事件时间**: 事件发生的时间（如日志时间戳）
- **处理时间**: 事件被处理的时间（系统时间）

## 核心接口

### WatermarkGenerator

```java
public interface WatermarkGenerator {
    // 生成水位线
    Watermark getCurrentWatermark();

    // 处理事件
    void onEvent(long timestamp);

    // 处理周期性调用
    void onPeriodicEmit();
}
```

### TimestampAssigner

```java
@FunctionalInterface
public interface TimestampAssigner<T> {
    long extract(T element, long recordTimestamp);
}
```

## 内置实现

### AscendingTimestampWatermarks

递增时间戳水位线生成器，适用于严格有序的事件流。

```java
AscendingTimestampWatermarks watermarks = new AscendingTimestampWatermarks();
```

### BoundedOutOfOrdernessWatermarks

有界乱序水位线生成器，允许一定程度的乱序。

```java
long maxOutOfOrderness = 5000; // 5秒
BoundedOutOfOrdernessWatermarks watermarks = new BoundedOutOfOrdernessWatermarks(maxOutOfOrderness);
```

## 使用方式

### 1. 分配时间戳

```java
DataStream<Event> stream = env.addSource(source);

// 分配事件时间
stream.assignTimestampsAndWatermarks(
    (event, timestamp) -> event.getTimestamp(),  // 提取时间戳
    new BoundedOutOfOrdernessWatermarks(5000)    // 水位线生成器
);
```

### 2. 处理乱序事件

```java
DataStream<Event> stream = env.fromElements(...);

stream.assignTimestampsAndWatermarks(
    TimestampAssignerSupplier.of((event) -> event.getTimestamp()),
    WatermarkStrategySupplier.forBoundedOutOfOrderness(Duration.ofSeconds(5))
);
```

## 水位线传播

水位线在数据流中传播：
1. 源算子生成初始水位线
2. 每个算子更新水位线
3. 下游算子收到上游最小水位线
4. 窗口算子根据水位线触发计算

## 相关文档

- [Window 模块](Window.md) - 窗口操作
- [Core API](Core.md) - 核心接口
