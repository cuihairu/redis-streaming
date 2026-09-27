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
public interface WindowAssigner<T> extends Serializable {
    // 分配窗口（嵌套接口 Window 含 getStart()/getEnd()）
    Iterable<Window> assignWindows(T element, long timestamp);

    // 获取默认触发器（每个 key×window 桶调用一次，须返回新实例）
    Trigger<T> getDefaultTrigger();

    // 会话类可合并窗口覆写为 true
    default boolean supportsWindowMerging() { return false; }
}
```

### Trigger

触发器（core `WindowAssigner.Trigger`），决定窗口何时发射/丢弃。引擎按 (key, window) 桶从 `WindowAssigner.getDefaultTrigger()` 取**新实例**并调用：

```java
interface Trigger<T> {
    TriggerResult onElement(T element, long timestamp, Window window);   // 每个元素到达时
    TriggerResult onProcessingTime(long time, Window window);            // 处理时间定时器（当前无引擎接入）
    TriggerResult onEventTime(long time, Window window);                 // 水位线到达关闭时间时
}

enum TriggerResult {
    CONTINUE,         // 不发射，继续累积
    FIRE,             // 发射窗口结果，保留状态
    FIRE_AND_PURGE,   // 发射并清空窗口状态
    PURGE             // 不发射，直接丢弃窗口状态
}
```

两个执行引擎（InMemory 与 Redis）均已驱动 `onElement`/`onEventTime`：Redis 引擎在元素累加后与到期关闭前分别咨询触发器，默认 `EventTimeTrigger`（CONTINUE / FIRE_AND_PURGE）与纯水位线关闭行为等价；`onProcessingTime` 因两引擎均无 processing-time 窗口定时器而暂未调用（见 docs/watermark.md、todo.md B3）。

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
