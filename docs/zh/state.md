# State 模块

模块目录:`state/`

## 概述

State 模块提供状态后端接口 `StateBackend` 与其 Redis 实现 `RedisStateBackend`。自双执行引擎状态统一第 3 步起(方案与分期见 [State-Unification-Design.md](State-Unification-Design.md)),`RedisStateBackend` 是 runtime `RedisKeyedStateStore` 之上的**薄适配层**:模块内不再有独立的状态实现,四类状态一律落到 runtime keyed 布局——所有状态键登记进作业的 `stateKeys` 索引与 `stateSchema` 登记簿,checkpoint 快照机制可直接覆盖。

适配层语义:backend 内部持有一个绑定**固定 solo 身份**的 keyed store(topic/consumerGroup/operatorId 均为 `standalone`、partition 0),每次操作前后把当前 key 钉为常量 `solo`(线程局部,操作结束即清除),于是无键控的 `StateBackend` 契约照常工作,而数据落在标准 keyed 布局里。

模块结构:

- `state.backend`:`StateBackend`(接口)
- `state.redis`:`RedisStateBackend`(适配层)

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
    T value();               // 无值返回 null(descriptor 带 defaultValue 时返回默认值)
    void update(T value);
}

public interface ListState<T> extends State {
    void add(T value);                       // 追加到列表尾部(RPUSH 语义)
    Iterable<T> get();
    void update(Iterable<T> values);         // 整体替换(原子批处理)
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
| `RedisStateBackend` 构造参数 `jobName` | `String` | — | 作业名,状态键命名空间根为 `{stateKeyPrefix}:{jobName}` |
| `RedisStateBackend` 构造参数 `stateKeyPrefix` | `String` | `"streaming:runtime"` | 状态键前缀(与 `RedisRuntimeConfig.stateKeyPrefix` 默认一致) |
| `StateDescriptor.schemaVersion` | `int` | `1` | 写入 stateSchema(`type.getName() + "\|" + schemaVersion`);不匹配按 FAIL 抛错(适配层固定,不可配) |

`RedisStateBackend` 还提供 `getRedisson()` / `getKeyPrefix()`。

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

StateBackend backend = new RedisStateBackend(redisson);                        // jobName "default"
StateBackend myBackend = new RedisStateBackend(redisson, "my-job");            // 默认前缀 streaming:runtime
StateBackend pfxBackend = new RedisStateBackend(redisson, "my-job", "my:pfx"); // 自定义前缀
```

### 2. ValueState

```java
StateDescriptor<Integer> descriptor = new StateDescriptor<>("session-counter", Integer.class);
ValueState<Integer> counter = stateBackend.createValueState(descriptor);

Integer count = counter.value();      // 首次为 null(带 defaultValue 时为默认值)
if (count == null) count = 0;
counter.update(count + 1);
counter.clear();                      // 删除该键的字段
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
activityLog.update(List.of("reset")); // 整体替换(单次 REDIS_WRITE_ATOMIC 批,见下文)
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

状态访问入口有二:

- `KeyedStream.getState(StateDescriptor)`——构建期在算子外持有状态句柄;
- `KeyedProcessFunction.Context.getState(StateDescriptor)`——`processElement` 内按当前 key 读写(默认方法,默认抛 `UnsupportedOperationException`,两引擎均覆写接线;join/cep 算子即走此通道,见 [Join/CEP 算子化设计](Join-CEP-Operators-Design.md) §3.4)。

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

Redis 引擎的键控状态由 runtime 自带的 `RedisKeyedStateStore` 管理(键前缀见 `RedisRuntimeConfig.stateKeyPrefix`,默认 `streaming:runtime`);本模块的 `RedisStateBackend` 适配到同一个 store 与同一套键布局,区别仅是 solo 身份与固定 partition 0。

## Redis 数据结构(新布局)

作业命名空间根 `{stateKeyPrefix}:{jobName}`,solo 身份固定 `cg:standalone / topic:standalone / p:0 / state:standalone`,`{base}` = `{stateKeyPrefix}:{jobName}:cg:standalone:topic:standalone:p:0:state:standalone`:

| 状态 | Redis 类型 | 键 | 字段 / 元素 |
|---|---|---|---|
| ValueState | Hash | `{base}:{stateName}` | field = `s:solo`,value = 状态值 JSON |
| MapState | Hash | `{base}:{name}:s:solo` | field = map 键 JSON,value = 值 JSON |
| ListState | List | `{base}:{name}:s:solo` | 元素 = JSON;`update`/`addAll` = 单次 `REDIS_WRITE_ATOMIC` 批(先 DEL 再 addAll,原子替换) |
| SetState | Set | `{base}:{name}:s:solo` | 成员 = JSON |

所有键在首次访问时登记进 `{stateKeyPrefix}:{jobName}:stateKeys`(checkpoint 索引)与 `{stateKeyPrefix}:{jobName}:stateSchema`(schema 登记簿,值为 `类型全名|schemaVersion`)。

## 迁移对照表(统一第 3 步,不兼容变更)

老实现(`RedisValueState` / `RedisMapState` / `RedisListState` / `RedisSetState`)已删除。**键布局与编解码均不兼容,新实现不读老数据**——这是设计预期(老数据无生产消费者)。确需保留老数据时按下表一次性自行搬运:

| | 老实现(已删) | 新实现(适配层) |
|---|---|---|
| 键 | `{keyPrefix}{stateName}`(默认前缀 `state:`,扁平单键) | `{prefix}:{job}:cg:standalone:topic:standalone:p:0:state:standalone:{name}[:s:solo]` |
| ValueState 结构 | String(RBucket 整值) | Hash 单字段(field `s:solo`) |
| ListState 结构 | List(RList) | List(结构同,键名变) |
| MapState 结构 | Hash(RMap,field = map 键) | Hash(field = map 键 JSON) |
| SetState 结构 | Set(RSet) | Set(结构同,键名变) |
| codec | Redisson 默认 `JsonJacksonCodec`(值带 `@class` 类型信息) | `StringCodec` + 纯 Jackson JSON(无类型信息,按 `StateDescriptor` 类型反序列化) |
| 构造参数 | `keyPrefix`(默认 `state:`) | `jobName` + `stateKeyPrefix`(默认 `streaming:runtime`) |
| 无值语义 | `value()` 返回 null | 同左;descriptor 带 defaultValue 时返回默认值 |
| 索引 / 元数据 | 无 | stateKeys 索引 + stateSchema 登记(可被 runtime checkpoint 快照) |
| TTL | 无 | 无(适配层固定 0) |

一次性搬运示例(仅 ValueState 为例;老值须按老 codec 的带类型 JSON 读回对象):

```java
// 旧:老键上的 RBucket(默认 JsonJacksonCodec,带 @class)
MyType old = redisson.getBucket("state:my-state").get();
// 新:适配层键上的标准布局
new RedisStateBackend(redisson, "my-job")
        .createValueState(new StateDescriptor<>("my-state", MyType.class))
        .update(old);
```

## 设计说明

### 序列化

- 值的编解码固定为 `StringCodec` + 纯 Jackson JSON(模块内 `ObjectMapper`,无类型信息):写入 `writeValueAsString`,读取按 `StateDescriptor` 的类型反序列化。序列化失败以 `RuntimeException("Failed to serialize/deserialize state value for ...")` 抛出。

### 并发与原子性

- 单键读写直接使用 Redisson 对应结构的原子命令(HGET/HSET、RPUSH、SADD/SREM)。
- `ListState.update/addAll` 通过 `redisson.createBatch(BatchOptions.defaults().executionMode(REDIS_WRITE_ATOMIC))` 保证多步写入的原子性(中途断连不会留下半写或清空后的状态,重试也不会重复追加)。
- 适配层每次操作前钉线程局部 current key / partition、操作后清除,因此同一个状态实例可安全地被多线程并发使用(各操作互不串键)。

### 与 runtime checkpoint 的关系

- 适配层状态落在作业命名空间的 keyed 布局中并登记 stateKeys,若该 `jobName` 与某 runtime 作业一致,其键会进入同一命名空间(适配层身份 `topic:standalone` 与真实流水线不同,正常不冲突)。跨引擎共享状态请显式复用同一 `jobName` + 前缀组合,并自行确认 partition 语义。

## 注意事项

1. `value()` / `get()` 无值返回 null,不存在"抛异常表示缺失"的路径;接口方法不抛受检异常。
2. `RedisStateBackend.close()` 为空实现,Redisson 客户端由调用方负责关闭。
3. `ListState.get()` 返回快照副本,`SetState.get()` 返回反序列化副本,`MapState.entries()/keys()/values()` 返回快照副本(不再透传 Redisson 视图)。
4. 键由 `jobName` + 前缀决定:多个客户端若共用同一 Redis、同一 `jobName` 与前缀,会读写同一份状态,用 `jobName` 区分。

## 相关文档

- [State-Unification-Design](State-Unification-Design.md) - 双执行引擎状态统一方案
- [Checkpoint 模块](checkpoint.md) - 检查点与状态恢复
- [Runtime 模块](runtime.md) - 运行时环境
- [Core API](Core.md) - 状态接口定义
