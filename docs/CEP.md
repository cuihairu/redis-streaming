# CEP 模块

Module: `cep/`

## 模块职责

复杂事件处理（Complex Event Processing）：对逐条推入的事件做**条件匹配**（单个谓词）与**序列匹配**（多步、带量词/邻接/时间窗约束），返回匹配到的事件序列。纯 Java 实现（Lombok 生成样板），依赖 core（`build.gradle` 中 `api project(':core')`），**不使用 Redis**，也没有内嵌执行引擎——由调用方在自己的消息循环里调用 `process(event, timestamp)`。

主源码共 8 个文件，包根 `io.github.cuihairu.redis.streaming.cep`：

| 类 | 角色 |
|---|---|
| `Pattern<T>` | 函数式匹配条件（谓词）接口，可 `and`/`or`/`negate` 组合 |
| `PatternBuilder<T>` | 条件组合器：`any()` / `all()` / `build()`，可产出 `PatternConfig` |
| `PatternConfig<T>` | 单条件匹配配置（时间窗、连续性、序列长度、事件复用） |
| `PatternMatcher<T>` | 单条件匹配器（可选维护内部扩展序列，有界） |
| `PatternSequence<T>` | 多步序列（`begin/where/next/followedBy/followedByAny` + 量词 + `within`） |
| `PatternSequenceMatcher<T>` | 多步序列匹配器（Kleene 闭包、三种邻接、时间约束） |
| `PatternQuantifier` | 量词：`{n}`、`+`、`*`、`?`、`{n,}`、`{min,max}` |
| `EventSequence<T>` | 事件序列值对象（起点/终点/时长） |

## Pattern 与 PatternBuilder

```java
// Pattern.of：从谓词构造；and/or/negate 组合
Pattern<Integer> p = Pattern.of(x -> x > 5);
Pattern<Integer> combined = p.and(Pattern.of(x -> x < 10))
                             .or(Pattern.of(x -> x == 0))
                             .negate();
combined.matches(20);

// PatternBuilder：多条件合成一个条件（对每个事件独立求值，不涉及序列）
Pattern<Integer> all = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 0))
        .where(Pattern.of(x -> x < 10))
        .all();                       // 所有条件都成立才匹配；any() 任一成立即匹配；build() == all()

// PatternBuilder 直出 PatternConfig（来自 PatternBuilderTest）
PatternConfig<Integer> config = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 5))
        .withTimeWindow(Duration.ofMinutes(5));           // contiguous = false
PatternConfig<Integer> strict = PatternBuilder.<Integer>create()
        .where(Pattern.of(x -> x > 5))
        .withContiguousTimeWindow(Duration.ofSeconds(30)); // contiguous = true
```

- `PatternBuilder.where(p)` 与 `followedBy(p)` 在单条件组合器里等价（都只是追加条件）。
- 空条件列表时 `any()` 恒 false、`all()`/`build()` 恒 true（源码行为）。

## PatternConfig（单条件匹配配置）

Lombok `@Data` + `@Builder`，`Serializable`：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `pattern` | `Pattern<T>` | 无（必填） | 匹配条件 |
| `timeWindow` | `Duration` | `Duration.ofMinutes(1)` | 匹配的时间窗 |
| `contiguous` | `boolean` | `false` | 要求连续：非匹配事件会清空已跟踪序列 |
| `maxSequenceLength` | `int` | `100` | 单条序列最大事件数，必须为正 |
| `allowEventReuse` | `boolean` | `false` | 匹配事件既开新序列又扩展已有序列 |

`validate()`：`pattern == null`、`timeWindow` 为空/零/负、`maxSequenceLength <= 0` 抛 `IllegalArgumentException`。`getTimeWindowMillis()` 返回毫秒值。

## PatternMatcher（单条件匹配器）

```java
PatternConfig<Integer> config = PatternConfig.<Integer>builder()
        .pattern(Pattern.of(x -> x > 5))
        .timeWindow(Duration.ofSeconds(10))
        .build();
PatternMatcher<Integer> matcher = new PatternMatcher<>(config);          // 活跃序列上限默认 1000
PatternMatcher<Integer> capped = new PatternMatcher<>(config, 100);      // 指定 maxActiveSequences

List<EventSequence<Integer>> matches = matcher.process(10);              // 用当前时间
List<EventSequence<Integer>> hits = matcher.process(10, 1000L);          // 显式时间戳
matcher.getActiveSequenceCount();
matcher.clear();
matcher.getConfig();
```

行为要点（源码语义）：

- 构造时先 `config.validate()`；`maxActiveSequences` 为负抛 `IllegalArgumentException`，`0` 表示完全不跟踪扩展序列（完成输出不受影响）。默认上限 `PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES = 1000`，超出时按「最新优先」淘汰最旧的扩展序列（B-10：无上限时 `allowEventReuse` 下部分序列数量按事件数指数膨胀）。
- **`process` 的返回值是单事件序列**：每个匹配事件立即产生一条长度为 1 的 `EventSequence`。`allowEventReuse(true)` 时匹配事件会额外扩展内部活跃序列（供 `contiguous`/超时清理等状态检查），但这些多事件序列**不会**作为完成结果输出——多事件序列匹配请用 `PatternSequenceMatcher`。
- `contiguous=true` 时，遇到一个不匹配的事件会清空全部活跃序列（`PatternMatcherTest#testContiguousBreaksOnNonMatchingEvent`）。
- 活跃序列按 `timeWindow` 过期清理（`now - startTime > timeWindowMillis`）。

## EventSequence（序列值对象）

```java
EventSequence<String> seq = new EventSequence<>(List.of("a", "b"), 1000L, 2500L);
seq.getEvents();      // List.of("a","b")（构造时拷贝）
seq.getFirst();       // "a"，空序列返回 null
seq.getLast();        // "b"，空序列返回 null
seq.size();           // 2
seq.isEmpty();
seq.getDuration();    // endTime - startTime = 1500
seq.getEventsCopy();  // 防御性拷贝
seq.addEvent("c");    // 追加（不影响 start/end）
```

另有 `new EventSequence<>()` / `new EventSequence<>(List<T>)` 两个构造（起止时间取当前毫秒）。Lombok `@Data` 提供 `getEvents()` / `getStartTime()` / `getEndTime()`。

## PatternQuantifier（量词）

静态工厂（非法参数抛 `IllegalArgumentException`）：

| 工厂 | 语义 | 约束 |
|---|---|---|
| `exactly(int n)` | `{n}` 恰好 n 次 | `n >= 1` |
| `oneOrMore()` | `+` 一次以上 | — |
| `zeroOrMore()` | `*` 零次以上 | — |
| `optional()` | `?` 零或一次 | — |
| `times(int min, int max)` | `{min,max}` 区间 | `min >= 0` 且 `max >= min` |
| `atLeast(int n)` | `{n,}` 至少 n 次 | `n >= 0` |

```java
PatternQuantifier q = PatternQuantifier.times(2, 4);
q.matches(2);              // true；matches(occurrences) 判断次数是否落在 [min, max]
q.getType();               // QuantifierType.RANGE
q.getMinOccurrences();     // 2
q.getMaxOccurrences();     // 4
q.toString();              // "{2,4}"；各类型符号：{n} / + / * / ? / {n,} / {min,max}
```

`QuantifierType` 枚举：`EXACTLY` / `ONE_OR_MORE` / `ZERO_OR_MORE` / `OPTIONAL` / `AT_LEAST` / `RANGE`。

## PatternSequence（多步序列定义）

```java
PatternSequence<String> pattern = PatternSequence.<String>begin()      // 或 begin("first", pattern)
        .where("A", Pattern.of(s -> s.equals("A")))      // RELAXED 步（可作第一步）
        .oneOrMore()                                     // 量词作用于最后一个 step
        .followedBy("B", Pattern.of(s -> s.equals("B"))) // RELAXED：中间允许其他事件
        .within(Duration.ofSeconds(5));                  // 整条序列的时间约束
```

步骤方法与邻接语义（`next`/`followedBy`/`followedByAny` 不能作第一步，抛 `IllegalStateException`）：

| 方法 | `ContiguityType` | 语义 |
|---|---|---|
| `where(name, pattern)` | `RELAXED` | 追加条件（可作第一步） |
| `next(name, pattern)` | `STRICT` | 严格相邻：中间不允许任何事件 |
| `followedBy(name, pattern)` | `RELAXED` | 松散相邻：中间允许其他事件 |
| `followedByAny(name, pattern)` | `NON_DETERMINISTIC` | 非确定松散：同一事件可用于多次匹配 |

量词方法（作用于最后一个 step）：`times(PatternQuantifier)`、`times(int)`（= exactly n）、`times(int min, int max)`、`oneOrMore()`、`zeroOrMore()`、`optional()`。step 默认量词为 `exactly(1)`。

其他：

- `within(Duration)`：窗口为 null/零/负抛 `IllegalArgumentException`。
- `validate()`：无步骤抛 `IllegalStateException`。
- `size()` 返回步数；`getSteps()` / `getTimeWindow()` 由 Lombok `@Getter` 提供；`isContiguous()` 当前恒为 `false`——类上的 `contiguous` 标志没有设置入口，邻接语义完全由每步的 `ContiguityType` 表达。
- `PatternSequence.PatternStep`：`getName()` / `getPattern()` / `getContiguityType()` / `getQuantifier()` / `setQuantifier(...)`；`toString()` 形如 `A+ [RELAXED]`。
- `toString()` 形如 `PatternSequence[A+ [RELAXED] -> B [RELAXED]] within PT5S`。

## PatternSequenceMatcher（多步序列匹配器）

```java
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);            // 保留上限默认 1000
PatternSequenceMatcher<String> bounded = new PatternSequenceMatcher<>(pattern, 200);       // maxRetainedMatches

List<PatternSequenceMatcher.CompleteMatch<String>> newMatches = matcher.process("A", 1000L); // 每次返回本次新完成的匹配
List<PatternSequenceMatcher.CompleteMatch<String>> history = matcher.getCompleteMatches();   // 最旧在前，最多 maxRetainedMatches 条
matcher.getPartialMatchCount();
matcher.clear();
```

行为要点（源码语义）：

- 构造时 `patternSequence.validate()`；`maxRetainedMatches` 为负抛 `IllegalArgumentException`，`0` 表示不留历史（`process` 仍会把每次匹配返回给调用方）。默认 `PatternSequenceMatcher.DEFAULT_MAX_RETAINED_MATCHES = 1000`（B-20：历史无界会随运行时长无限增长）。
- 单步量词按 `minOccurrences`/`maxOccurrences` 驱动：次数未达 min 时停在当前步继续收事件；达到 min 后推进到下一步；超过 max 停止扩展。
- 事件已被某个 partial 消费（扩展成功）时，不再用它启动新匹配；未消费时才以该事件为种子开新匹配。
- `STRICT` 步：事件不匹配时丢弃该 partial（断链）；`RELAXED`/`NON_DETERMINISTIC` 步：不匹配则保留 partial 跳过该事件；`NON_DETERMINISTIC` 步在消费事件后额外保留一份原 partial（同一事件可再次命中）。
- `within` 设置后按 `startTimestamp + timeWindow` 过期 partial；未设置则不做过期清理。
- `getCompleteMatches()` 返回副本、最旧在前、至多 `maxRetainedMatches` 条。

`CompleteMatch<E>`：

```java
match.getEvents();                    // 全部事件（拷贝）
match.getEventsByStep();              // Map<Integer, List<E>>（按步索引）
match.getStartTimestamp(); match.getEndTimestamp();
match.getDuration();                  // end - start（毫秒）
match.getEventsForStep("login", sequence);  // 按步名取事件；名字不存在返回空列表
```

## 配置项

CEP 模块没有配置文件键，全部参数走 Builder/构造器，汇总默认值：

| 位置 | 参数 | 默认值 |
|---|---|---|
| `PatternConfig.Builder` | `timeWindow` | `Duration.ofMinutes(1)` |
| `PatternConfig.Builder` | `contiguous` | `false` |
| `PatternConfig.Builder` | `maxSequenceLength` | `100` |
| `PatternConfig.Builder` | `allowEventReuse` | `false` |
| `PatternMatcher` 构造 | `maxActiveSequences` | `PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES` = `1000` |
| `PatternSequenceMatcher` 构造 | `maxRetainedMatches` | `PatternSequenceMatcher.DEFAULT_MAX_RETAINED_MATCHES` = `1000` |
| `PatternSequence.PatternStep` | `quantifier` | `PatternQuantifier.exactly(1)` |

## 用法示例

### 单条件匹配（来自 PatternMatcherTest）

```java
Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
PatternConfig<Integer> config = PatternConfig.<Integer>builder()
        .pattern(greaterThan5)
        .timeWindow(Duration.ofSeconds(10))
        .build();

PatternMatcher<Integer> matcher = new PatternMatcher<>(config);
List<EventSequence<Integer>> matches = matcher.process(10);  // 1 条（单事件序列）
assertEquals(10, matches.get(0).getFirst());
```

### Kleene 闭量词（来自 AdvancedCEPTest）

```java
// A* B：零个以上 A，后跟 B（zeroOrMore；B 直接到达也成立，见 testKleeneStar_ZeroOrMore）
PatternSequence<String> pattern = PatternSequence.<String>begin()
        .where("A", Pattern.of(s -> s.equals("A")))
        .zeroOrMore()
        .followedBy("B", Pattern.of(s -> s.equals("B")));
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process("A", 2000L);
List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("B", 4000L);
```

其他量词同构：`.oneOrMore()`（`A+`）、`.times(3)`（`A{3}`）、`.times(2, 4)`（`A{2,4}`）、`.optional()`（`B?`）。

### 登录失败检测：1 分钟内 3 次以上失败（来自 AdvancedCEPTest）

```java
PatternSequence<LoginEvent> pattern = PatternSequence.<LoginEvent>begin()
        .where("FAILED_LOGIN", Pattern.of(e -> e.status.equals("FAILED")))
        .times(3, Integer.MAX_VALUE)
        .within(Duration.ofMinutes(1));
PatternSequenceMatcher<LoginEvent> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process(new LoginEvent("user1", "FAILED"), 1000L);
matcher.process(new LoginEvent("user1", "FAILED"), 2000L);
List<PatternSequenceMatcher.CompleteMatch<LoginEvent>> matches =
        matcher.process(new LoginEvent("user1", "FAILED"), 3000L);  // 第 3 次失败即产生匹配
```

（`LoginEvent` 为 `AdvancedCEPTest` 中的静态内部类：`userId`/`status` 两字段。）

### 时间窗约束（来自 AdvancedCEPTest#testWithinTimeConstraint）

```java
PatternSequence<String> pattern = PatternSequence.<String>begin()
        .where("A", Pattern.of(s -> s.equals("A")))
        .followedBy("B", Pattern.of(s -> s.equals("B")))
        .within(Duration.ofSeconds(5));
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

matcher.process("A", 0L);
matcher.process("B", 3000L);  // 窗口内 → 1 条匹配
matcher.clear();
matcher.process("A", 0L);
matcher.process("B", 6000L);  // 超窗 → 0 条
```

### 严格相邻与按步取事件（来自 PatternSequenceMatcherCoverageTest）

```java
PatternSequence<String> sequence = PatternSequence.<String>begin("login", type("a"))
        .next("browse", type("b"));        // STRICT：b 必须紧跟 a
PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

matcher.process("a", 10L);
List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("b", 20L);

matches.get(0).getEventsForStep("login", sequence);   // List.of("a")
matches.get(0).getEventsForStep("browse", sequence);  // List.of("b")
matches.get(0).getDuration();                          // 10
```

## 相关文档

- [Architecture](Architecture.md) - 模块在整体架构中的位置
- [window](window.md) - 窗口与触发器（另一种时间维度的事件切分）
