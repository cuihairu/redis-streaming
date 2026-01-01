# Window 模块

## 概述

Window 模块提供窗口操作功能，支持将无限流分割成有限的窗口进行聚合计算。支持滚动窗口、滑动窗口和会话窗口。

## 窗口类型

### 1. TumblingWindow (滚动窗口)

窗口不重叠，每个窗口独立处理。

```java
DataStream<Event> stream = env.fromElements(...);

// 创建滚动窗口（1分钟）
stream.window(TumblingWindow.of(Duration.ofMinutes(1)))
      .aggregate(new MyAggregateFunction());
```

### 2. SlidingWindow (滑动窗口)

窗口有重叠，支持更平滑的数据分析。

```java
// 创建滑动窗口（窗口1分钟，每30秒滑动一次）
stream.window(SlidingWindow.of(
        Duration.ofMinutes(1),   // 窗口大小
        Duration.ofSeconds(30)   // 滑动步长
    ))
   .sum("value");
```

### 3. SessionWindow (会话窗口)

基于活动间隔动态划分窗口。

```java
// 创建会话窗口（30秒无活动则窗口结束）
stream.window(SessionWindow.withGap(Duration.ofSeconds(30)))
      .process(new MyProcessFunction());
```

## 核心组件

### WindowAssigner

窗口分配器，决定元素属于哪些窗口。

```java
public interface WindowAssigner<T, W extends Window> {
    // 分配窗口
    Iterable<W> assignWindows(T element, long timestamp);

    // 获取默认触发器
    Trigger<T> getDefaultTrigger();
}
```

### Trigger

触发器，决定何时触发窗口计算。

```java
public interface Trigger<T> {
    // 检查是否可以触发
    boolean canTrigger(Watermark watermark);

    // 处理元素
    void onElement(T element) throws Exception;
}
```

## 使用方式

### 1. 基本窗口聚合

```java
DataStream<Event> stream = env.fromElements(...);

stream.keyBy(Event::getKey)
      .window(TumblingWindow.of(Duration.ofMinutes(1)))
      .aggregate(Aggregates.sum("value"));
```

### 2. 窗口处理函数

```java
stream.window(SlidingWindow.of(Duration.ofHours(1), Duration.ofMinutes(30)))
      .process(new ProcessWindowFunction<String, Event, Result>() {
          @Override
          public void process(String key,
                            Context context,
                            Iterable<Event> elements,
                            Collector<Result> out) {
              // 处理窗口内所有元素
              long count = 0;
              for (Event e : elements) {
                  count++;
              }
              out.collect(new Result(key, count));
          }
      });
```

### 3. 迟到数据处理

```java
stream.window(TumblingWindow.of(Duration.ofMinutes(1)))
      .allowedLateness(Duration.ofSeconds(30))  // 允许30秒迟到
      .sideOutputLateData(lateDataTag)         // 输出到侧输出流
      .sum("value");
```

## 时间语义

### Event Time (事件时间)

基于事件本身的时间戳，处理乱序事件。

```java
stream.assignTimestamps((event) -> event.getTimestamp())
      .window(TumblingWindow.of(Duration.ofMinutes(1)))
      .trigger(EventTimeTrigger.create());
```

### Processing Time (处理时间)

基于系统时间，不关心事件时间。

```java
stream.window(TumblingWindow.of(Duration.ofMinutes(1)))
      .trigger(ProcessingTimeTrigger.create());
```

## 相关文档

- [Watermark 模块](Watermark.md) - 水位线机制
- [Aggregation 模块](Aggregation.md) - 聚合函数
