# State 模块

## 概述

State 模块提供基于 Redis 的分布式状态管理后端，实现流处理框架中的状态持久化。支持值状态、列表状态、映射状态和集合状态，为有状态流处理提供可靠的数据存储。

## 核心功能

### 1. 状态类型

- **ValueState**: 单值状态，存储单个值
- **ListState**: 列表状态，存储元素列表
- **MapState**: 映射状态，存储键值对集合
- **SetState**: 集合状态，存储不重复元素集合

### 2. 状态特性

- **持久化**: 所有状态持久化到 Redis
- **分布式**: 支持多节点共享状态
- **类型安全**: 支持泛型，保持类型安全
- **自动序列化**: 自动处理对象的序列化和反序列化

### 3. 生命周期管理

- **创建**: 通过 StateBackend 创建状态实例
- **更新**: 支持状态的增删改查操作
- **清理**: 支持状态的清空和删除
- **关闭**: 释放资源

## 核心接口

### StateBackend

状态后端接口，负责创建和管理各种类型的状态。

```java
public interface StateBackend {
    // 创建值状态
    <T> ValueState<T> createValueState(StateDescriptor<T> descriptor);

    // 创建映射状态
    <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType);

    // 创建列表状态
    <T> ListState<T> createListState(StateDescriptor<T> descriptor);

    // 创建集合状态
    <T> SetState<T> createSetState(StateDescriptor<T> descriptor);

    // 关闭状态后端
    void close();
}
```

### ValueState

值状态接口，存储单个值。

```java
public interface ValueState<T> extends State {
    // 获取值
    T get() throws IOException;

    // 更新值
    void update(T value) throws IOException;

    // 清空值
    void clear() throws IOException;
}
```

### ListState

列表状态接口，存储有序元素列表。

```java
public interface ListState<T> extends State {
    // 获取所有元素
    List<T> get() throws IOException;

    // 添加元素
    void add(T value) throws IOException;

    // 添加所有元素
    void addAll(List<T> values) throws IOException;

    // 更新列表
    void update(List<T> values) throws IOException;

    // 清空列表
    void clear() throws IOException;
}
```

### MapState

映射状态接口，存储键值对集合。

```java
public interface MapState<K, V> extends State {
    // 获取值
    V get(K key) throws IOException;

    // 添加所有元素
    void putAll(Map<K, V> map) throws IOException;

    // 更新值
    void put(K key, V value) throws IOException;

    // 删除值
    void remove(K key) throws IOException;

    // 获取所有条目
    List<Map.Entry<K, V>> entries() throws IOException;

    // 检查是否为空
    boolean isEmpty() throws IOException;

    // 清空映射
    void clear() throws IOException;
}
```

### SetState

集合状态接口，存储不重复元素集合。

```java
public interface SetState<T> extends State {
    // 获取所有元素
    List<T> get() throws IOException;

    // 添加元素
    void add(T value) throws IOException;

    // 添加所有元素
    void addAll(List<T> values) throws IOException;

    // 检查包含
    boolean contains(T value) throws IOException;

    // 移除元素
    void remove(T value) throws IOException;

    // 清空集合
    void clear() throws IOException;
}
```

## 使用方式

### 1. 创建 StateBackend

```java
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import org.redisson.api.RedissonClient;

// 创建 Redis 状态后端
RedissonClient redissonClient = Redisson.create();
StateBackend stateBackend = new RedisStateBackend(redissonClient);
```

### 2. 使用 ValueState

```java
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;

// 创建值状态
StateDescriptor<Long> descriptor = new StateDescriptor<>(
    "counter",
    Long.class,
    0L  // 初始值
);
ValueState<Long> counterState = stateBackend.createValueState(descriptor);

// 更新值
counterState.update(100L);

// 获取值
Long value = counterState.get();
System.out.println("Counter: " + value);

// 清空值
counterState.clear();
```

### 3. 使用 ListState

```java
import io.github.cuihairu.redis.streaming.api.state.ListState;

// 创建列表状态
StateDescriptor<String> descriptor = new StateDescriptor<>(
    "events",
    String.class
);
ListState<String> eventList = stateBackend.createListState(descriptor);

// 添加元素
eventList.add("event1");
eventList.add("event2");

// 批量添加
eventList.addAll(Arrays.asList("event3", "event4"));

// 获取所有元素
List<String> events = eventList.get();
System.out.println("Events: " + events);

// 更新列表
eventList.update(Arrays.asList("new-event1", "new-event2"));
```

### 4. 使用 MapState

```java
import io.github.cuihairu.redis.streaming.api.state.MapState;

// 创建映射状态
MapState<String, Integer> countMap = stateBackend.createMapState(
    "word-count",
    String.class,
    Integer.class
);

// 添加键值对
countMap.put("hello", 1);
countMap.put("world", 2);

// 批量添加
Map<String, Integer> batch = new HashMap<>();
batch.put("foo", 3);
batch.put("bar", 4);
countMap.putAll(batch);

// 获取值
Integer count = countMap.get("hello");
System.out.println("'hello' count: " + count);

// 获取所有条目
List<Map.Entry<String, Integer>> entries = countMap.entries();
entries.forEach(entry -> {
    System.out.println(entry.getKey() + ": " + entry.getValue());
});

// 删除键
countMap.remove("world");
```

### 5. 使用 SetState

```java
import io.github.cuihairu.redis.streaming.api.state.SetState;

// 创建集合状态
StateDescriptor<String> descriptor = new StateDescriptor<>(
    "unique-users",
    String.class
);
SetState<String> userSet = stateBackend.createSetState(descriptor);

// 添加元素
userSet.add("user1");
userSet.add("user2");

// 批量添加
userSet.addAll(Arrays.asList("user3", "user4"));

// 检查包含
boolean exists = userSet.contains("user1");
System.out.println("user1 exists: " + exists);

// 获取所有元素
List<String> users = userSet.get();
System.out.println("Users: " + users);

// 移除元素
userSet.remove("user2");
```

### 6. 在 KeyedStream 中使用状态

```java
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.fromElements("hello", "world", "hello", "world")
    .keyBy(word -> word)
    .process((key, value, ctx, out) -> {
        // 获取状态
        StateDescriptor<Long> descriptor = new StateDescriptor<>(
            "word-count",
            Long.class,
            0L
        );
        ValueState<Long> countState = ctx.getState(descriptor);

        // 读取并更新状态
        Long current = countState.value();
        Long next = current == null ? 1L : current + 1;
        countState.update(next);

        out.collect(key + ": " + next);
    });
```

## StateDescriptor

状态描述符，定义状态的元数据。

```java
public class StateDescriptor<T> {
    private String name;          // 状态名称
    private Class<T> type;         // 状态类型
    private T defaultValue;        // 默认值

    public StateDescriptor(String name, Class<T> type, T defaultValue) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    // Getters
    public String getName() { return name; }
    public Class<T> getType() { return type; }
    public T getDefaultValue() { return defaultValue; }
}
```

## Redis 数据结构

状态后端使用以下 Redis 数据结构：

### ValueState

- **Key**: `state:{jobId}:{operatorId}:{key}:{stateName}`
- **Type**: String
- **Value**: 序列化的状态值

### ListState

- **Key**: `state:{jobId}:{operatorId}:{key}:{stateName}`
- **Type**: List
- **Elements**: 序列化的元素列表

### MapState

- **Key**: `state:{jobId}:{operatorId}:{key}:{stateName}`
- **Type**: Hash
- **Fields**: 序列化的键
- **Values**: 序列化的值

### SetState

- **Key**: `state:{jobId}:{operatorId}:{key}:{stateName}`
- **Type**: Set
- **Members**: 序列化的元素

## 设计说明

### 1. 状态键设计

状态键采用层次化设计，支持多租户和多实例：

```
state:{jobId}:{operatorId}:{key}:{stateName}
```

- `jobId`: 作业ID
- `operatorId`: 算子ID
- `key`: 键控流的键
- `stateName`: 状态名称

### 2. 序列化

状态使用 JSON 进行序列化：

- 优点: 可读性好，易于调试
- 缺点: 性能略低于二进制序列化
- 适用场景: 状态数据不大，性能要求不极端

### 3. 并发控制

状态操作使用 Redis 的原子操作保证一致性：

- ValueState: 使用 SET 命令
- ListState: 使用 LPUSH/RPUSH 命令
- MapState: 使用 HSET/HGET 命令
- SetState: 使用 SADD 命令

## 注意事项

1. **状态大小**: 单个状态不宜过大，建议控制在 10MB 以内
2. **序列化**: 状态对象必须可序列化
3. **空值处理**: get() 操作返回 null 表示状态不存在
4. **异常处理**: 状态操作可能抛出 IOException
5. **资源清理**: 使用完毕后应调用 close() 释放资源

## 性能优化

1. **批量操作**: 使用 addAll/putAll 等批量操作减少网络往返
2. **本地缓存**: 在高频访问场景考虑引入本地缓存
3. **Pipeline**: 使用 Redis Pipeline 批量执行命令
4. **连接池**: 合理配置 Redisson 连接池大小

## 相关文档

- [Checkpoint 模块](Checkpoint.md) - 检查点与状态恢复
- [Runtime 模块](Runtime.md) - 运行时环境
- [Core API](Core.md) - 核心接口定义
