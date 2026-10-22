# Reliability 模块

Module: `reliability/`

## 模块职责

可靠性构建块：进程内死信队列与重试策略、Redis 去重、内存/Redis 限流器、带限流装饰的 Sink，以及两组静态指标挂钩。包根 `io.github.cuihairu.redis.streaming.reliability`。

主源码共 24 个文件，分四个包：

| 包 | 文件数 | 内容 |
|---|---|---|
| `reliability`（根包） | 6 | `DeadLetterQueue`、`FailedElement`、`FailureStrategy`、`ReliabilityConfig`、`RetryPolicy`、`RetryExecutor` |
| `reliability.deduplication` | 5 | `Deduplicator`、`SetDeduplicator`、`BloomFilterDeduplicator`、`WindowedDeduplicator`、`DeduplicatorFactory` |
| `reliability.ratelimit` | 9 | `RateLimiter`、`NamedRateLimiter`、`RateLimiterRegistry`、`RateLimitingSink`、3 个内存实现、2 个 Redis 实现 |
| `reliability.metrics` | 4 | `ReliabilityMetrics`、`ReliabilityMetricsCollector`、`RateLimitMetrics`、`RateLimitMetricsCollector` |

边界说明：

- 根包的 `DeadLetterQueue` 是**纯内存**队列（`ConcurrentLinkedQueue`），不落 Redis。**Redis 侧的 DLQ 在 mq 模块**（`io.github.cuihairu.redis.streaming.mq.dlq` 的 `DeadLetterService` / `DeadLetterAdmin` / `DeadLetterConsumer`，存储为 Redis Stream，键默认 `stream:topic:{topic}:dlq`，见 `DlqKeys.dlq(topic)`），见 [MQ](MQ.md)。
- 去重/限流的 Redis 实现依赖 Redisson（模块 `build.gradle` 中为 `implementation libs.redisson`）。
- Spring Boot 配置键不在本模块，见下方「Spring Boot 配置（starter）」。

## 失败策略与重试

### FailureStrategy（枚举）

`FAIL_FAST` / `RETRY` / `SKIP` / `DEAD_LETTER_QUEUE` / `IGNORE` 五种策略，是 `ReliabilityConfig` 的取值；本模块没有把这些策略接到某个执行引擎的调度器上，`ReliabilityConfig` + `RetryExecutor` + `DeadLetterQueue` 是供上层自行组装的构建块。

### RetryPolicy（重试策略）

`RetryPolicy implements Serializable`，Lombok `@Builder`，字段与 Builder 默认值：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `maxAttempts` | `int` | `3` | 重试次数（不含首次尝试） |
| `initialDelay` | `Duration` | `Duration.ofMillis(100)` | 首次重试前的等待 |
| `maxDelay` | `Duration` | `Duration.ofSeconds(10)` | 单次等待上限（毫秒封顶） |
| `backoffMultiplier` | `double` | `2.0` | 指数退避倍数 |
| `exponentialBackoff` | `boolean` | `true` | 是否启用指数退避 |
| `retryableExceptions` | `Class<? extends Exception>[]` | `null` | 白名单：一旦设置，只有命中才重试 |
| `nonRetryableExceptions` | `Class<? extends Exception>[]` | `null` | 黑名单：命中则不重试（优先判定） |

关键方法：

```java
long getDelayForAttempt(int attemptNumber); // 1-based；delay = initialDelay * backoffMultiplier^(n-1)，封顶 maxDelay；n<=0 返回 0
boolean isRetryable(Exception exception);   // 先黑名单、再白名单；两者都未设置时对所有异常返回 true
static RetryPolicy defaultPolicy();         // = RetryPolicy.builder().build()
static RetryPolicy noRetry();               // maxAttempts(0)
static RetryPolicy fixedDelay(int maxAttempts, Duration delay); // exponentialBackoff(false)
```

### RetryExecutor（执行重试）

```java
RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();  // 来自 RetryExecutorTest
RetryExecutor executor = new RetryExecutor(policy);

String result = executor.execute(input -> "success", "input");       // <T,R> R execute(Function<T,R>, T)
executor.execute((RetryExecutor.RunnableWithException) () -> {       // execute(RunnableWithException)
    attempts.incrementAndGet();
    throw new IllegalStateException("boom");
});
executor.getPolicy();
```

按异常类型过滤重试（来自 `RetryExecutorRunnableTest`）：

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(2)
        .initialDelay(Duration.ofMillis(1))
        .retryableExceptions(new Class[]{java.io.IOException.class})  // 只有 IOException 才重试
        // 或 .nonRetryableExceptions(new Class[]{IllegalStateException.class})
        .build();
```

行为要点（源码语义）：

- 总尝试次数 = `maxAttempts + 1`（首次 + `maxAttempts` 次重试），且不小于 1。
- 捕获到 `RuntimeException` 且其 cause 为 `Exception` 时先解包，再交给 `isRetryable` 判定（白/黑名单匹配的是根因）。
- 重试间隔用 `Thread.sleep`；被中断时恢复中断位并抛 `RuntimeException("Retry interrupted", ie)`。
- 全部尝试失败后抛出最后一个（解包后的）异常。

### ReliabilityConfig（失败处理配置）

`ReliabilityConfig<T> implements Serializable`，Lombok `@Builder`：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `failureStrategy` | `FailureStrategy` | `FailureStrategy.RETRY` | 失败策略 |
| `retryPolicy` | `RetryPolicy` | `RetryPolicy.defaultPolicy()` | RETRY 策略必填 |
| `deadLetterQueue` | `DeadLetterQueue<T>` | `null` | DEAD_LETTER_QUEUE 策略必填 |
| `onFailureCallback` | `Function<FailedElement<T>, Void>` | `null` | 失败回调（日志/指标） |
| `continueOnFailure` | `boolean` | `true` | 失败后是否继续 |
| `maxConsecutiveFailures` | `int` | `10` | 连续失败上限，必须为正 |

`validate()` 的四条规则（违规抛 `IllegalArgumentException`）：策略非空；`RETRY` 必须带 `retryPolicy`；`DEAD_LETTER_QUEUE` 必须带 `deadLetterQueue`；`maxConsecutiveFailures > 0`。

静态工厂：`withRetry()`、`withDeadLetterQueue(int queueSize)`、`failFast()`（`FAIL_FAST` + `continueOnFailure(false)`）。

### DeadLetterQueue / FailedElement（进程内死信队列）

```java
DeadLetterQueue<String> dlq = new DeadLetterQueue<>();      // 无上限（Integer.MAX_VALUE）
DeadLetterQueue<String> small = new DeadLetterQueue<>(2);   // maxSize 必须 > 0，否则抛 IllegalArgumentException（B-35）

dlq.add("element1", exception, 1);        // boolean：队满返回 false（CAS 占位，不会越过 maxSize）
FailedElement<String> failed = dlq.poll(); // 取出并减计数；空则 null
dlq.peek(); dlq.getAll(); dlq.size(); dlq.isEmpty(); dlq.isFull(); dlq.clear(); dlq.getMaxSize();

failed.getElement(); failed.getException(); failed.getErrorMessage(); // 异常消息为空时为 "Unknown error"
failed.getTimestamp(); failed.getAttemptCount();
failed.getStackTrace();   // 字符串形式堆栈；exception 为 null 时返回 ""
failed.canRetry(5);       // attemptCount < maxAttempts
```

`clear()` 是逐元素 poll + 计数递减（而非 `queue.clear()+置 0`），保证与并发 add 的计数一致（B-35）。队列元素类型是 `FailedElement<T>`（Lombok `@Data`，`Serializable`）。

`FailureStrategy` 与 `ReliabilityConfig` 的典型用法（来自 `ReliabilityConfigTest`）：

```java
ReliabilityConfig<String> config = ReliabilityConfig.<String>withDeadLetterQueue(1000);
config.validate();                                  // DEAD_LETTER_QUEUE 策略校验队列已配置
config.getDeadLetterQueue().add("payload", new IllegalStateException("boom"), 3);
```

## 消息去重

### Deduplicator 接口

```java
public interface Deduplicator<T> {
    boolean isDuplicate(T element);
    void markAsSeen(T element);
    default boolean checkAndMark(T element); // 默认实现：isDuplicate + markAsSeen（非原子；请用覆写版本）
    void clear();
    long getUniqueCount();
}
```

三个实现都以 Redis 存储、以 `Function<T, String> keyExtractor` 抽取去重键；`element == null` 时一律按「非重复」返回且不写入。

### SetDeduplicator（精确去重）

```java
SetDeduplicator<String> d = new SetDeduplicator<>(redisson, "dedup", s -> s);  // 来自 DeduplicatorsTest
d.checkAndMark("x");   // 底层 RSet.add：首次 false（新），再次 true（重复）——跨进程也是原子的
d.isDuplicate("x");
d.getUniqueCount();    // RSet.size()
d.containsKey("k"); d.remove("k"); d.clear();
```

存储为 Redis Set。精确、无假阳性；键**没有 TTL**，长期运行需要自行 `clear()`/`remove()`。跨进程需要「检查并标记」原子性时用它（Bloom 过滤器做不到，见下）。

### BloomFilterDeduplicator（概率去重）

```java
// 默认假阳性率 0.03
BloomFilterDeduplicator<String> d1 = new BloomFilterDeduplicator<>(redisson, "bf", 10, s -> "k1");
// 指定假阳性率
BloomFilterDeduplicator<String> d2 = new BloomFilterDeduplicator<>(redisson, "bf", 10, 0.001, s -> "k1");
// 或工厂
DeduplicatorFactory.createBloomFilter(redisson, "bf", 10L, s -> "k1");
DeduplicatorFactory.createBloomFilter(redisson, "bf", 10L, 0.001, s -> "k1");
```

- 构造校验：`expectedInsertions <= 0` 或 `falseProbability` 不在 `(0, 1)` 开区间 → `IllegalArgumentException`；首次构造对 Redis 键 `tryInit`（已存在则不重复初始化）。
- `checkAndMark` 在**同一进程**内用 `synchronized` 保证原子（B-19）；Redisson 的 `RBloomFilter` 没有服务端 check-and-add，**跨进程**仍可能双双看到「新元素」——跨进程精确去重用 `SetDeduplicator`。
- `getUniqueCount()` 是本进程 `add` 成功次数的本地计数（不是 Redis 端精确基数）；`count()` 返回布隆过滤器的近似计数；`getExpectedFalseProbability()` 读回假阳性率。
- `clear()` 先删键再 `tryInit`（否则删除后过滤器未初始化导致后续操作抛错，B-12）。

### WindowedDeduplicator（时间窗口去重）

```java
WindowedDeduplicator<String> d = new WindowedDeduplicator<>(redisson, "dedup:orders", Duration.ofHours(1), s -> s);
if (!d.checkAndMark(event)) {  // 新元素 → false
    process(event);
}
d.getWindowDuration();  // Duration
d.getRemainingTTL();    // 键剩余 TTL 毫秒；-1 无过期、-2 键不存在
d.getUniqueCount();     // 剪枝后窗口内元素数
d.clear();
```

存储为 Redis Sorted Set，score = 最后一次出现的毫秒时间戳：

- 每个元素在「最后出现 + 窗口时长」后过期（写入时按分数剪枝，集合规模 ≈ 流量 × 窗口）；窗口边界为**开区间**：恰好到达窗口时长的元素不再算重复。
- `checkAndMark` 用 `ZADD NX` 原子判定，随后刷新已存在元素的最后出现时间（B-09）。
- 整键另盖一个兜底 TTL = `windowDuration + TTL_MARGIN`（`TTL_MARGIN = Duration.ofSeconds(60)`），流量停后回收整键。
- 首次访问会迁移旧版普通 Set 布局（成员以迁移时刻作为最后出现时间）。
- 构造校验：`redissonClient`/`name`/`windowDuration`/`keyExtractor` 非空，`windowDuration` 必须为正，否则 `IllegalArgumentException`。

### DeduplicatorFactory

```java
DeduplicatorFactory.createBloomFilter(redisson, name, expectedInsertions, keyExtractor);
DeduplicatorFactory.createBloomFilter(redisson, name, expectedInsertions, falseProbability, keyExtractor);
DeduplicatorFactory.createSet(redisson, name, keyExtractor);
DeduplicatorFactory.createWindowed(redisson, name, windowDuration, keyExtractor);

// 按策略创建（策略默认参数写死在 create 内）
Deduplicator<String> d = DeduplicatorFactory.create(
        DeduplicatorFactory.DeduplicationStrategy.WINDOWED, redisson, "name", s -> s);
```

`DeduplicationStrategy` 枚举与 `create(strategy, ...)` 的默认参数：

| 策略 | 实现 | 默认参数 |
|---|---|---|
| `BLOOM_FILTER` | `BloomFilterDeduplicator` | `expectedInsertions = 1_000_000`（假阳性率 0.03） |
| `SET` | `SetDeduplicator` | 无（精确集合） |
| `WINDOWED` | `WindowedDeduplicator` | `Duration.ofHours(1)` |

未知策略抛 `IllegalArgumentException`。

## 限流

### RateLimiter 接口

```java
public interface RateLimiter {
    default boolean allow(String key) { return allowAt(key, System.currentTimeMillis()); }
    boolean allowAt(String key, long nowMillis);  // true = 放行；false = 触发限流
}
```

`key` 是逻辑桶键（如 userId/IP）。没有 `tryAcquire()` 之类的方法，判定点是 `allow` / `allowAt`。

### 内存实现（进程内，单机或测试用）

| 类 | 构造 | 参数校验 |
|---|---|---|
| `InMemorySlidingWindowRateLimiter` | `(long windowMs, int limit)` | `windowMs > 0`、`limit > 0` |
| `InMemoryTokenBucketRateLimiter` | `(double capacity, double ratePerSecond)` | `capacity > 0`、`ratePerSecond > 0` |
| `InMemoryLeakyBucketRateLimiter` | `(double capacity, double leakRatePerSecond)` | `capacity > 0`、`leakRatePerSecond > 0` |

- 滑动窗口：每键保存时间戳队列，剔除 `<= now - windowMs` 的旧时间戳后 `size < limit` 才放行。
- 令牌桶：每键按毫秒精度补充令牌，**新键初始满容量**（允许初始突发），令牌不足即拒绝。
- 漏桶：按 `leakRatePerSecond` 排水，`water + 1 <= capacity` 才放行，**新键初始为空**。
- 三个实现都会驱逐「已走完整个状态」的键（阈值 `DEFAULT_SWEEP_THRESHOLD = 256`，包内可见的三参构造可传测试阈值），防止高基数 key 撑爆 map（B-34）；可用 `trackedKeyCount()` 观察。

```java
RateLimiter sliding = new InMemorySlidingWindowRateLimiter(1000, 5); // 5 次/秒
RateLimiter token = new InMemoryTokenBucketRateLimiter(10, 5);       // 突发 10、匀速 5/s
if (!sliding.allow("user:1")) { /* 拒绝 */ }
sliding.allowAt("user:1", 1_000L); // 测试/虚拟时间
```

### Redis 实现（多实例共享）

```java
RateLimiter rl = new RedisSlidingWindowRateLimiter(redisson, "streaming:rl", 1000, 100);
RateLimiter tb = new RedisTokenBucketRateLimiter(redisson, "streaming:tb", 100, 50);
```

- `RedisSlidingWindowRateLimiter(RedissonClient, String keyPrefix, long windowMs, int limit)`：`windowMs/limit` 必须为正；`keyPrefix` 为 null/空白时回退 `"streaming:rl"`。Lua 原子执行 `ZREMRANGEBYSCORE`（剪掉窗口外）→ `ZCARD` → 未满则 `ZADD`（成员 `now:seq`，seq 用同槽位的 `:seq` 键 INCRBY 生成）+ `PEXPIRE window`。数据键为 `keyPrefix + ":{" + key + "}"`，seq 键为其后缀 `:seq`（花括号 hash tag 保证 Cluster 同槽）。
- `RedisTokenBucketRateLimiter(RedissonClient, String keyPrefix, double capacity, double ratePerSecond)`：`capacity/ratePerSecond` 必须为正；`keyPrefix` 为 null/空白时回退 `"streaming:tb"`。Lua 在 HASH（字段 `tokens`/`ts`）上原子补充 + 消耗；键 TTL = `max(2000ms, 两次全量补充时长)`。数据键为 `keyPrefix + ":{" + key + "}:tb"`。
- 两者 `allowAt` 每次都执行一段 Lua；无 Lua 返回值异常处理（返回 null 按拒绝处理）。

### NamedRateLimiter / RateLimiterRegistry

```java
// 包装为具名限流器：每次 allowAt 通过 RateLimitMetrics 上报 incAllowed/incDenied
RateLimiter named = new NamedRateLimiter("sliding-demo", new InMemorySlidingWindowRateLimiter(1000, 5));

// 具名注册表：构造时防御性拷贝（Map.copyOf），get() 未注册返回 null，all() 为不可变视图
RateLimiterRegistry registry = new RateLimiterRegistry(
        Map.of("api1", new InMemorySlidingWindowRateLimiter(1000, 10),
               "api2", new InMemoryTokenBucketRateLimiter(100, 50)));
RateLimiter l = registry.get("api1");   // 可能为 null
registry.all();
```

### RateLimitingSink（给 StreamSink 加限流）

```java
public class RateLimitingSink<T> implements StreamSink<T> {
    public enum DenyPolicy { DROP, THROW }
    // denyPolicy 传 null 时按 DROP 处理
    public static <T> RateLimitingSink<T> drop(RateLimiter, Function<T,String> keySelector, StreamSink<T> delegate);
    public static <T> RateLimitingSink<T> throwing(RateLimiter, Function<T,String> keySelector, StreamSink<T> delegate);
}
```

`invoke(T value)`：按 `keySelector` 取键（null 键映射为字符串 `"null"`），放行则委托给 delegate；拒绝时 `DROP` 静默丢弃、`THROW` 抛内部类 `RateLimitingSink.RateLimitedException`（`RuntimeException`）。完整用法见下方示例。

## 指标挂钩

静态门面，默认实现为 Noop，`setCollector` 忽略 null：

```java
RateLimitMetrics.get().incAllowed(name);   // RateLimitMetricsCollector：incAllowed(name) / incDenied(name)
RateLimitMetrics.get().incDenied(name);
```

- `RateLimitMetrics` 的唯一产数点是 `NamedRateLimiter`（包装后每次判定都上报）。
- DLQ 的重放/删除/清空指标不走本模块：mq 模块的 DLQ 操作统一产数到 `mq.metrics.MqMetrics`，由 starter 的 `MqMicrometerCollector` 桥接到 Micrometer（`redis_streaming_dlq_replay_success_total` 等，见 docs/Metrics.md）。曾有独立的 `ReliabilityMetrics` 死桥（零调用者），已在指标统一 v1 中删除。
- Spring Boot starter 在 classpath 存在 Micrometer 且有 `MeterRegistry` 时自动安装 `RateLimitMicrometerCollector`，指标名：
  - `redis_streaming_rl_allowed_total` / `redis_streaming_rl_denied_total`（tag：`name` = 限流器名）

## Spring Boot 配置（starter）

自动配置类 `RedisStreamingRateLimitAutoConfiguration` 由 `redis-streaming.ratelimit.enabled=true` 激活；配置前缀 `redis-streaming`（`RedisStreamingProperties.RateLimitProperties`）：

| 配置键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `redis-streaming.ratelimit.enabled` | boolean | `false` | 开关（仅影响自动配置） |
| `...ratelimit.backend` | String | `memory` | `memory` 或 `redis`；redis 无 `RedissonClient` 时回退内存并 warn |
| `...ratelimit.window-ms` | long | `1000` | 滑动窗口宽度（无 `policies` 时的单个默认限流器） |
| `...ratelimit.limit` | int | `100` | 窗口内放行上限 |
| `...ratelimit.key-prefix` | String | `streaming:rl` | Redis 键前缀（backend=redis 时） |
| `...ratelimit.policies` | Map\<String, Policy\> | 空 | 具名策略；非空时按名构建 `RateLimiterRegistry` |
| `...ratelimit.default-name` | String | `default` | 单个 `RateLimiter` Bean 的取名 |

`policies.<name>`（`RateLimitProperties.Policy`）字段：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `algorithm` | String | `sliding` | `sliding` / `token-bucket` / `leaky-bucket`；未知值 warn 后回退 sliding |
| `backend` | String | `memory` | `memory` / `redis`；仅 `sliding`、`token-bucket` 支持 redis，`leaky-bucket` 固定内存实现 |
| `window-ms` | long | `1000` | sliding 用 |
| `limit` | int | `100` | sliding 用 |
| `capacity` | double | `100.0` | token/leaky 桶容量 |
| `rate-per-second` | double | `100.0` | token 补充速率 / leaky 漏出速率 |
| `key-prefix` | String | `streaming:rl` | redis backend 键前缀 |

Spring 注入与用法（注意：判定点是 `allow`，拒绝异常是 `RateLimitingSink.RateLimitedException`）：

```yaml
redis-streaming:
  ratelimit:
    enabled: true
    backend: redis
    default-name: api1
    policies:
      api1:
        algorithm: sliding
        window-ms: 1000
        limit: 10
      api2:
        algorithm: token-bucket
        capacity: 100
        rate-per-second: 50
```

```java
@Autowired
private RateLimiterRegistry rateLimiterRegistry;

public void handleRequest(String policyName) {
    RateLimiter limiter = rateLimiterRegistry.get(policyName);   // 未注册时为 null，需要判空或回退
    if (limiter == null || !limiter.allow("req")) {
        throw new RateLimitingSink.RateLimitedException("Rate limited");
    }
    // 处理请求
}
```

自动配置提供的 Bean：`RateLimiterRegistry`（每个实现都用 `NamedRateLimiter` 包装，会打点 `redis_streaming_rl_*`）和 `@Primary RateLimiter`（取 `default-name` 对应项；缺失时依次回退到注册表中任意一个、再回退到 `new InMemorySlidingWindowRateLimiter(1000, 100)`）。

## 用法示例

### 限流装饰 Sink（来自 examples 的 RateLimitExample）

```java
import io.github.cuihairu.redis.streaming.reliability.ratelimit.*;
import io.github.cuihairu.redis.streaming.sink.print.PrintSink;

RateLimiter sliding = new InMemorySlidingWindowRateLimiter(1000, 5); // 5 req/s
RateLimiter token = new InMemoryTokenBucketRateLimiter(10, 5);       // 突发 10、匀速 5/s
var sink = new PrintSink<String>("RL");

var slidingSink = RateLimitingSink.drop(new NamedRateLimiter("sliding-demo", sliding), s -> "user:1", sink);
var tokenSink = RateLimitingSink.drop(new NamedRateLimiter("token-demo", token), s -> "user:1", sink);

slidingSink.invoke("msg-1");   // 超限的调用被 DROP
```

### 带指标的具名限流 + 拒绝异常

```java
RateLimitingSink<String> sink = RateLimitingSink.throwing(
        new NamedRateLimiter("api1", new RedisSlidingWindowRateLimiter(redisson, "streaming:rl", 1000, 100)),
        s -> "user:1",                          // Function<T,String>：按元素取限流键
        delegateSink);                          // StreamSink<String>
try {
    sink.invoke("msg-1");                       // StreamSink#invoke，被限流时抛 RateLimitingSink.RateLimitedException
} catch (RateLimitingSink.RateLimitedException e) {
    // 拒绝处理
}
```

### 去重后再处理（来自 DeduplicatorsTest / WindowedDeduplicator 的接口约定）

```java
WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(
        redisson, "dedup:orders", Duration.ofHours(1), s -> s);

if (!dedup.checkAndMark("order-1")) {   // false = 新元素（已原子标记）；true = 窗口内重复
    System.out.println("new element");
}
// 或使用工厂
Deduplicator<String> d = DeduplicatorFactory.create(
        DeduplicatorFactory.DeduplicationStrategy.SET, redisson, "dedup", s -> s);
```

### 重试 + DLQ（来自 RetryExecutorTest / DeadLetterQueueTest）

```java
RetryExecutor retry = new RetryExecutor(RetryPolicy.builder()
        .maxAttempts(3)
        .initialDelay(Duration.ofMillis(100))
        .build());
String out = retry.execute(input -> "success", "input");   // 全部失败时抛出（解包后的）最后异常

DeadLetterQueue<String> dlq = new DeadLetterQueue<>(1000);
if (!dlq.add("payload", new IllegalStateException("boom"), 3)) {
    // 队满：返回 false，元素被拒绝入队
}
for (FailedElement<String> e : dlq.getAll()) {
    if (e.canRetry(5)) { /* 按 attemptCount 决定是否重放 */ }
}
```

## 相关文档

- [MQ](MQ.md) - Redis 侧 DLQ（`mq.dlq` 包）与消息重试
- [Spring-Boot-Starter](Spring-Boot-Starter.md) - 限流自动配置与 Micrometer 桥接
- [exactly-once](exactly-once.md) - 端到端语义（去重在其中的位置）
