# State 模块

模块目录:`state/`

## 概述

State 模块提供状态后端接口 `StateBackend` 及其 Redis 实现 `RedisStateBackend`,把 core 定义的四类状态接口落到 Redis 上。键为 `{keyPrefix}{状态名}`(默认前缀 `state:`),同一 Redis、同一前缀下的多个客户端共享同一份状态。

模块结构:

- `state.backend`:`StateBackend`(接口)
- `state.redis`:`RedisStateBackend`、`RedisValueState`、`RedisListState`、`RedisMapState`、`RedisSetState`

## 核心接口

### StateBackend

状态后端接口,负责创建各类状态实例(`io.github.cuihairu.redis.streaming.state.backend.StateBackend`):

```java
public interface StateBackend {
    <T> ValueState<T> createValueState(StateDescriptor<T> descriptor);
    <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType);
    <T> ListState<T> createListState(StateDescriptor<T> descriptor);
    <T> SetState<T> createSetState(StateDescriptor<T> descriptor);
    void close();
}
```

注意:接口方法均不抛受检异常;`RedisStateBackend.close()` 是空实现(`RedissonClient` 生命周期由外部管理)。

### 状态接口(core 模块 `api.state`)

```java
public interface State extends Serializable {
    void clear();
}

public interface ValueState<T> extends State {
    T value();               // 无值返回 null
    void update(T value);
}

public interface ListState<T> extends State {
    void add(T value);                       // 追加到列表尾部(RPUSH 语义)
    Iterable<T> get();
    void update(Iterable<T> values);         // 整体替换
    void addAll(Iterable<T> values);
}

public interface MapState<K, V> extends State {
    V get(K key);                            // 不存在返回 null
    void put(K key, V value);
    void remove(K key);
    boolean contains(K key);
    Iterable<Map.Entry<K, V>> entries();
    Iterable<K> keys();
    Iterable<V> values();
    boolean isEmpty();
}

public interface SetState<T> extends State {
    boolean add(T value);                    // 返回是否为新增
    boolean remove(T value);                 // 返回是否发生了删除
    boolean contains(T value);
    Iterable<T> get();
    boolean isEmpty();
    int size();
}
```

### StateDescriptor

状态描述符,定义状态名与类型(`io.github.cuihairu.redis.streaming.api.state.StateDescriptor`):

```java
public class StateDescriptor<T> implements Serializable {
    public StateDescriptor(String name, Class<T> type);
    public StateDescriptor(String name, Class<T> type, T defaultValue);
    public StateDescriptor(String name, Class<T> type, T defaultValue, int schemaVersion);

    public String getName();
    public Class<T> getType();
    public T getDefaultValue();
    public int getSchemaVersion();           // 默认 1,构造时按 Math.max(1, n) 截断
}
```

`MapState` 不走 `StateDescriptor`:`createMapState(name, keyType, valueType)` 直接以名字与键值类型创建。

## 配置项

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `RedisStateBackend` 构造参数 `keyPrefix` | `String` | `"state:"` | 状态键前缀,实际键 = `keyPrefix + 状态名` |
| `StateDescriptor.schemaVersion` | `int` | `1` | 状态演进版本号。state 模块的 `RedisStateBackend` 不读取它、不参与键名;runtime 的 `RedisKeyedStateStore` 用 `type.getName() + "\|" + schemaVersion` 做状态 schema 校验(不符时按 `RedisRuntimeConfig.stateSchemaMismatchPolicy` 处理,默认 `FAIL`,可选 `CLEAR`/`IGNORE`) |
| 各实现类构造参数 `key` | `String` | — | 实现类也可直接构造,如 `new RedisValueState<>(redisson, key, type)` |

`RedisStateBackend` 还提供 `getRedisson()` / `getKeyPrefix()`;四个 Redis 状态实现均提供 `getKey()`。

## 用法示例

### 1. 创建 StateBackend

```java
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import io.github.cuihairu.redis.streaming.state.backend.StateBackend;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379"); // 可用 REDIS_URL 覆盖
RedissonClient redisson = Redisson.create(config);

// 默认前缀 "state:"
StateBackend stateBackend = new RedisStateBackend(redisson);
// 或自定义前缀(examples 模块 StateExample 用 "example:state:")
StateBackend backend = new RedisStateBackend(redisson, "example:state:");
```

### 2. ValueState

```java
StateDescriptor<Integer> descriptor = new StateDescriptor<>("session-counter", Integer.class);
ValueState<Integer> counter = stateBackend.createValueState(descriptor);

Integer count = counter.value();      // 首次为 null
if (count == null) count = 0;
counter.update(count + 1);
counter.clear();                      // 删除该键
```

### 3. ListState

```java
import java.util.Arrays;
import java.util.List;

StateDescriptor<String> descriptor = new StateDescriptor<>("activity-log", String.class);
ListState<String> activityLog = stateBackend.createListState(descriptor);

activityLog.add("login");             // RPUSH 追加
activityLog.addAll(Arrays.asList("view_product", "checkout"));
for (String activity : activityLog.get()) { /* Iterable 遍历 */ }
activityLog.update(List.of("reset")); // 整体替换(原子批处理,见下文)
activityLog.clear();
```

### 4. MapState

```java
MapState<String, String> preferences =
        stateBackend.createMapState("user-preferences", String.class, String.class);

preferences.put("theme", "dark");
preferences.get("theme");             // "dark"
preferences.contains("theme");        // true
preferences.entries().forEach(e -> System.out.println(e.getKey() + "=" + e.getValue()));
preferences.keys();
preferences.values();
preferences.isEmpty();
preferences.remove("theme");
preferences.clear();
```

### 5. SetState

```java
StateDescriptor<String> descriptor = new StateDescriptor<>("unique-visitors", String.class);
SetState<String> visitors = stateBackend.createSetState(descriptor);

boolean isNew = visitors.add("user1");   // 首次 true,重复 add 返回 false
visitors.contains("user1");              // true
visitors.size();
for (String user : visitors.get()) { /* Iterable 遍历 */ }
visitors.remove("user1");
visitors.clear();
```

### 6. 在 KeyedStream 中使用状态(runtime 内存引擎)

状态访问入口是 `KeyedStream.getState(StateDescriptor)`,而不是 `KeyedProcessFunction.Context`(后者只有时间/定时器方法):

```java
import java.util.ArrayList;
import java.util.List;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
ValueState<Integer> count = keyed.getState(descriptor);

List<String> out = new ArrayList<>();
keyed.<String>process((key, value, ctx, collector) -> {
            int next = count.value() + 1;
            count.update(next);
            collector.collect(key + ":" + next);
        })
        .addSink(out::add);
// 结果:a:1, b:1, a:2(runtime 测试 StreamExecutionEnvironmentTest 同款用法)
```

Redis 引擎的键控状态由 runtime 自带的 `RedisKeyedStateStore` 管理(键前缀见 `RedisRuntimeConfig.stateKeyPrefix`,默认 `streaming:runtime`),不经过本模块的 `RedisStateBackend`,详见 [runtime.md](runtime.md)。

## Redis 数据结构

键 = `keyPrefix + 状态名`,例如默认前缀下 `state:session-counter`,自定义前缀 `example:state:` 下 `example:state:activity-log`:

| 状态 | Redisson API | Redis 类型 | 写路径 |
|---|---|---|---|
| `RedisValueState` | `RBucket.set/get/delete` | String(bucket) | `update` = SET,`clear` = DEL |
| `RedisListState` | `RList` | List | `add` = RPUSH;`addAll` = `RBatch`(REDIS_WRITE_ATOMIC)一次 `addAllAsync`;`update` = 同一批处理内 `DEL` + `addAllAsync`(原子替换,中途断连不会留下半写状态) |
| `RedisMapState` | `RMap` | Hash | `put` = HSET,`get` = HGET,`remove` = HDEL,`clear` = 清空该键内容 |
| `RedisSetState` | `RSet` | Set | `add` = SADD,`remove` = SREM,`clear` = 清空该键内容 |

## 设计说明

### 序列化

- 值的编解码由 `RedissonClient` 配置的 codec 决定,state 模块自身不设置 codec、不感知具体格式(`RedisStateBackendTest` 用 mock 客户端即可验证逻辑;集成测试用 `Redisson.create(config)` 默认配置)。
- `State extends Serializable`;存入的值需要能被所选 codec 序列化。

### 并发与原子性

- 单键读写直接使用 Redisson 对应结构的原子命令(SET/GET、RPUSH、HSET/HGET、SADD/SREM)。
- `RedisListState.update/addAll` 通过 `redisson.createBatch(BatchOptions.defaults().executionMode(REDIS_WRITE_ATOMIC))` 保证多步写入的原子性。

## 注意事项

1. `value()` / `get()` 无值返回 null,不存在"抛异常表示缺失"的路径;接口方法不抛受检异常。
2. `RedisStateBackend.close()` 为空实现,Redisson 客户端由调用方负责关闭。
3. `ListState.get()` 返回快照副本(`ArrayList` 拷贝),`SetState.get()` 返回 `HashSet` 拷贝;`MapState.entries()/keys()/values()` 直接透传 Redisson 视图。
4. 键由前缀+状态名决定:多个作业若共用同一 Redis 与前缀,会读写同一份状态,用 `keyPrefix` 区分。

## 相关文档

- [Checkpoint 模块](checkpoint.md) - 检查点与状态恢复
- [Runtime 模块](runtime.md) - 运行时环境
- [Core API](Core.md) - 状态接口定义
