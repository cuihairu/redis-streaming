# 潜在 Bug 清单（系统性代码审计）

> 生成时间：2026-09-25。来源：对全仓代码的系统性审计（重点：并发与竞态、连接断开与重连、消费者组与 offset/ack、背压与阻塞、超时与重试、被吞掉的错误、资源泄漏、null 与边界条件、序列化兼容性、配置校验）。
> 每条包含：触发条件、影响、怀疑位置、严重度。状态列随验证进度更新。
>
> 状态标记：
> - ⏳ 待验证
> - 🔁 已复现（附复现测试）
> - ✅ 已修复（附回归测试）
> - ❌ 无法复现（附原因）
> - 📝 设计缺陷/不修（附理由，或转为后续任务）

---

## 严重（Critical）

### B-01 In-memory 引擎不合并会话窗口：SessionWindow 下每个元素自成窗口 [已修复]
- 位置：`runtime/.../internal/InMemoryWindowedStream.java`（drive 分桶逻辑）；`window/.../assigners/SessionWindow.java:31-36`
- 触发：`env.fromCollection(events).keyBy(k).window(SessionWindow.withGap(5min)).count()`，事件间隔 1 分钟。
- 影响：`SessionWindow.assignWindows` 对每个元素返回 `[ts, ts+gap)` 窗口，引擎按 start/end 精确分桶且无任何合并逻辑（`shouldMerge`/`TimeWindow.merge` 全仓无调用方），每个元素产生独立结果（count=1）。会话窗口功能完全失效。
- 审计置信度：高
- 验证与修复：core `WindowAssigner` 新增 `supportsWindowMerging()` 钩子（默认 false，含契约 javadoc：合并语义、trigger 状态丢弃限制）；`SessionWindow` 声明支持；`InMemoryWindowedStream` 在入桶前对同 key 的相交桶做单趟合并（不变量：同 key 桶两两不相交，故单趟扫描完备，代码注释含证明）。回归测试 `InMemoryWindowedStreamSessionMergeTest` 5 个用例：连续事件合并为单一会话 [0,270)=4、间隔超 gap 分裂、恰好等于 gap 不合并（半开区间）、链式桥接合并 [0,350)=5、多 key 独立合并。

### B-02 StreamJoiner 匹配条件依赖到达顺序：非对称 JoinWindow 下同一对数据是否 join 取决于先到方 [已修复]
- 位置：`join/.../StreamJoiner.java:54,68`（配 `JoinWindow.java:74-77`）
- 触发：`JoinWindow.afterOnly(10s)`（before=0）。右元素 R(ts=T+5) 先到、左元素 L(ts=T) 后到：`processLeft` 计算 `contains(R.ts, L.ts)` → diff=L−R=−5s，`-5 >= 0` 为假 → 不匹配；若 L 先到则 `processRight` 计算 `contains(L.ts, R.ts)` → +5s∈[0,10] → 匹配。
- 影响：join 结果取决于到达顺序而非数据本身。两处调用点条件互为镜像，任何非对称窗口必有一侧是错的。INNER/LEFT/RIGHT/FULL_OUTER 全部静默出错。
- 审计置信度：高
- 验证：已用 `StreamJoinerOrderIndependenceTest` 复现（旧代码 6 个用例中 5 个失败：afterOnly/beforeOnly 双向 + of(3s,7s) 边界）。修复：`processLeft` 改为 `contains(L.ts, R.ts)`，两条路径统一为左锚定谓词 `R.ts − L.ts ∈ [−before, +after]`（与 Kafka Streams 语义一致），并补充 `JoinWindow.contains` 的锚定契约 javadoc。修复后 join 模块全部测试通过。

### B-03 KGroupedTable.aggregate 同时丢弃初始值与累加值：多行分组坍缩为最后一行 [已修复]
- 位置：`table/.../impl/InMemoryKGroupedTable.java:45-51`；`table/.../impl/RedisKGroupedTable.java:54-56`
- 触发：`table.groupBy(...).aggregate(() -> 0L, (k, v) -> v)` 聚合同组 5 行。
- 影响：adder 签名 `BiFunction<K,V,VR>` 根本拿不到当前累加值；`current = initializer.get()` 计算后即被丢弃。结果= `adder(key, 最后一行的值)`，任何需要历史的聚合（count/sum/reduce）全错。
- 审计置信度：高
- 验证与修复：API 变更为 `aggregate(Supplier<VR> initializer, TableAggregator<K,V,VR> adder, TableAggregator<K,V,VR> subtractor)`（新增 `TableAggregator` 三参函数式接口，携带累加值）。两个实现均改为真正的 fold；修复前 InMemory 版测试断言的是"只保留最后一行"的错误行为，已改为真实 sum 断言（a=1+2=3, b=3+4=7, c=5）。

---

## 高（High）

### B-04 消费端健康状态上报完全失效：uniqueId 与 instanceId key 错配 [已修复]
- 位置：`registry/.../impl/RedisServiceConsumer.java:101-126,290-299,512-519`；`registry/.../health/HealthCheckManager.java:58,81,96-102`
- 触发：`enableHealthCheck=true` 后发现实例。`HealthCheckManager` 以 `getUniqueId()`（`"serviceName:instanceId"`）为 checker key 并作为 reporter 回调首参；`reportHealthStatus` 却用该值查 `discoveredInstances`（以裸 `instanceId` 为 key）。
- 影响：健康事件（HEALTH_FAILURE/RECOVERY）永不触发、缓存永不更新；`unsubscribe` 用裸 id 反注册同样永不命中 → checker 永不 stop；`isInstanceHealthy` 恒 false。
- 审计置信度：高
- 验证与修复：`RedisServiceConsumer` 新增 `uniqueIdToInstanceId` 反查表：三处发现路径统一经 `cacheInstanceAndRegisterHealthCheck` 登记映射；`reportHealthStatus` 先翻译 uniqueId→裸 id 再查缓存（无映射时回退原值）；`isInstanceHealthy` 先把裸 id 翻译成 uniqueId 再查 checker；`unsubscribe` 清理改用 uniqueId（旧裸 id 调用永不命中，每实例泄漏一个运行中 checker 线程——即 B-08 的放大器）；`stop()` 清空反查表。回归测试：`ConsumerHealthKeyTranslationTest`（翻译层，旧代码 2/2 失败）+ 集成测试 `ConsumerHealthEventIntegrationTest`（真实 Redis：不可达实例 → HEALTH_FAILURE 事件 + `isInstanceHealthy("i1")`=false + unsubscribe 后 checker 数归零；旧代码收不到事件）。

### B-05 metrics 收集为空时心跳被静默跳过：实例在"正常心跳"中被过期清除 [已修复]
- 位置：`registry/.../impl/RedisServiceProvider.java:333-357`；`registry/.../metrics/MetricsCollectionManager.java:34-73,133-148`
- 触发：采集超时（默认 5s > 心跳间隔 3s）/采集器失败/enabledMetrics 为空 → `collectMetrics` 返回空 map → `NO_UPDATE`；默认 `enableMetadataChangeDetection=false` → 最终 `NO_UPDATE` → 不执行任何 Redis 写。
- 影响：负载高峰（恰恰最需要心跳时）ZSet score 与 hash TTL 停止刷新，`heartbeatTimeoutSeconds` 后实例被清除、消费者掉线。仅 TRACE 级日志。
- 审计置信度：高
- 验证与修复：`HeartbeatStateManager` 新增 `shouldHeartbeatOnly(serviceName, instanceId)`（按心跳间隔判定）；`processInstanceHeartbeat` 对空采集结果改走该判定而非直接 `NO_UPDATE`——到期即产生 `HEARTBEAT_ONLY`，`executeUpdate` 的 Lua 路径照常刷新 TTL/score；心跳未到期则仍 NO_UPDATE（不产生多余写）。回归测试 `ProviderEmptyMetricsHeartbeatTest`（把决策行还原为旧短路后 2/2 复现失败）与 `HeartbeatStateManagerTest.testShouldHeartbeatOnlyDecidesByHeartbeatInterval`。

### B-06 配置中心监听器纯 pub/sub 无重同步：断连期间错过的通知永久丢失 [已修复]
- 位置：`config/.../impl/RedisConfigService.java:196-237,440-463`
- 触发：订阅方 Redis 连接闪断期间发生 `publishConfig`。
- 影响：监听器持有过期配置直至同 dataId 下次发布；无版本对账、无轮询兜底。registry 消费端事件处理同为纯响应式。
- 审计置信度：高（语义缺失类）
- 验证与修复：新增对账轮询：`ConfigServiceConfig.resyncIntervalMs`（默认 30s，0 关闭）驱动 fixed-delay 守护线程，逐 key 重读订阅配置的权威状态（content+version 一次 readAllMap），与"监听器最后被告知的状态"（DeliveredState 基线）比对，偏离即重投——错过的通知从"永久丢失"变为最多滞后一个 interval。按 content 而非 version 比对：content 是监听器可见状态且必然收敛于 Redis 真值，跨发布方时钟的 version 序可能拒绝收敛；键缺失计为删除态（null content）。基线未定（addListener 快照读失败）时静默采纳当前态、不重放（订阅即快照语义）。投递统一走 `dispatchToListeners`：按 (content, version) 幂等去重——对账轮询与在途 pub/sub 投递可能并发观察到同一新发布状态（轮询在 Lua 写后、事件分发前读到，实测复现 [v1,v2,v2]），先到者投递、后到者 no-op，顺带为 B-07 回环跳过加了第二道防线。addListener 快照基线用 putIfAbsent（后续订阅者不重置既有基线）。轮询异常捕 Throwable（防 fixed-delay 静默死亡，同 B-08 教训）仅告警下轮重试；stop() 关闭调度器并清基线。API 仅增不改。registry 消费端不在本条范围：其事件丢失由发现周期对账兜底（见 B-45 修复）。
- 回归测试：`ConfigResyncIntegrationTest`（@Tag("integration")，真实 Redis，interval=200ms，4 用例：直接改 Redis 哈希模拟"通知未送达"的发布在数个 interval 内被对账且恰投一次、错过的删除以 null content 对账、无丢失时轮询零噪音、interval=0 完全关闭回到纯响应式）。单元 `ConfigResyncReconciliationTest` 5 用例（mock 直调包私有 resyncSubscribedConfigs：补投/不重复投/删除为 null/未定基线静默采纳后偏离必投/Redis 异常吞掉 + 调度器随配置启停）。旧代码复现：临时集成测试在未修复 main 上以 "listener is stuck at [v1]" 失败坐实（复现测试已按惯例删除）。

### B-07 配置变更监听器每次发布收到两次通知（本地重放 + pub/sub 回环） [已修复]
- 位置：`config/.../impl/RedisConfigService.java:424-435`
- 触发：任何 `publishConfig`/`removeConfig`：先 `topic.publish(evt)` 又同步 `handleConfigChangeEvent(evt)`，消息再经订阅回环送达同一 JVM。
- 影响：进程内监听器对每次变更收到两次（且来自不同线程并发）；非幂等监听器（计数、一次性 reload）行为错误。
- 审计置信度：高
- 验证与修复：`ConfigChangeEvent` 新增 `publisherId` 标记（旧事件无标记仍按原路径投递，跨版本兼容）；`publishConfigChangeEvent` 发布时盖上本实例 `clientId`；订阅回调对 `publisherId==自己` 的回环事件直接跳过——本 JVM 的同步投递只此一份，远端 JVM 仍各收一次。
- 回归测试：`ConfigChangeSingleDeliveryIntegrationTest`（@Tag("integration")，真实 Redis，3 用例：发布方自身监听器恰好一次且 publish 返回时已送达、回环落地后仍一次、远端监听器恰好一次、删除监听后不再通知）。旧代码复现：git stash 还原 main 代码后跑同一测试，removal/publishing 两条以 "expected 1 but was 2" 失败——双投递坐实；新代码全绿。

### B-08 ClientHealthChecker 每实例一个非守护线程且首次检查在调用线程同步执行 [已修复]
- 位置：`registry/.../health/ClientHealthChecker.java`；`HealthCheckManager.java`
- 触发：健康检查开启后 discover N 个实例。
- 影响：`subscribe()/discover()` 首次注册每实例阻塞至 connect+read 超时（默认 5s）；未 stop 的 checker（B-04 保证会发生）以非守护线程阻止 JVM 退出；线程数 O(实例数)。
- 审计置信度：高
- 验证与修复：三项并修——(1) 首检不再内联：`start()` 改为 `scheduleWithFixedDelay(checkHealth, 0, ...)`，首检落到执行器线程，`registerServiceInstance` 每实例不再阻塞至超时（首检完成前 `getLastHealthStatus()` 为默认 true，与注册即 UP 的注册中心语义一致）；(2) 线程转守护：自管调度器与共享池的线程工厂均 `setDaemon(true)`，泄漏的 checker 不再阻止 JVM 退出；(3) 共享池：`HealthCheckManager` 惰性建一个 `ScheduledThreadPoolExecutor`（core=max(2, cores/2)，keepalive 60s + allowCoreThreadTimeOut，空闲零线程），全部实例的检查经 `ClientHealthChecker` 包私有 6 参构造器跑在共享池上，公共 5 参构造器保留自管单线程调度器向后兼容；`stopAll()` 收口关闭共享池（此后注册会重建）。附带把 `checkHealth` 的 catch 从 Exception 放宽到 Throwable——未捕获 Error 会让 fixed-delay 调度静默死亡（无日志、永不再检），捕获后仍按"检查失败=不健康"上报。
- 回归测试：`HealthCheckThreadingTest`（只用公共 API，可直接对旧代码编译）——旧代码 3/3 按预期失败：首检线程="Test worker"（调用线程内联）、调度线程 isDaemon=false、检查线程名含 "Test worker"（无共享池）；新代码 3/3 绿。既有 `ClientHealthCheckerTest`/`CustomClientHealthCheckerCoverageTest`（含 stop 阻塞探针等待 5s 超时路径、stop 中断路径）全绿，行为兼容。

### B-09 WindowedDeduplicator 的"元素级时间窗"实为集合级 TTL 且每次写入刷新 [已修复]
- 位置：`reliability/.../deduplication/WindowedDeduplicator.java`
- 触发：`windowDuration=1h` 持续有流量，元素 "A" 于 t=0 出现、一年后再次出现。
- 影响：条目永不单独过期；整个 set 的 TTL 在每次 `markAsSeen` 被刷回 windowDuration，持续流量下 set 永不过期 → "A" 一年后仍判重；set 无界增长，与"Memory-bounded"文档相反。
- 审计置信度：高
- 验证与修复：数据结构从 plain SET + 整键刷新 TTL 换成 ZSET（`RScoredSortedSet`，score=元素最近出现时间）：`isDuplicate`/`checkAndMark` 按元素 score 判窗（`now - score < window`），写入时 `removeRangeByScore` 剪掉窗口外旧条目使集合有界（≈ 流量速率 × 窗口），并盖 `window+60s` 背板 TTL 只负责在流量停止后回收整键（不再是过期机制）。时钟经包私有构造器注入（`LongSupplier`，公共构造器默认 `System::currentTimeMillis`）；构造期零 Redis 访问并补齐 NPE/IAE 校验。旧版 plain SET 键在首次访问时惰性迁移（getType==SET → readAll → delete → 以迁移时刻为 last-seen 重写为 ZSET，即旧成员视作再多看一次）。剪枝下界用 0 而非 "-Infinity"（后者过 Redisson 编码不可移植）。
- 回归测试：`WindowedDeduplicatorIntegrationTest`（只用公共 API，可直接对旧代码编译）——旧代码复现 2 项失败：`elementExpiresWhileTrafficContinues`（300ms 窗，标 "A" 后以 50ms 间隔持续泵入 "B" 共 700ms，`isDuplicate("A")` 旧=true（整键 TTL 被流量续命）/新=false ✓）、`legacyPlainSetIsMigratedToScoredLayout`（键类型旧=SET/新=ZSET ✓）；另 2 项（空闲过期、背板 TTL）新旧皆绿作守卫。单元 `WindowedDeduplicatorTest` 重写 9 例（注入时钟判窗/剪枝边界 now−window/背板 TTL/迁移/校验），`DelegateCoverageTest`、`DeduplicatorsTest` 同步迁移到 ZSET 布局。

### B-10 PatternMatcher 开启 allowEventReuse 后活动序列每事件翻倍：指数膨胀 [已修复]
- 位置：`cep/.../PatternMatcher.java:43-55,76-92`
- 触发：`allowEventReuse(true)`，N 个连续匹配事件。
- 影响：序列数 2^N（每事件克隆全部活动序列再新增）→ ~30 个事件即 OOM；仅靠时间窗清理，快速流撑不到过期。
- 审计置信度：高
- 验证与修复：实测增长基数为 2^(N+1)−2（newSeq 加入后 extendSequences 连它一起扩展），20 个事件即 2,097,150 个活动序列。沿 B-20 惯例加保留上限：新增 `DEFAULT_MAX_ACTIVE_SEQUENCES=1000` 与 2-arg 构造器（负数 IAE；0 完全关闭扩展跟踪），超出上限按最旧优先裁剪（subList 批量删除，保留最新部分序列——最可能被后续事件扩展），在 process() 扩展后调用。时间窗清理保留；`process()` 完成输出不受影响（每匹配事件仍恰一个完成序列）。裁剪不改变"扩展序列从不产出"的既有事实——活动序列仅供计数与扩展，上限化后内存有界 O(cap×maxSequenceLength)。
- 回归测试：`PatternMatcherActiveSequenceCapTest`——旧代码复现：临时旧 API 测试（1-arg 构造器）喂 20 事件断言 ≤1000，实测 2,097,150 失败后删除。永久测试 6 例：默认上限精确钳制在 1000、显式 cap=3 保留最新 3 个、cap=0 零跟踪但完成输出不变、负数 IAE、关闭 reuse 无部分序列、时间窗在满载状态下仍正常清空。

### B-11 窗口 assigner 接受 0/负大小：除零、死循环或静默丢数据 [已修复]
- 位置：`window/.../assigners/TumblingWindow.java:32-35`、`SlidingWindow.java:35-47`；`aggregation/.../TumblingWindow.java:24-29`、`SlidingWindow.java:22-27,46-59`
- 触发：`TumblingWindow.ofMillis(0)` → `%0` ArithmeticException；`SlidingWindow.ofMillis(10000, -1)` → slide 循环死循环/OOM；负 size → `end<start`、`contains()` 恒假 → 元素静默消失。
- 影响：构造或首元素崩溃、挂死或静默丢数据，无指向错误配置的校验报错。
- 审计置信度：高
- 验证与修复：四个类的构造路径统一加正数校验（window 模块两个私有构造器 + aggregation 模块把 `@AllArgsConstructor` 换成显式校验构造器，杜绝绕过工厂直构）；`SlidingWindow.of` 先构造后比较 slide<=size，null 参数不再 NPE；B-39 的 `CountTrigger` 同步加校验。回归测试 `WindowAssignerValidationTest`（window）与 `WindowValidationTest`（aggregation）。原有断言旧错误行为的用例（`TumblingWindowAssignerTest.testAssignWindowsNegativeTimestamps`、`CountTriggerTest.testWithZeroCount`）改为断言新语义。

### B-12 BloomFilterDeduplicator.clear() 删除过滤器后所有后续操作失败 [已修复]
- 位置：`reliability/.../deduplication/BloomFilterDeduplicator.java:118-121` vs `73-77`
- 触发：`clear()` 后调用 `markAsSeen`/`isDuplicate`。
- 影响：`clear()` 只 `delete()` 不重建（构造器有 `tryInit`）；对已删除过滤器操作抛错（"Bloom filter is not initialized"），重置后每个元素都异常。
- 审计置信度：高
- 验证与修复：`clear()` 在 `delete()` 后用保存的原始参数（新增 `expectedInsertions`/`falseProbability` 字段）重新 `tryInit`；`tryInit` 对已存在的 key 是 no-op，并发 clear 安全。回归测试 `clearReinitializesFilterForSubsequentOperations` 断言 delete 后 tryInit(原始参数) 且后续 markAsSeen/isDuplicate 正常。

---

## 中（Medium）

### B-13 Checkpoint 快照无类型无版本：恢复侧 LinkedHashMap/ClassCastException [已修复]
- 位置：`checkpoint/.../DefaultCheckpoint.java:65-93`；`RedisCheckpointStorage.java:37-48`；`RedisCheckpointCoordinator.java:184-188`
- 触发：快照放入 POJO → JSON 系 codec 存取 → `getState` 反序列化为 `LinkedHashMap`。
- 影响：未检查强转 `(T) stateMap.get(key)`，调用点首次使用才 CCE；无 schema/version 字段，状态类变更静默破坏旧 checkpoint；`restoreFromCheckpoint` 只打日志不恢复任何后端（呈现成功实为 no-op）。
- 审计置信度：高（设计类）
- 验证与修复：
  - 旧代码复现（集成，真实 Redis）：POJO 与 `HashMap<Integer,String>` 经默认 bucket codec 往返后类型保真（POJO 仍是 POJO、Integer 键仍是 Integer）——审计主张的"默认 codec 下必然退化为 LinkedHashMap/CCE"**不成立**，降级为非默认 codec/自定义 storage 实现下的潜在风险；真实缺陷是 `restoreFromCheckpoint`：仅遍历快照打 debug 日志后打印 "Successfully restored"（RedisCheckpointCoordinator.java:185-194），无任何外部可观察效果——假成功由代码路径直接成立。
  - 修复一（调用点类型检查）：`Checkpoint.StateSnapshot` 新增 `getState(String, Class<T>)` 默认方法（严格校验，类型不符当场抛 `IllegalStateException`，报文含 key/实际类型/期望类型）；`StateSnapshotImpl` 覆写为 Jackson `convertValue` 兜底，把解码后的字段图（如遗留 codec 产生的 `Map`）就地转成目标 POJO。
  - 修复二（快照版本）：`Checkpoint` 新增 `getSnapshotVersion()` 默认 0（= 遗留无版本标记）；`DefaultCheckpoint` 新增 `CURRENT_SNAPSHOT_VERSION=1` 与 `snapshotVersion` 字段——字段初始化值保持 0，使版本化之前持久化的旧 JSON 反序列化后如实报告 legacy，构造器对新实例盖 1；往返经真实 Redis 验证（写 1 读 1，旧 payload 读 0）。
  - 修复三（恢复诚实化 + 能力）：`RedisCheckpointCoordinator.restoreFromCheckpoint(long, BiConsumer<String,Object>)` 新增重载——校验存在且 completed（B-14 语义）后把每个 `(key, value)` 交给调用方 sink，返回移交条数，不存在/未完成返回 -1 且零移交；接口方法改为走同一实现，日志如实说明协调器不持有后端、状态落库由调用方完成，不再输出 "Successfully restored" 假成功。
- 回归测试：`DefaultCheckpointTest`（新实例版本=1、typed read 同型/转换/透 null/不可能转换报错、无标记实现读作 legacy 0）；`CheckpointSnapshotRoundTripIntegrationTest`（@Tag("integration")，真实 Redis：POJO+Integer 键 Map 往返保真、快照版本往返=1、sink 恢复移交 2 条且内容正确、未知/未完成 checkpoint 拒绝且零移交、遗留解码形状经 typed read 转回 POJO）。既有 `CheckpointIntegrationTest` 未改一字全绿（接口新增默认方法向后兼容）。

### B-14 未完成的 checkpoint 被持久化、被当作 latest 返回、可被恢复 [已修复]
- 位置：`checkpoint/.../redis/RedisCheckpointCoordinator.java:74-91,168-195`；`RedisCheckpointStorage.java:50-54,91-107`
- 触发：`triggerCheckpoint()` 落盘后、`completeCheckpoint` 前崩溃/超时；重启后 `getLatestCheckpoint()` 取到不完整者。
- 影响：恢复只打 "Restoring from incomplete checkpoint" 继续执行；`cleanupOldCheckpoints` 按时间戳淘汰，可能删旧保新（不完整的）。
- 审计置信度：高
- 验证与修复：`RedisCheckpointStorage.getLatestCheckpoint()` 只返回最新**已完成**的 checkpoint（按时间戳倒序找第一个 `isCompleted()`，全不完整则返回 null）；`cleanupOldCheckpoints(keepCount)` 淘汰顺序改为"先不完整、再最旧已完成"（各内 oldest-first，保留总数仍为 keepCount，全已完成时行为与原先完全一致）；`restoreFromCheckpoint` 对未完成 checkpoint 由 warn+继续恢复改为拒绝恢复（error 日志 + return）。checkpoint 按 id 仍可 `loadCheckpoint` 检查，不影响可观测性。回归：`RedisCheckpointStorageRecoveryFilterTest`（4 用例：latest 跳过不完整、全不完整→null、cleanup 先淘汰不完整者、断言不再调全库 getKeys()——旧代码 3 例失败）+ `CheckpointIncompleteRecoveryIntegrationTest`（真实 Redis：1/2 ack 后 latest 为 null、补满 ack 后可恢复；cleanup(2) 淘汰的是不完整的最新者而保留更旧的已完成者——旧代码两例全失败，stash 复现）。既有 `CheckpointIntegrationTest.testGetLatestCheckpoint` 原本对未 ack（不完整）checkpoint 断言 latest——属固化缺陷，已改为补满 ack 后断言。

### B-15 RedisCheckpointStorage.listCheckpoints 全 keyspace 扫描并反序列化整个快照 [已修复]
- 位置：`checkpoint/.../redis/RedisCheckpointStorage.java:57-81`
- 触发：任何 `getLatestCheckpoint()`（含 coordinator 构造器）。
- 影响：`keys.getKeys()` 无 pattern 全库遍历；每个候选 key 完整反序列化 checkpoint（含全量状态快照）后才 `.limit(limit)`；limit=1 时 O(全部×快照大小)，共享库上启动即 OOM/卡死。
- 审计置信度：高
- 验证与修复：keyspace 遍历改为前缀 SCAN——`keys.getKeys(KeysScanOptions.defaults().pattern(keyPrefix + "*"))`（Redisson 4.x 中旧的 getKeysByPattern(String) 已弃用且本项目 -Werror），只扫描本 storage 前缀；保留纯数字后缀过滤防误读同前缀辅助键。既有单测的 `keys.getKeys()` stub 相应改为 KeysScanOptions stub（impl 无值 equals，用 any(KeysScanOptions.class) 匹配；真实 pattern 行为由集成测试覆盖）。回归：`RedisCheckpointStorageRecoveryFilterTest.listCheckpointsScansOnlyTheStoragePrefix`（verify(never()).getKeys() + 结果正确——旧代码空扫描得 0 条失败）。

### B-16 StreamJoiner 缓冲为 ConcurrentHashMap + 裸 ArrayList：并发遍历/修改竞态 [已修复]
- 位置：`join/.../StreamJoiner.java:22-23,44-45,50-59,122-148`
- 触发：双线程并发 `processLeft`/`processRight`（类用 CHM 即为支持并发）。
- 影响：遍历匹配循环 vs `add`/`cleanup().removeIf` → CME 或静默漏配/重复配；`cleanup()` 每元素 O(总缓冲) 扫描。
- 审计置信度：高
- 验证与修复：与 B-02 一并处理：`processLeft/processRight/clear/getLeftBufferSize/getRightBufferSize` 全部加 `synchronized`（粗粒度锁），消除遍历 vs 修改竞态与 `workers` 类 check-then-act 问题。该类定位为测试/简单场景引擎，吞吐损失可接受。每元素 O(n) 的 cleanup 扫描保留（标记为后续优化项，非正确性问题）。
- 回归测试：`StreamJoinerConcurrencyTest`（补于后续批次）——旧代码复现：定点剥离 5 处 `synchronized` 后 4/4 失败，全部 `ConcurrentModificationException`（16 线程同 key 写入、40+40 左右流并发全交叉配对、读线程并发取缓冲 size、`clear()` 与处理并发）；修复代码上 4/4 通过，并断言精确配对数 `left×right` 与缓冲无损。

### B-17 InMemoryKGroupedTable 对 null 分组 key NPE（Redis 版容忍，行为不一致） [已修复]
- 位置：`table/.../impl/InMemoryKGroupedTable.java:41-84`
- 触发：`groupBy` 对某行返回 null 后 `count()/aggregate()/reduce()`。
- 影响：CHM `merge/compute` 对 null key 抛 NPE；`RedisKGroupedTable` 显式 `continue` 跳过。同样输入内存崩、Redis 正常。
- 审计置信度：高
- 验证与修复：`count/aggregate/reduce` 三个操作统一跳过 null 分组 key；回归测试 `nullGroupKeyRowsAreSkippedLikeRedisImplementation` 断言两个实现行为一致。

### B-18 TopKAnalyzer 忽略 windowSize："窗口化 Top-K"实为全时段 Top-K [已修复]
- 位置：`aggregation/.../analytics/TopKAnalyzer.java:24-31,52-70`
- 触发：`createTopKAnalyzer(10, Duration.ofMinutes(5))` 做 5 分钟滚动热榜。
- 影响：`windowSize` 字段从不读取；分数只增不减、只按 rank 裁剪（保留 2k）不按时间；跌出 top-2k 的条目分数永久丢失 → 窗口语义完全错误。
- 审计置信度：高
- 验证与修复：重构为时间桶窗口实现，公共 API 签名不变：记录落入 `windowSize/10`（下限 1ms）宽度的桶（`<prefix>:topk:<category>:b:<bucketIndex>`），每次写刷新桶 TTL（窗口 + 2 桶，Redis 自动回收过期桶）；查询合并尾随窗口覆盖的全部桶（`entryRangeReversed` 读 (value,score)，分数按项求和、按分数降序 + 项名并列裁决）。getTopK/getRank/getScore/removeItem/reset 全部改为窗口视图；2k rank 裁剪保留但作用域缩到单桶；旧布局 key 被忽略不破坏。构造器新增校验（k>0、window 正数）；包级私有时钟注入构造器供测试。旧实现测试中 8 个布局耦合用例按新语义重写（意图保留），新增跨桶合并/过期/桶粒度/TTL/参数校验用例。
- 回归测试：`TopKAnalyzerWindowDecayIntegrationTest`（integration，仅用公共构造器，旧码可编译）——旧代码复现：record 3 次（score=3.0 可见）→ 睡 1.2s（窗口 500ms）→ 旧码 getScore 仍 3.0、getTopK 仍报该项（失败）；对照用例"窗口内聚合"旧码即通过（隔离缺陷）。新码上两用例通过。`TopKAnalyzerWindowTest`（注入时钟）：桶离开尾随窗口后贡献清零、与窗口仍重叠的桶继续计数、相邻桶分数合并、写入 TTL 精确到 now+window+2 桶、1ms 桶下限。

### B-19 BloomFilterDeduplicator.checkAndMark 非原子 contains [add：并发同 key 双双通过 ✅已修复]
- 位置：`reliability/.../deduplication/BloomFilterDeduplicator.java:100-115`
- 触发：两线程并发 `checkAndMark(同id)`。
- 影响：双双返回 false（"新元素"）→ 处理两次；接口文档自称"原子"。`seenCount` 非 volatile 多线程丢失更新。`SetDeduplicator` 用单 `add()` 是对的。
- 审计置信度：高
- 验证与修复：checkAndMark 的 contains→add 临界区与 clear() 收敛到同一 `stateLock` 监视器（进程内原子），`seenCount` 改 `AtomicLong`；类 javadoc 明确作用域——Redisson RBloomFilter 无服务端 check-and-add，跨进程首次并发 sighting 仍可能双双通过（需要跨进程精确去重请用 SetDeduplicator 的单 SADD）。回归：`BloomFilterDeduplicatorCheckAndMarkRaceTest`——mock 模拟真实布隆成员语义 + contains 内延时，24 线程 barrier 并发 checkAndMark 同一元素断言恰 1 个"新"（旧代码实测 2 个通过即失败）；另附 8×250 个不同元素并发 markAsSeen 断言计数无丢失。旧代码复现：expected <1> but was <2>。

### B-20 PatternSequenceMatcher 完整匹配永不清理：无界增长 [已修复]
- 位置：`cep/.../PatternSequenceMatcher.java:23,62,84,187-189`
- 触发：高频匹配模式长时间运行。
- 影响：`completeMatches` 只增不删（清理仅作用于部分匹配），`getCompleteMatches()` 每次全量拷贝 → 长跑 OOM。
- 审计置信度：高
- 验证与修复：新增 `maxRetainedMatches` 保留上限（默认 1000，新双参构造器指定；负数 IAE、0 表示不留历史但 process() 照常逐条交付匹配），process() 末尾 `trimCompleteMatches()` 淘汰最旧者。消费主通道仍是 process() 返回值，保留历史仅供查询。回归：`PatternSequenceMatcherRetentionTest` 5 用例（默认上限有界、显式上限保留最新 3 条按事件标记断言、上限 0 不留历史仍逐条交付、负数 IAE、单参构造器默认行为）；旧代码复现用仅含单参构造器调用的临时测试（aria：1005 条全保留，"old code grew to 1005"），修复后删除。

### B-21 负时间戳窗口对齐用 `%` 而非 floorMod：窗口错位甚至不包含元素自身 [已修复]
- 位置：`window/.../TumblingWindow.java:33`、`SlidingWindow.java:38`；`aggregation/.../TumblingWindow.java:27`
- 触发：`assignWindows(elem, -1)`，size=1000：`-1%1000=-1` → start=0 → 窗口 [0,1000) 不含 ts=-1；正确对齐是 [-1000,0)。
- 影响：pre-epoch 时间戳（测试时钟、合成数据、1970 前 Instant）下结果静默错位一个窗口。
- 审计置信度：高（算术）/中（现实影响）
- 验证与修复：与 B-11 一并修复：window 模块对齐改 `Math.floorMod`、aggregation 模块改 `Math.floorDiv`（含 SlidingWindow.getOverlappingWindows 的 startWindow 计算）。回归用例见两个 `*ValidationTest`（断言 ts=-1 落入 [-1000,0) / [-300,700) 且所有生成窗口包含元素本身）。

### B-22 InMemoryCheckpointCoordinator 非同步映射 + 浅快照 [已修复]
- 位置：`runtime/.../internal/InMemoryCheckpointCoordinator.java:24-58`；`InMemoryKeyedStateStore.java:36-42`
- 触发：`registerStore` 与 `triggerCheckpoint` 并发；或快照后用户算子继续改共享可变值。
- 影响：CME/撕裂快照；`new HashMap<>(store)` 一层浅拷贝，可变值与 live store 共享 → 事后修改污染"已完成"快照。文档自称单线程，但 API 无防护。
- 审计置信度：高（并发时）/中（总体）
- 验证与修复：并发部分——`registerStore/triggerCheckpoint/restoreFromCheckpoint/getCheckpoint/getLatestCheckpoint/getRegisteredStores` 统一加 `synchronized`（单一监视器），`latestCheckpoint` 的 volatile 随之不再必要；`getRegisteredStores()` 从 live view 改为返回不可变**副本**，迭代不再与注册竞态。浅快照部分经核实 `InMemoryKeyedStateStore.snapshot()` 已是两层拷贝（外层 + 每个 state 的内层 map），仅用户 value 对象按引用共享——内存引擎无序列化的固有限制，值替换不会污染已完成快照（现有 `testRestoreFromCheckpointWithMultipleStores` 与新快照隔离测试共同钉住该语义），无需改动。
- 回归测试：`InMemoryCheckpointCoordinatorConcurrencyTest`——旧代码复现：①1600 store 并发注册 + 1800 次并发 trigger → 8 个 store 静默丢失（1592≠1600）；②读线程迭代 live view → `ConcurrentModificationException`；③另一轮复现 reader 20s 观察不到注册完成的可见性缺陷。修复代码上 3/3 通过，另含快照与后续状态变更隔离的语义测试。

### B-23 RedisKTable.join/leftJoin 错误处理自身 NPE：掩盖原始异常 [已修复]
- 位置：`table/.../impl/RedisKTable.java:273-276,317-320`
- 触发：join 函数抛错且对端是 InMemoryKTable（`otherTable` 为 null）。
- 影响：catch 内 `otherTable.tableName` NPE，调用方收到裸 NPE，真实根因丢失。
- 审计置信度：高
- 验证与修复：两个 catch 块改为 null 安全：`otherTable != null ? otherTable.tableName : "in-memory table"`，日志保留两张表名且原始 joiner 异常作为 cause 正常包装为 `Join failed`/`Left join failed` 向上抛。
- 回归测试：`RedisKTableJoinInMemoryPeerErrorTest`——旧代码复现：对 InMemoryKTable 对端 joiner 抛 `IllegalStateException("joiner bug")` 时，调用方收到 `Cannot read field "tableName" because "otherTable" is null` 的裸 NPE（cause 链全丢）；修复后收到 message=`Join failed`、cause=`IllegalStateException("joiner bug")` 的 RuntimeException；join/leftJoin 双路径 + 正常路径共 3 例。

### B-24 RedisKTable 每次转换物化新 Redis hash 且永不删除 [已修复]
- 位置：`table/.../impl/RedisKTable.java:155,184,213,248,294`；`RedisKGroupedTable.java:128`
- 触发：每微批调用 `filter/mapValues/join/groupBy`。
- 影响：`tableName + ":op:" + millis` 全量拷贝、无 TTL 无清理 → 长任务 Redis 内存无界增长、key 爆炸。
- 审计置信度：高
- 验证与修复：物化语义保留（整合测试钉死派生表为真实 Redis hash），治理生命周期——① 血缘登记：每个物化派生在源的 `<table>:__derived` hash 登记（child → `{"o":op,"t":millis}` JSON），先登记后填充，崩溃留下的是可回收代而非孤儿 hash；非血缘 JSON 的外来条目跳过不碰。② 代际保留：每 (源表, 操作) 只保留最新 `derivedRetention`（默认 8，`setDerivedTableRetention(≥1)` 可调，子表继承）代，超龄同操作派生连同其自身派生树级联删除，派生循环的 key 空间有界。③ 可选 TTL：`setDerivedTableTtl(Duration)` 给派生 hash 设过期（填充后设置——Redisson 对不存在的 key expire 无效；子表继承），微批重驱场景可用 Redis 原生过期兜底。④ 级联回收：`delete()` 递归删除本体 + 全部登记派生 + 各级血缘 hash（visited 集防环）；`clear()` 语义不变只清本体内容。五个物化路径（mapValues×2/filter/join/leftJoin）与 grouped 的 aggregate/count/reduce 统一走 `newDerivedChild(op, keyCls, valCls, content)`。回归：`RedisKTableLineageTest`（8 用例：保留上界、跨操作互不回收、delete 全树级联、子表只回收自己子树、外来条目跳过、grouped 登记与保留、TTL 继承、retention 非法值拒绝）+ `RedisKTableLineageIntegrationTest`（真实 Redis：12 轮派生 ≤ 保留数、delete 后 `name*` 全清、TTL 落地且不超配置值；旧代码前两者必红——key 数无界增长且 delete 残留派生）。

### B-25 订阅 check-then-act 竞态：重复 RTopic 监听器、回调翻倍、订阅泄漏 [已修复]
- 位置：`registry/.../RedisServiceConsumer.java:244-257`；`config/.../RedisConfigService.java:204-223`
- 触发：两线程并发 `subscribe(同服务)` / `addListener(同 dataId)`。
- 影响：双活监听器 → 每条消息回调两次；`unsubscribe` 只清理 map 内那个 RTopic，另一个的 Redis 订阅与 handler 永久泄漏。
- 审计置信度：高
- 验证与修复：两处订阅守卫从 `containsKey`+`put` 改为 `ConcurrentHashMap.compute`（per-key 原子的 create-or-reuse），并发订阅只会注册一个 RTopic 监听器，清理路径不变。回归测试 `ConcurrentSubscribeRaceTest`（registry，12 线程栅栏并发 subscribe，断言 addListener/removeAllListeners 恰一次；旧代码复现失败）与 `ConfigServiceConcurrentAddListenerRaceTest`（config 同型，旧代码复现失败）。

### B-26 HealthCheckManager 注册 check-then-act 竞态：泄漏运行中的 checker 线程 [已修复]
- 位置：`registry/.../health/HealthCheckManager.java:57-90`
- 触发：并发 discover 同一实例。
- 影响：双开 checker，被覆盖者线程永续运行、重复探测；反注册只停其一。
- 审计置信度：高
- 验证与修复：`registerServiceInstance` 的权威守卫改为 `putIfAbsent`（原 containsKey 仅作快速路径）——落败方不再 put+start，避免被覆盖的 checker 线程永续探测。回归测试 `HealthCheckManagerRegistrationRaceTest`（16 线程栅栏并发注册，断言恰 1 个 checker、恰 1 次初始探测、unregister 后归零；旧代码复现失败）。

### B-27 版本生成器跨线程可生成重复版本串 [已修复]
- 位置：`config/.../impl/RedisConfigService.java:366-378`
- 触发：同毫秒并发 `generateVersion()`，与 `SEQ.set(0)` 交错。
- 影响：两个不同发布携带相同 version；按 version 去重/排序的消费者丢事件或乱序。
- 审计置信度：中
- 验证与修复：根因比原描述多两层：①else 分支迟到的 `SEQ.set(0)` 落在两个 if 分支调用之间 → 二者拿到相同序号；②LAST_TS/SEQ 是 **static**（跨实例共享），实例级锁无法防护多实例；③时钟滞后（`now < last`，跨核 currentTimeMillis 偏移的真实形态）走 else 返回过去毫秒的 `-0` → 与历史版本重复。修复：`generateVersion()` 改为 `static synchronized`（类监视器覆盖所有实例），版本基于高水位发放——`now > last` 才开新毫秒序列，否则继续最新毫秒的序号（滞后时钟不重置）。9999 封顶回绕为既有行为未变（1ms 万次发布的理论边界，非本次并发缺陷）。
- 回归测试：`ConfigVersionGeneratorUniquenessTest`——旧代码复现：①16 线程×4000 次并发生成 → **2354 个重复版本串**；②反射注入 LAST_TS 高水位超前 50s（模拟时钟滞后）→ 同毫秒两次调用返回同一串 `ts-0`（确定性复现）；③顺序调用不受影响（正确通过，证伪"污染式"通过）。修复代码上 3/3 通过。测试自行恢复静态状态，不污染同 JVM 其他用例。

### B-28 historySize=0 语义反转：无界保留历史（LTRIM 0 -1） [已修复]
- 位置：`config/.../ConfigServiceConfig.java:29-31`；`RedisConfigService.java:85,413-414`
- 触发：`setHistorySize(0)`。
- 影响：`LTRIM hist 0 maxhist-1` = `LTRIM 0 -1` 全保留，与"不留历史"意图相反；每发布一条历史无界增长。
- 审计置信度：高
- 验证与修复：发布/删除两条 Lua 脚本的历史写入条件改为 `oldc and maxhist>0`（historySize=0 完全跳过历史记录而非 LTRIM 到 keep-all）；Java 回退路径 `saveConfigHistory` 对 `maxHistorySize<=0` 直接返回。回归：`ConfigHistorySizeZeroFallbackTest`（mock RList，historySize=0 断言从不 add/trim——旧代码实测 NeverWantedButInvoked；historySize=1 仍正常 trim(0,0)）+ `ConfigHistorySizeZeroIntegrationTest`（真实 Redis：historySize=0 发布两次+删除后历史键恒为 0——旧代码实测 expected <0> but was <1>；historySize=1 发布 3 次恰保留 1 条）。

### B-29 Provider 清理在空集 check-then-act 移除服务索引：孤儿心跳 ZSet 且永不再清理 [已修复]
- 位置：`registry/.../impl/RedisServiceProvider.java:536-542`
- 触发：清理批次清空某服务 ZSet 后、`SREM` 前，新实例恰好注册。
- 影响：服务被移出索引 → `cleanupExpiredInstances` 不再遍历它；无 TTL 的心跳 ZSet 永久孤儿；getAllServices 与实例列表不一致。
- 审计置信度：高
- 验证与修复：`cleanupExpiredInstancesForService` 的空集判断与 SREM 合并为单条原子 Lua（`ZCARD==0 则 SREM 服务索引`，经 RScript 直发），竞态窗口不复存在；同时把该原子步骤从 `if (!result.isEmpty())` 内移到每服务必经处——原位置只有"本批次恰好清掉了实例"才校验索引，早已为空的残留（前次清理被中断、或 B-29 竞态遗留）永不被修复。回归：`ProviderServiceIndexAtomicCleanupTest`（mock RScript：断言 eval 携带 ZCARD/SREM 原子脚本及 heartbeatKey+servicesIndexKey——旧代码零交互即失败）+ `ProviderServiceIndexCleanupIntegrationTest`（@Tag("integration") 真实 Redis：空 ZSet 服务被移出索引、有活跃心跳的服务保留且心跳不被触碰；旧代码因残留位置缺陷实测 expected false but was true 失败）。

### B-30 RedisNamingService 构造子 Provider/Consumer 时静默丢弃健康检查等配置 [已修复]
- 位置：`registry/.../impl/RedisNamingService.java:43-53`
- 触发：`namingServiceConfig.setEnableHealthCheck(true)` 等设置后经 namingService 创建。
- 影响：healthCheck* 与 admin 开关被忽略（只拷贝 keyPrefix 两项）；`getConfig()` 仍返回用户配置 → 错配不可见。
- 审计置信度：高
- 验证与修复：构造子创建角色配置时将 `enableHealthCheck`/`healthCheckInterval`/`healthCheckTimeUnit`/`healthCheckTimeout`/`enableAdminService` 五项全部拷贝到 `ServiceConsumerConfig`（Provider 侧无可对应的 Naming 级字段，keyPrefix 两项照旧）。回归：`RedisNamingServiceConfigPropagationTest` 3 用例（自定义五项反射断言到达 consumer 配置——旧代码实测 enableHealthCheck 断言失败；默认值传播；keyPrefix 双角色照常传播）。

### B-31 healthCheckTimeout 零/负值：构造抛 IAE 或 connect 无限阻塞 [已修复]
- 位置：`registry/.../RedisServiceConsumer.java:74-77`；`HttpHealthChecker.java:30-37,69`；`TcpHealthChecker.java:33-35`
- 触发：`setHealthCheckTimeout(0)` 无校验。
- 影响：`connectTimeout(Duration.ofMillis(0))` IAE → 构造失败；或 `socket.connect(addr, 0)` = 无限超时 → 该实例健康检查线程永久冻结。
- 审计置信度：高
- 验证与修复：非正超时统一回退 5000ms 默认值——`ServiceConsumerConfig`/`NamingServiceConfig` 的 setter 夹紧（前者补显式 setter 覆盖 Lombok 生成）；`HttpHealthChecker`（connect+read 双超时）、`TcpHealthChecker`、`WebSocketHealthChecker` 构造器各自夹紧（直连构造同样安全）。回归：`HealthCheckerTimeoutNormalizationTest`（0/负→5000，正值保留，三个 checker 反射断言——旧代码全数失败）+ `ConsumerZeroHealthCheckTimeoutStartTest`（healthCheckTimeout=0 时 consumer 可构造并 start——旧代码 HttpClient IAE 构造即炸；两配置类 setter 夹紧断言）。

### B-32 CircuitBreaker 窗口未满即计算失败率：首个失败即开路 [已修复]
- 位置：`registry/.../client/CircuitBreaker.java:62-78`
- 触发：默认 `new CircuitBreaker(20, 0.5, ...)`：首调用失败 → 1/1=1.0 ≥ 0.5 → 立即 toOpen。
- 影响：瞬时错误即隔离实例整个 openDuration。
- 审计置信度：高
- 验证与修复：`slideWindow` 对未满窗口返回 0（无裁决），失败率只在窗口填满（`calls >= windowSize`）时评估一次并复位——即 resilience4j `minimumNumberOfCalls` 语义；threshold=0 的"最敏感"配置行为不变。回归测试 `singleFailureDoesNotOpenBreakerOnUnfilledWindow`；原断言"首失败即 OPEN"的两个用例（registry 包 `testStateTransitions`、client 包 `testGetState`）改为填满窗口后断言。

### B-33 注册时未记录 metadata hash：开启元数据检测后首次心跳必发虚假 UPDATED 事件 [已修复]
- 位置：`registry/.../RedisServiceProvider.java:161-165`；`heartbeat/HeartbeatStateManager.java:185-196`
- 触发：`enableMetadataChangeDetection=true` 注册。
- 影响：`markMetadataUpdateCompleted` 用 `get` 而条目尚未 `computeIfAbsent` 创建 → no-op；首次心跳 0≠hash 误判 METADATA_UPDATE → 全体订阅者无谓 re-discover。
- 审计置信度：高
- 验证与修复：`markMetadataUpdateCompleted` 的 `instanceStates.get` 改为 `computeIfAbsent`——注册发生在任何决策之前，条目必然不存在，get 是静默 no-op，注册时的基线 hash 从未落盘；metrics/heartbeat-only 两个 mark 保持 `get` 不变（它们只会在决策创建条目后被调用，未知实例标记仍应 no-op）。回归：`HeartbeatStateManagerMetadataRegistrationTest`（注册后首次心跳元数据未变断言 NO_UPDATE——旧代码实测 METADATA_UPDATE；元数据真变仍检出 METADATA_UPDATE，interval 置 0 隔离限流窗口）；既有 `HeartbeatStateManagerCoverageTest.stateInfoAndRemovalHelpers` 原断言"未知实例 metadata 标记为 no-op"属固化缺陷，已按新语义改为断言条目被创建且可 remove。

---

## 低（Low）

### B-34 In-memory 限流器 per-key 状态永不淘汰（无界 map）✅已修复
- 位置：`reliability/.../ratelimit/InMemorySlidingWindowRateLimiter.java`（Token/Leaky 同型已同批修复）
- 影响：高基数 key（IP/用户）churn 下限流器自身成内存泄漏。算法本身同步正确。
- 验证与修复：三个 in-memory 限流器（滑窗/令牌桶/漏桶）的 per-key map 增加写路径惰性清扫：状态变为"语义等价于不存在"（滑窗 deque 全过期 / 令牌桶按已流逝时间回满 / 漏桶按已流逝时间漏空）即从 map 摘除——淘汰对限流判定完全透明，不改变任何 allow/deny 结果。清扫按规模阈值（默认 256，包私有构造器可调）+ 最小间隔（max(1s, 半过期周期)）CAS 门控，稳态调用零额外开销、无后台线程、无生命周期负担；活跃 key 永不被淘汰。新增 `trackedKeyCount()` 供监控。坑位记录：门控时间戳哨兵不能用 `Long.MIN_VALUE`（`now-哨兵` 溢出为负使清扫永不触发，测试立即暴露，改 0）。
- 回归测试：`InMemoryRateLimiterKeyEvictionTest`（公共构造器 + 反射读私有 map 字段，字段名新旧一致，可直接对旧代码编译）——旧代码 3/3 泄漏断言按预期失败（"expected 1 but was 301"：300 个过期 key + 1 个新 key 全部滞留），新代码 6/6 绿（3 个淘汰 + 3 个活跃 key 不误删）；既有行为测试全绿证明淘汰零语义漂移；`sweepThreshold` 负数校验入 `InMemoryRateLimiterCtorValidationTest`。

### B-35 DeadLetterQueue maxSize 未校验 + clear 与 add 竞态 [已修复]
- 位置：`reliability/.../DeadLetterQueue.java`（构造器校验；`add` CAS；`clear` 逐元素 drain）
- 影响：`maxSize<=0` → add 恒 false 全静默丢弃；`clear()` 两步非原子，计数可漂移，容量永久缩水。
- 审计置信度：高
- 验证与修复：分两步落地（`2db0433` 先修 add 超调，`787c056` 补齐剩余两项）：1) 构造器校验 `maxSize<=0` 抛 `IllegalArgumentException`——非正上限等于"所有失败静默丢弃"，违背 DLQ 存在目的。口径说明：maxSize 是**类型化构造参数**而非字符串属性袋，"缺失/非数值"在编译期即被排除，≤0 采用 fail-fast IAE（对齐 B-39 CountTrigger、B-34 sweepThreshold 的构造参数校验惯例），而非 M1 背压字符串配置的"回退默认+告警"口径（后者适用于 typo 不得静默解除保护的属性袋场景）；无参构造器仍以 `Integer.MAX_VALUE` 表示无界。2) `clear()` 从 `queue.clear(); sizeCounter.set(0)` 两步批量清零改为**逐元素 drain + 逐次递减**——批量清零会抹掉落在两步之间的并发 add 递增，计数永久少计、队列此后可超 maxSize；drain 版与 `poll()` 同构（每次出队恰好一次递减），任意并发交错下 `size()==getAll().size()<=maxSize` 恒成立。`add()` 侧为 fast-path 检查 + CAS 占额、offer 失败回滚递减（`DeadLetterQueueOfferFailureCoverageTest` 钉住回滚）。
- 回归测试：`DeadLetterQueueClearRaceTest`（只用公共 API，可对旧代码编译）——`ctorRejectsNonPositiveMaxSize`（0/-1 抛 IAE，旧代码接受并全静默丢弃；无参构造器可用）；`clearKeepsTheCounterConsistentUnderConcurrentAdds`（2 adder × 1 clearer 缠斗 400ms 后断言 `size()==getAll().size()` 且 ≤ maxSize——旧代码批量清零实测计数漂移 `expected 2 but was 1`）；`counterStaysConsistentWhenAddPollAndClearAllInterleave`（add×poll×clear 三 mutator 并发缠斗，同一不变量）。既有 `DeadLetterQueueAddCoverageTest`（满队列 add 返 false）覆盖 `maxSize<=0` 的另一症状面。

### B-36 外连接立即发 unmatched，对端稍后到达又发 match：同元素双发 ✅已缓解（语义明示化）
- 位置：`join/.../StreamJoiner.java`
- 影响：LEFT/FULL_OUTER 下游对同一左元素先收 join(L,null) 后收 join(L,R)（无 watermark barrier/retraction；类文档已声明"测试与简单场景"，低）。
- 处置说明：行为本身正确实现了"立即 unmatched + 窗口内迟到配对"的直通语义；修复它需要等待再决定（延迟=窗口时长）或撤回（retraction）机制，属于面向生产 join 的重设计，非本测试型 joiner 的目标。本轮把该语义显式写入类 javadoc（含对下游的双发警示），并加语义锚定测试防止未来无意识变更。与 RT-M5 同为"缓解"计。

### B-37 WindowAggregator 以窗口类简名为 key：同类不同参数窗口互相截断 ✅已修复
- 位置：`aggregation/.../WindowAggregator.java:159-167`
- 影响：`TumblingWindow`(1min) 与 (1hour) 同 key 使用时 key 无 size 维度：同刻窗口起点（整点）下两类窗口落同一 Redis key，互相 prune 对方数据、且小时窗口读区间会计入分钟窗口写入的值（读污染，1 变 2）。注：起点不相同时 prune 互删不发生，可确证缺陷为读污染。
- 验证与修复：`getWindowKey` 追加窗口 size 维度，key 格式改为 `prefix:window:key:startMillis:WindowSimpleName:sizeMillis`。新增 `WindowAggregatorKeyIsolationTest`（mock 验证两类窗口 captor 抓到不同 key，旧代码两 key 完全相同）与 `WindowAggregatorKeyIsolationIntegrationTest`（真实 Redis：小时窗口 COUNT 旧代码 2、新代码 1），同步更新 `WindowAggregatorTest` 13 处 key 期望。

### B-38 RedisListState.update 先清后写非原子：中途失败状态全丢 ✅已修复
- 位置：`state/.../redis/RedisListState.java:47-60`
- 影响：clear 后 add 中途连接断 → 旧状态已毁新状态未写全，静默丢失；并发读者还能观察到清空瞬间。
- 验证与修复：`update` 改为单次 `REDIS_WRITE_ATOMIC` 批（MULTI/EXEC：delete + addAll 原子生效），空更新仅 delete。改写原钉死缺陷行为的 `RedisListStateTest.updateClearsThenAddsAllElements` 为 `updateReplacesAtomicallyViaWriteAtomicBatch`（旧代码 `createBatch` 零交互）+ `emptyUpdateStillDeletesAtomically`，新增 `RedisListStateUpdateIntegrationTest`（真实 Redis：整体替换/空更新清空）。

### B-39 CountTrigger 接受 maxCount<=0：每元素即触发 [已修复]
- 位置：`window/.../triggers/CountTrigger.java:15-31`
- 影响：静默错配，无校验报错。
- 验证与修复：构造器加正数校验抛 IAE（与 B-11 同批）；`CountTriggerTest.testWithZeroCount` 原断言"maxCount=0 每元素触发"的旧缺陷行为，改为断言抛 IAE。

### B-40 TopKAnalyzer 边界裁剪对同分条目非确定 ✅已修复
- 位置：`aggregation/.../TopKAnalyzer.java`（recordItem 裁剪 + getTopK/rankedWindowItems 排序）
- 影响：裁剪侧 `removeRangeByRank` 同分按字典序**升序**逐出——恰好逐出查询同分中应排最前的条目；查询侧同分顺序 = HashMap 迭代序（未规定，随实现漂移）。裁剪与查询的排名策略互相矛盾。
- 验证与修复：统一排名策略为 `(score desc, item asc)`；裁剪改为按同一排名的尾部 `(score asc, item desc)` 逐名逐出（读 rank 前缀 + 补齐跨界同分组的 `entryRange` 再排序），测试实测本 JDK 上 HashMap 同 bin 先插 q 后插 a 迭代序仍为 a 在前，佐证旧查询侧顺序不可依赖。新增 `TopKAnalyzerTieOrderTest`（裁剪逐出 b 而非 a；旧代码 `remove("m")` 未被调用）+ 改写 `recordItemAddsScoreAndOptionallyTrims`。

### B-41 PVCounter 混用事件时间与墙钟保留：迟到事件即到即删、未来事件永生 ✅已修复
- 位置：`aggregation/.../analytics/PVCounter.java`（recordPageView/getPageViewCount）
- 影响：`add(score=ts)` 后立即按墙钟 `removeRangeByScore(0, now-window)` → 旧于窗口的事件写入即被静默删除；count 用无上界 `size()` → 未来时间戳事件长期计入（永生虚高）。
- 验证与修复：统一 trailing-window 语义 `[now-window, now]`：count 改为区间 `count(cutoff,true,now,true)`；早于窗口的事件直接拒收不写入（返回当前计数）；未来事件照存（score=ts）但不计数，墙钟越过其 ts+window 后由保留裁剪回收。新增 `PVCounterWindowSemanticsTest`（旧代码：未来事件 count 0→5、无上界 7→9、迟到事件仍写入）+ `PVCounterWindowIntegrationTest`（真实 Redis），更新 3 个钉死旧行为的测试文件。

### B-42 RedisClientMetricsReporter 非原子读改写共享 metrics JSON 且全吞错误 ✅已修复
- 位置：`registry/.../client/metrics/RedisClientMetricsReporter.java`（mutateMetrics）
- 影响：并发下 `clientInflight` 丢失更新（不归零，扭曲 maxInflight 均衡）；异常全吞无任何日志。
- 验证与修复：`mutateMetrics` 按实例键分条 `ReentrantLock` 串行化读改写（实例 hash 归单进程所有，请求线程并发即真实竞态；metrics JSON 被负载均衡/管理端整体读取，不改存储格式）；全吞改为 `log.debug` 带上下文。新增 `RedisClientMetricsReporterConcurrencyTest`：旧代码 8 线程×250 次自增仅落 448/2000、加减两阶段后残留 8；新代码精确 2000/归零。

### B-43 collectWithTimeout 超时任务不取消，泄漏到公共 ForkJoinPool ✅已修复
- 位置：`registry/.../metrics/MetricsCollectionManager.java`
- 影响：公共池线程堆积，与 B-05 叠加。
- 验证与修复：双重修复——(1) 采集调用改跑专用守护线程池（`newCachedThreadPool`，线程名 `metrics-collector-N`，空闲 60s 自灭，无需显式生命周期），挂死的采集器不再占用公共 ForkJoinPool 线程（common pool 容量 = cores−1，几个挂死探针即可饿死全 JVM 的并行流/异步任务）；(2) `future.get` 超时路径补 `future.cancel(true)` 中断采集器，任务不再滞留。超时语义不变：TimeoutException 仍由调用方按 WARN 吞掉。测试踩坑记录：`MetricsConfig.getEnabledMetrics()` 返回不可变 `Set.of(...)`，须用 `setEnabledMetrics` 整体替换。
- 回归测试：`MetricsCollectionTimeoutTest`（只用公共 API + 线程栈扫描，可对旧代码编译）——旧代码失败理由精确匹配：`a timed-out collector must not linger on the common ForkJoinPool (B-43) ==> expected: <null> but was: <Unsafe.park 栈含 collectMetric 帧>`（超时探针卡死在 common-pool worker 上）；新代码全绿：采集在专用池执行、超时后中断、公共池无残留 collectMetric 帧。既有 MetricsCollectionManager 覆盖测试全绿。

### B-44 publishConfig 降级路径非原子且换版本号重写 ✅已修复
- 位置：`config/.../impl/RedisConfigService.java`（publishConfig + 新 fallbackPublish）
- 影响：Lua 可能已生效（响应丢失）又走 5+ 步 Java 回退：重新 generateVersion 双写历史、同一次发布两个版本事件，且逐字段 fastPut 非原子。
- 验证与修复：版本号一次生成全链路复用；回退先比对已存 version——等于 Lua 尝试版本则说明脚本已生效，跳过重写只补发事件；回退散写改为单次 REDIS_WRITE_ATOMIC 批。新增 `RedisConfigServiceFallbackTest`（旧代码：已生效仍 fastPut 重写、putAsync 未调用），更新 4 个钉死 fastPut 链的既有测试。

### B-45 discoveredInstances 缓存永不失效且跨服务按裸 instanceId 键控 ✅
- 位置：`registry/.../RedisServiceConsumer.java:52,185,292-299,381-408`
- 影响：实例移除/过期后仍被健康检查、计数错；不同服务同 instanceId（默认 hostname）互相覆盖。
- 修复：缓存改按 uniqueId（`serviceName:instanceId`，与健康检查器同键空间）键控，删除 uniqueIdToInstanceId 翻译表；`discover()` 每轮按本次存活集对该服务的缓存条目做对账（心跳过期的条目随发现周期淘汰）；REMOVED 变更事件立即驱逐缓存条目；`discoverByMetadata()` 复用统一缓存+注册路径。裸 instanceId 查询改为按值匹配：多服务共享同一 id 时仅当全部命中健康才报告健康（不再"最后写入者获胜"）。限定：`discoverByMetadata()` 的过滤子集不做对账（非全量存活集），由下一轮全量 `discover()` 或 REMOVED 事件兜底。
- 测试：RedisServiceConsumerDiscoveredCacheTest（5 项旧代码失败判别 + 1 项契约钉）、DiscoveredInstancesCacheIntegrationTest（真实 Redis 跨服务同 id + 反注册驱逐）、ConsumerHealthKeyTranslationTest/RedisServiceConsumerCoverageUnitTest 按新键空间更新。

---

## 审计确认无问题（界定范围）

- `RedisSlidingWindowRateLimiter`/`RedisTokenBucketRateLimiter`：淘汰+计数+插入在单 Lua 内原子；allow/remaining 数学正确。
- `InMemoryTokenBucket`/`LeakyBucket`：per-bucket synchronized，算术正确。
- `BoundedOutOfOrderness`/`AscendingTimestamp` 水位线数学：初始值防下溢正确。
- `QuantileAnalyzer.quantile`：lower nearest-rank 无 off-by-one。
- 心跳 vs 反注册复活：心跳 Lua 先 `EXISTS` 再 `ZADD`，竞态下不会复活已反注册实例。
- NOSCRIPT 重载：`RegistryLuaScriptExecutor` 重载并重试。
- Redisson RTopic 连接恢复后自动重订阅（真正的丢失窗口是 B-06 的无重同步）。

---

## 审计覆盖说明

mq / runtime(redis 引擎) / cdc+connectors 三个模块的审计已完成，结果见下方各节。

---

## mq 模块审计结果（MQ-01 ~ MQ-15）

> 置信度均为审计 agent 依据代码路径给出；逐条验证状态见各条目。

### 严重（Critical）/ 高（High）

### MQ-01 DLQ 消费者从不回收 pending 条目：handler 失败即永久滞留 PEL [已修复]
- 位置：`mq/.../dlq/RedisDeadLetterConsumer.java:89-188`（readGroup 用 `neverDelivered()`，catch 只 log @179-181，RETRY 失败路径 @163-172 不 ack）
- 触发：`DeadLetterHandler.handle` 抛异常，或 RETRY 重放失败（ok=false）。两种情况都把 id 留在消费者组 PEL。
- 影响：静默丢消息——DLQ 条目永远不会被重读（`neverDelivered()` 只读从未投递的），全类无 `listPending`/`claim`（主消费者 `RedisMessageConsumer.processPendingMessages` 有，此处没有），条目永久 pending。
- 审计置信度：高
- 验证与修复：无需整体重构即可补齐回收路径——镜像主消费者 processPendingMessages 的既有惯例做局部修复：消费循环每 topic 限频（默认 5s，`mq.dlq.test.pendingSweepMs` 可调）跑 pending sweep，`listPending` 找出 idle 超阈值（默认 300s 与 MqOptions.claimIdleMs 一致，`mq.dlq.test.claimIdleMs` 可调）的条目，`claim` 后走与实时投递完全相同的处置（处置块原样抽取为 `processEntry` 共用：SUCCESS/FAIL ack、RETRY 重放成功才 ack、handler 抛异常留 PEL 下轮 sweep 再试——无限重试与主消费者语义一致）。条目历史上有两种写入 codec：经错误句柄 claim 会抛解码异常，回退另一句柄（与既有读取路径同款 dance）。失败期间条目始终留在 DLQ 流本身（XACK 只清 PEL），可人工处置，无静默丢弃。MQ-04（DLQ 删除/回收策略）仍是独立专项，不受本条影响。
- 回归测试：`DlqPendingReclaimIntegrationTest`（@Tag("integration")，真实 Redis，3 用例：handler 持续抛异常被 idle claim 反复重试且条目不丢（留 PEL + 留流）、瞬时失败后重投递 SUCCESS 最终 ack、RETRY 重放首败后经 sweep 重试至成功才 ack）。测试只用修复前公共 API，可直接对旧代码编译——旧代码 3/3 失败：`got 1`（投递一次后 PEL 永久滞留）、pending 卡 1、`replays=1`，精确对应本条三个失败路径。

### MQ-02 DlqConsumerAdapter 的 RETRY 重放两次 XADD：业务主题收到重复消息 [已修复]
- 位置：`mq/.../impl/DlqConsumerAdapter.java:80-97`（toResult 的 RETRY 分支自己 XADD @94）与 `:26-46`（replay lambda XADD @38）；`RedisDeadLetterConsumer.java:144-172`（case RETRY 调 `replayHandler.publish`）
- 触发：被 `DlqConsumerAdapter` 包装的 handler 对 DLQ 条目返回 `RETRY`。
- 影响：业务消费者收到并处理两次（重复副作用/重复计数）。
- 审计置信度：高
- 验证与修复：删除 `DlqConsumerAdapter.toResult` RETRY 分支自身的 XADD，重放统一由 delegate 的 replayHandler 单次发布（adapter 构造时始终注入非 null replay）。`DlqConsumerAdapterTest` 两个断言旧双重写入的用例改为断言 `toResult` 不再触碰流（`verifyNoInteractions`）。

### MQ-03 LeaseManager 获取租约非原子（SET+EXPIRE）、释放非原子（GET+DELETE）：永久卡死与所有权窃取 [已修复]
- 位置：`mq/.../lease/LeaseManager.java:19-37`（`setIfAbsent` 后 `expire`）、`:64-74`（`releaseIfOwner` GET 后 DELETE）
- 触发：(a) `setIfAbsent(ownerId)` 与 `expire` 之间崩溃/断连 → key 无 TTL 永存；(b) release 中 worker A 读到 cur=="A" 后 key 过期、B `tryAcquire` 成功写入 B，随后 A 的 `delete()` 删掉 B 的新租约。
- 影响：(a) 该 topic/group/partition 永远无法再获租约，消费永久停摆（需人工 DEL）；(b) 两个消费者同时认为自己持有分区 → 并发重复消费。
- 修复方向：`setIfAbsent(value, Duration)` 原子获取；release 用 Lua compare-and-delete。
- 审计置信度：高
- 验证与修复：`tryAcquire` 改为单次 `setIfAbsent(value, Duration)`（原子 SET NX EX）；`renewIfOwner` 改为 Lua compare-and-pexpire；`releaseIfOwner` 改为 Lua compare-and-delete（Redisson `RScript.ReturnType.LONG`）。`LeaseManagerTest` 重写为验证原子调用形态与脚本内容。

### MQ-04 all-groups-ack 删除策略把"活跃组"等同于"有租约的组"：停机组的未消费消息被删 [已修复]
- 位置：`mq/.../broker/impl/DefaultBroker.java:137-171`（计数 @150-161，删除 @162-166）
- 触发：`ackDeletePolicy="all-groups-ack"`，两消费组共用分区；B 组停机（租约 key 过期）时 A 组 ack。`active` 只算 A → `ackset.size()>=active` → `stream.remove(...)`。
- 影响：B 组数据丢失：条目在 B 读到之前被 XDEL；B 重启后消息已消失。
- 审计置信度：高
- 验证与修复：删除门槛从"有租约的组数"改为"已注册组数"（`stream.listGroups().size()`）——注册即投递契约：停机组（租约过期）会回来、仍需要该条目，其未 ack 期间删除门槛不满足（`registered > 0 && ackset.size() >= registered`）。租约活性只是消费心跳信号，不是"该组还需要这条消息吗"的答案。代价是无人再回来的僵尸组会暂留条目——方向安全（宁多留不丢数据），由 ackset TTL 与保留策略另行回收。
- 回归测试：`BrokerAllGroupsAckStoppedGroupIntegrationTest`（@Tag("integration")，真实 Redis，3 用例：gA 有租约/gB 停机时 gA 单独 ack 条目保留、gB 补 ack 后条目删除且 ack-set 清理、单注册组 ack 后照常删除、immediate 策略不受影响）。旧代码复现：`stoppedGroupIsNotStarvedOutOfDeletionGate` 以 "expected: <1> but was: <0>" 失败（条目在停机组读到前被删）。钉死旧租约过滤行为的 `DefaultBrokerUnitTest.ackAllGroupsAckDeletesWhenActiveGroupsAcked` 重写为两段式新契约（1<2 不删、2>=2 删），`DefaultBrokerAckEdgeTest` 四个 all-groups-ack 分支用例同步到注册组数语义，`ConsumerBrokerPathIntegrationTest` 注释更正（该流仅一个注册组，行为不变）。

### MQ-05 DLQ 重放路径硬编码 `stream:topic` 前缀，忽略配置前缀：重放消息石沉大海且 DLQ 条目被 ack [已修复]
- 位置：`mq/.../dlq/RedisDeadLetterService.java:155`；`mq/.../dlq/RedisDeadLetterConsumer.java:153`
- 触发：`MqOptions.streamKeyPrefix != "stream:topic"` 时走 RETRY fallback 或 `RedisDeadLetterService.replay` 无 ReplayHandler 的路径。
- 影响：消费路径丢数据：`ok=true`（写到了错误的 key）→ `stream.ack` 删掉 DLQ 条目，而重放消息写进了无人消费的流。
- 修复方向：统一走 `StreamKeys.partitionStream(...)`。
- 审计置信度：高
- 验证与修复：`RedisDeadLetterConsumer:153` 与 `RedisDeadLetterService:155` 的硬编码前缀均替换为 `StreamKeys.partitionStream(topic, pid)`；`DlqConsumerAdapter` 构造时同时配置 `StreamKeys.configure(controlPrefix, streamPrefix)`（此前只配置了 DlqKeys，StreamKeys 仍是默认前缀）。

### 中（Medium）

### MQ-06 毒消息在 pending 扫描器中无限循环：非 payload 缺失的解析错误被重抛，永远不进 DLQ [已修复]
- 位置：`mq/.../impl/StreamEntryCodec.java:107`（`Instant.parse`）；`RedisMessageConsumer.java:484-491`（非 payload-missing 重抛）、`360-392`（扫描器 claim，catch @390 只 log）
- 触发：任何 `timestamp` 字段非 ISO-8601 的流条目（外部生产者/手工修复/损坏写入）。`isPayloadMissing` 只匹配 "Payload not found"/"Failed to load payload"。
- 影响：条目留在 PEL；每个 `pendingScanIntervalSec` 都被 claim → parse 抛 → log → 继续，无退避、无 DLQ、无最大投递截断；日志洪水 + 永久卡死条目。
- 审计置信度：高
- 验证与修复：`RedisMessageConsumer` 的两条解析路径（`processPendingMessages` 与 `processIncomingRecord`）统一把所有 `RuntimeException` 视为 poison —— 复用 `handleMissingPayload` 构造最小错误消息（含解析异常类型/消息头）、经 `DeadLetterService.send` 入 DLQ 再 ACK 原条目。旧代码仅捕获 payload-missing，其余重抛 → 外层 `requeueOrDeadLetter` 收到 null message → NPE 被吞 → 条目留在 PEL 无限 re-claim。
- 回归测试：`Mq06PoisonPendingIntegrationTest`（@Tag("integration")，真实 Redis：手工写入非法 timestamp 条目，经 ghost consumer 放入 PEL，本消费者启动后 pending scanner claim → 解析失败 → DLQ 计数=1、handler 零调用、二次扫描无重复；旧代码复现：DLQ 计数递增、handler 从未被调用但日志无限报错）。

### MQ-07 指数退避移位溢出变负数：重试风暴零延迟轰炸 Redis [已修复]
- 位置：`mq/.../retry/ExponentialBackoffRetryPolicy.java:25`（`baseMs * (1L << (attempt-1))`）；消费点 `RedisMessageConsumer.java:654-656,670`
- 触发：`maxRetries >= ~54`（生产者可设，`Message.maxRetries` 从流数据解析）。attempt 54+ 时乘积超 Long.MAX；attempt 64 时 `1L<<63` 为负。
- 影响：`delayMs` 为负 → `Math.min(neg, max)` 为负 → 立即重入队：每次失败零退避地 read/XADD/XACK 循环轰炸 Redis。
- 修复方向：对 `v<=0` 先钳到 maxBackoffMs（饱和处理）。
- 审计置信度：高（算术确定性成立）
- 验证与修复：`nextBackoffMs` 改为饱和计算：shift 钳到 ≤62，乘法前先判 `baseMs > maxBackoffMs / factor` 则直接返回 maxBackoffMs（永不溢出、永不为负）。`ExponentialBackoffRetryPolicyTest` 新增 5 用例（正常增长、封顶、attempt 1..200 全程非负且不超上限、大 base 饱和、base=0 立即重试）。

### MQ-08 in-flight 信号量幽灵释放：背压上限被永久抬高 ✅
- 位置：`mq/.../impl/RedisMessageConsumer.java:836-860`（acquire 循环在 running/closed 翻转时未获取即返回）、`:862-870`（release 无条件调用）、调用点 `:505/527`、`:378/387`
- 触发：worker 阻塞在 `inFlightLimiter.acquire()` 时调用 `stop()/close()`：循环条件变假、方法未获取即返回；`finally` 仍 `releaseInFlightPermit()`。
- 影响：`Semaphore.release()` 无配对 acquire → 可用许可超过配置最大值，背压上限被静默永久削弱（stop/start 循环的实例上会累积）。
- 修复：`acquireInFlightPermit()` 改返回 boolean（未获取即返回 false；limiter 关闭视为已获取），两处调用点（processIncomingRecord 与 pending 扫描器）的 `finally` 仅在确实获取到许可时才 release。stop 中断路径语义不变（恢复中断标志、不阻塞关停）。
- 测试：RedisMessageConsumerInFlightBackpressureTest——端到端驱动真实 processIncomingRecord 调用点：maxInFlight=1 下第一条消息阻塞 handler 占满许可、第二条阻塞在 acquire，翻转 running+中断后断言可用许可仍等于 maxInFlight（旧代码：expected 1 but was 2）；第二项钉死 acquire 必须如实报告未获取（旧代码返回 void，expected false but was null）。

### MQ-09 `claimIdleMs(0)` 被接受：pending 扫描器立刻偷走正在处理的消息 [已修复]
- 位置：`mq/.../config/MqOptions.java:89`（钳到 `>=0` 而非 `>=1`）；使用点 `RedisMessageConsumer.java:360-363`
- 触发：`MqOptions.builder().claimIdleMs(0)`（负值也会被钳成 0）。任何 pending 超过 0ms 的条目——即组内任何正在处理的消息——被 claim 并发重处理。
- 影响：保证重复/并行处理在途消息 + ACK 风暴（首个 handler 的 ACK 与重处理者的重入队竞争），慢 handler 场景等效 at-most-once。
- 修复方向：builder 钳到 `Math.max(1, v)`。
- 审计置信度：高（机制确定，需非默认配置）
- 验证与修复：builder 改为 `Math.max(1, v)`；`MqOptionsTest` 原"0 被允许"用例（断言的就是缺陷行为）改为断言钳到 1。

### MQ-10 JdbcBrokerPersistence 手写 JSON 不转义控制字符：headers 列损坏 [已修复]
- 位置：`mq/.../broker/jdbc/JdbcBrokerPersistence.java:60-82`
- 触发：header key/value 含 `\n`、`\t`、`\r` 等（异常详情 header 常含换行）。
- 影响：写出的 headers JSON 非法（RFC 8259 禁止裸换行）；消费方解析失败或静默丢 headers。
- 审计置信度：高
- 验证与修复：`escapeJson` 重写为逐字符转义（`\n \r \t \b \f` 命名转义、其余 <0x20 用 `\u00XX`）；`JdbcBrokerPersistenceJsonEscapingTest` 新增控制字符用例：断言输出无裸控制字符且 Jackson 解析还原原值。

### 低（Low）

### MQ-11 commit frontier 更新是非原子 read-modify-write：并发 ACK 可使 frontier 回退 ✅
- 位置：`mq/.../impl/RedisMessageConsumer.java:805-816`
- 触发：两个 worker/扫描线程并发 ack 同组同分区不同消息；都读到同一 `prev`，较小 id 后写。
- 影响：frontier 回退；`StreamRetentionHousekeeper`（按最小 frontier trim）少 trim（安全方向），lag 指标不准。应改 Lua HSET-with-compare。
- 修复：frontier 更新改为单个 Lua 脚本原子 compare-and-set（HSET 仅当新 id 更新；prev 不可解析时自愈覆写，新 id 非法则不写），脚本走 StringCodec 明文 hash（"ms-seq"，与客户端 codec 无关，二进制 codec 留下的旧值由 Lua 自愈覆写）；best-effort 语义不变（脚本失败仅 debug 日志）。runtime 同源修复：`RedisStreamExecutionEnvironment` 延迟 ack 刷新改同一原子 CAS、缺失 group 恢复读改 StringCodec；`RedisRuntimeCheckpointManager` 快照读改 StringCodec（506c11c）。
- 测试：RedisMessageConsumerCommitFrontierScriptTest——eval 契约（旧代码零 eval 调用，"Wanted but not invoked"）、脚本失败被吞、8 线程×250 id 并发 ack 以内存 CAS 钉死 max 归约；CommitFrontierAtomicityIntegrationTest（真 Redis，40 轮×8 线程乱序 ack，frontier 必须收在最大 id；旧代码 round 0 即 expected 5-8 but was 5-0）；CommitFrontierUpdate/MultiGroupIntegrationTest 读端改 StringCodec 适配明文 hash。
- 审计置信度：高（竞态真实，影响良性方向）

### MQ-12 管理路径全库 SCAN：`getKeys()` 不带 pattern [已修复]
- 位置：`mq/.../dlq/RedisDeadLetterAdmin.java:33`（pattern @28 已算出但没用）；`mq/.../admin/impl/RedisMessageQueueAdmin.java:464`
- 触发：大共享 Redis 上 `listTopics()`（或 pc<=1 主题的 `deleteConsumerGroup`）。
- 影响：阻塞式全库 SCAN，管理路径延迟/负载尖峰。
- 审计置信度：高
- 验证与修复：两处改为模式化 SCAN（B-15 同款 `getKeys(KeysScanOptions.defaults().pattern(...))`）：`RedisDeadLetterAdmin.listTopics` 用已算出的 `DlqKeys.dlq("*")` 模式，`RedisMessageQueueAdmin.deleteConsumerGroup` 的 pc<=1 回退用 `{streamPrefix}:{topic}:p:*` 模式；手工前缀/后缀过滤保留作第二道防线，扫描范围收窄而结果集不变。
- 回归测试：单测 `RedisDeadLetterAdminScanPatternTest` / `RedisMessageQueueAdminDeleteConsumerGroupScanTest`（旧代码 2/2 失败：`expected:<[orders, billing]> but was:<[]>`、`expected:<true> but was:<false>`——旧代码只调无参 `getKeys()`，模式化桩零命中即坐实全库扫描）；集成 `AdminPatternScanIntegrationTest`（真实 Redis：DLQ 主题清单含两个种子 DLQ 且分区流 decoy 不入列、pc=1 回退经扫描发现并删除 p:1 上的组）。

### MQ-13 保留量/硬上限回退路径把无界区间整体载入内存 [已修复]
- 位置：`mq/.../broker/impl/RedisBrokerPersistence.java:118-125`（`range(batch, MIN, MAX)` 只为拿 id）；`RedisMessageQueueAdmin.java:396`（`trimQueueByAge` 用 `Integer.MAX_VALUE`）
- 触发：积压远超 `retentionMaxLenPerPartition`（缩容配置）或对大流调 `trimQueueByAge`。
- 影响：整流（含 value）反序列化进堆；维护调用期间 OOM 风险。只需要 id，却拿了全量 map。
- 审计置信度：高
- 验证与修复：两处无界 `range(..., MIN, ...)` 改为有界分页扫描（COUNT=页大小 + id 游标推进）：硬上限救援按 `mq.retention.test.hardCapPageSize`（默认 500）分页删到 `toDelete` 为止；`trimQueueByAge` 按 `mq.admin.test.trimAgePageSize`（默认 500）分页删到短页/空页为止。终止性三重保障：短页即末页、游标严格前进否则终止、救援路径另有 `removed>=toDelete` 上界——对"每次返回同一页"的 mock 也保证收敛。页大小按实例读取（MQ-01 教训：不能 static final，共享 JVM 类加载顺序会吞掉测试配置）。删除语义与计数（RetentionMetrics/deletedTotal）不变。
- 回归测试：单测 `RedisBrokerPersistenceHardCapPagingTest`（size=7/maxLen=2/页 2 → 3 页删 5，捕获 range COUNT 参数断言全部 ≤ 页大小）、`TrimQueueByAgePagingTest`（3 页删 5，同款断言）——旧代码 2/2 失败且理由精确命中缺陷：`got [5]`（单次 range=toDelete）、`got [2147483647]`（单次 range=Integer.MAX_VALUE）。集成 `TrimQueueByAgePagingIntegrationTest`（真实 Redis：12 条、页大小 5 → 跨 5/5/2 三页全部删除、流清空）。既有钉 `anyInt()`/`eq(MIN)` 桩的 FallbackPaths/SprintCoverage/Behavior 等用例不改一字全绿（短页即断，首轮行为与旧单次调用一致）。

### MQ-14 null payload 经过 retry 桶往返后变成空字符串 [已修复]
- 位置：`mq/.../impl/RedisMessageConsumer.java:909`（Lua `HGET ... or ''`）、`:913`（XADD 无条件带 payload 字段）、`:715-719`（重试入队跳过 null payload 的 put）
- 触发：null payload 消息失败并走 scheduled-retry 路径。
- 影响：payload 类型跨重试改变（null → ""），按 null 分支的 handler 首次重试后行为改变。
- 审计置信度：高
- 验证与修复：mover Lua 的 payload 读取去掉 `or ''` 兜底（字段缺失时 HGET 返回 false，与存量为 `""` 天然可区分——空串在 Lua 中为真值），XADD args 改为仅在 payload 存在时携带该字段——null 经桶往返仍是"无 payload 字段"，解码侧 `data.get("payload")` 如实得 null；`""` 往返仍逐字保留为 `""`（不过度修正）。入队侧跳过 null put（保留"缺失"表示）与 ≤50ms 快速路径（removeIf(isNull) 本已保 null）不变。
- 回归测试：`RetryBucketNullPayloadIntegrationTest`（@Tag("integration")，真实 Redis，强制 backoff>50ms 走桶路径：无 payload 字段条目经 handler 首抛→桶→mover 重投，第二次投递 payload 仍为 null——旧代码失败理由精确命中缺陷 `expected: <null> but was: <>`；对照用例：显式 `""` payload 桶往返后仍为 `""`，新旧皆绿防误伤）。既有 mover 用例（RetryMoverBadField/RetryMoverLuaEdge/ConsumerWorkerFlow/RetryPayloadPassthrough 两路径 + 三个 mock 级 Coverage 类）不改一字全绿。

### MQ-15 rebalance 与租约续期任务可在多线程调度器上并发：check-then-act 产生重复 worker [已修复]
- 位置：`mq/.../impl/RedisMessageConsumer.java:955-978`（rebalance）、`:994-1002`（renew 移除）、`:67-68`（两个 `newScheduledThreadPool(schedulerThreads)`，默认 2）
- 触发：`schedulerThreads > 1` 时 rebalance 与 renewLeases 交错：renew 移除丢租约 worker 的同时 rebalance 看到 `containsKey==false` 再启一个同分区 worker。
- 影响：两个 worker 线程并发读同组同分区（消息仍按 consumer 名单次投递，无重复消费，但读交错、双重 `releaseIfOwner`、worker 数指标超 `maxLeased`）。`workers.size() >= maxLeased` 同为 check-then-act。
- 审计置信度：中（窗口窄，后果有限）
- 验证与修复：危害链的核心在 worker 退出路径：`runPartitionWorker` 的 finally 无条件 `releaseIfOwner(leaseKey, consumerName)`，而新旧 worker 携带**同一 owner 名**——renew 误判移除 w1、rebalance 经 `isOwner` 复活 w2 后，w1 排空退出时的 compare-and-delete 会删掉 w2 赖以持有的活租约（租约窃取，分区在续期察觉前无主/被他进程抢入）。修复两点：① rebalance 的 containsKey→tryAcquire→put 合并为单键 `workers.compute(pk, ...)`（槽位占用则原样返回，不再叠放），与 renew/unsubscribe 的移除按键原子；② worker 退出改为 compute 裁决——仅当注册项仍是自己或已被清空（stop/unsubscribe 语义保留）才释放租约，被继任者替换则跳过（自摘除+释放、替换则弃权）。`workers.size() >= maxLeased` 门槛保持：加 worker 者只有 rebalance 一处且 fixed-delay 不自重叠，交错只可能来自移除方（收缩方向，安全）。
- 回归测试：`ReplacedWorkerLeaseReleaseTest`（mock LeaseManager + 门控 broker.readGroup，确定性编排"renew 移除 → rebalance 复活 → w1 排空退出"全程，无需真实竞态线程；renew 前先等待 w1 确已停在第一次 readGroup（reads≥1 屏障）——机器重载下 worker 池线程可能晚启动，w1 未进读即退休会落进"槽位已空=自摘除即释放"的合法路径，属测试时序假设而非产品缺陷）——旧代码 T1 以 `MoreThanAllowedActualInvocations`（releaseIfOwner 被排空的退役 worker 调用，窃取继任租约）失败，精确命中缺陷；对照 T2/T3 钉住 stop()/unsubscribe() 退出路径仍即时释放（新旧皆绿，防过度修正）。既有 ConsumerLeaseRebalance/ConsumerRebalanceClaim/ConsumerWorkerFlow 集成与 3 个 mock 级 Coverage、LeaseManagerTest 不改一字全绿。

### mq 审计确认无问题项

近期修复均成立：XADD 前 `data.values().removeIf(isNull)`；重试路径 String payload 直通（无二次编码）；enqueue-before-ACK 顺序正确；`moveDueRetries` 的 ZRANGEBYSCORE+ZREM+DEL 在同一 Lua 内原子。Redisson `StringCodec` 对非 String 值做 JSON 编码，headers Map 在 `StreamEntryCodec` 的往返可靠（`StreamEntryCodecRoundTripTest` 覆盖）。DEFER_ACK 的 no-ack 行为是文档化的运行时协调语义（`MqHeaders.java:30-36`，`RuntimeDeferAckAckAllIntegrationTest` 覆盖），不计为 bug。

---

## cdc 模块审计结果（CDC-C1、CDC-H1~H5、CDC-M1~M7、CDC-L1~L9）

### 严重（Critical）

### CDC-C1 PostgreSQL 解析器对真实 test_decoding 流静默丢弃所有变更事件（格式不匹配） [已修复]
- 位置：`cdc/.../impl/PostgreSQLLogicalReplicationCDCConnector.java:245-254`
- 触发：任何真实 PostgreSQL `test_decoding` 流。test_decoding 每个变更输出为**单行** `table public.users: INSERT: id[integer]:1 ...`；循环先匹配 table 模式就 `continue`，同行的 INSERT/UPDATE/DELETE 匹配器永远看不到。
- 影响：完全静默丢数据：连接器"启动成功"、健康状态 HEALTHY、零事件产出。单元测试只过了是因为喂的是合成格式（table 行与 INSERT 行分开两行，`PostgreSQLLogicalReplicationCDCConnectorParsingTest.java:32-37`），掩盖了 bug。
- 审计置信度：高
- 验证与修复：删除 table 匹配后的无条件 `continue`，让操作匹配器继续看同一行的剩余部分（真实单行格式命中；合成的纯 table 行无操作符则自然落空）。新增回归测试 `parseLogicalMessageHandlesRealSingleLineTestDecodingFormat`（三种操作各一行单行格式，断言 3 个事件与表名/前后镜像）。

### 高（High）

### CDC-H1 MySQL 连接器重启丢弃已保存水位并清空未交付队列（3b262e7 只修了轮询连接器） [已修复]
- 位置：`cdc/.../impl/MySQLBinlogCDCConnector.java:52-56,69-72,94`
- 触发：同一连接器实例 `stop()` 后 `start()`（或任何重跑 `doStart` 的重连路径）。
- 影响：`doStart` 从**配置**重读 binlog 文件名/位置，覆盖 `handleRotateEvent`/`updateCurrentPosition` 推进的实时水位。未配置文件名时（常见）从服务器当前位重启 → 停机窗口内事件全部丢失；配置了固定位 → 全量重放 → 重复。且 `doStop` 调 `eventQueue.clear()`（:94），已捕获未交付事件被丢——正是 3b262e7 为轮询连接器修掉的同类问题。
- 审计置信度：高
- 验证与修复：`doStart` 只在字段为 null（首次启动）时才从配置读文件名、只在位置为 0 时才从配置读位置，重启保留实时水位；`doStop` 不再清空 eventQueue（注释说明语义）。跨进程重启仍无持久化（与轮询连接器相同的既有边界，见审计备注）。

### CDC-H2 PostgreSQL 连接器 doStop 清空未交付队列；内存恢复跳过丢失事件 [已修复]
- 位置：`cdc/.../impl/PostgreSQLLogicalReplicationCDCConnector.java:103,205-207`
- 触发：事件已解析进 `eventQueue` 但消费方未拉取时 stop/start。
- 影响：重启后 `startReplicationStream()` 从 `lastReceivedLSN`（最后**收到**而非最后**交付**的 LSN）恢复，被清空的未交付事件不再重发 → 永久丢失。3b262e7 的"重启保留水位与未交付队列"只在 `DatabasePollingCDCConnector` 实现。
- 审计置信度：高
- 验证与修复：`doStop` 不再清空 eventQueue；重启先排空保留的队列再从 lastReceivedLSN 续流，未交付事件不再丢失。

### CDC-H3 断连后无重连（MySQL 与 PostgreSQL）——静默永久停摆 [已修复]
- 位置：`MySQLBinlogCDCConnector.java`（一次性 `connect()`，无 LifecycleListener）；`PostgreSQLLogicalReplicationCDCConnector.java`（catch SQLException 后继续轮询死流）
- 触发：网络中断、MySQL 重启、PG 故障切换。
- 影响：`mysql-binlog-connector-java` 0.29.2 不自动重连；断连不可检测。`poll()` 持续返回空列表、health 保持 HEALTHY。PG 侧 LSN 反馈停止 → 服务端 WAL 在 slot 中无限堆积。
- 审计置信度：高
- 验证与修复：
  - **MySQL**（`MySQLBinlogCDCConnector`）：注册 `LifecycleListener`（`onConnect`/`onCommunicationFailure`/`onDisconnect`）→ 断连翻转 UNHEALTHY + `onConnectorError` 通知；后台单线程重连循环（`compareAndSet` 防重入，指数退避 `reconnect.backoff.initial.ms`（默认 1000）→ `reconnect.backoff.max.ms`（默认 30000）），重连前按 CDC-H1 活水位（`binlogFilename`/`binlogPosition`）`setBinlogFilename`+`setBinlogPosition` 续读；`doPoll` 心跳看门狗兜底（`isConnected()==false` 走同一断连路径）；`start()` 由阻塞 `connect()` 改为 `connect(connect.timeout.ms)`（默认 10000，旧代码 start 永不返回、health 永远到不了 HEALTHY）；主动 `stop()` 不触发断连误报、重连线程即时关闭。
  - **PostgreSQL**（`PostgreSQLLogicalReplicationCDCConnector`）：拉模型内检测与重建——`processReplicationMessages` 捕获 SQLException 后拆除死流（`replicationStream=null`）并**立即**关闭坏连接、翻转 UNHEALTHY；下一轮 `poll()` 按 `reconnect.backoff.ms`（默认 1000）限频重建连接与流（`openConnection` 提为 protected 接缝），从 `lastReceivedLSN` 续读。slot 流失效判定：错误消息匹配（`was invalidated`/`cannot continue replication`/slot `does not exist`）+ 服务端探测 `pg_replication_slots.lost`/行消失 → `slotInvalidated=true` 永久停止重连并以 UNHEALTHY 响亮报告（不再静默重建跳过已丢弃的 WAL）；`streamEverStarted` 守卫：首次流启动成功前 slot 缺失只视为"未就绪"（`running` 在 `doStart()` 前置位，早到的 poll——如 CDCManager 调度器与 `start()` 竞态——不得误判 invalidated 永久停摆）。
  - 关键坑：断连路径禁止 `isValid()` 探测——对已被服务端终止的复制连接，pgjdbc 的 `isValid(1)` 实测阻塞 ~20s（超时参数在该状态下不生效），导致健康态翻转迟到无用；读失败的连接一律直接 `close()`（非阻塞）。
- 回归测试：`CdcH3DisconnectReconnectTest`（纯单测，8 用例，经反射/行为触达新接缝）：MySQL 断连翻转 UNHEALTHY（消息含 CDC-H3）+ listener 通知 + 从水位（`mysql-bin.000004:8200`）重连、`poll()` 看门狗检测死客户端且 3 次退避失败后恢复、主动 stop 不误报不重连；PG 死流拆除（stream/connection 双 null）+ UNHEALTHY、下一轮 poll 重建流（assertSame）并在 lastReceivedLSN 续读、invalidated 消息 / `lost=true` / slot 行消失三路失效全部响亮停摆（不再重连不再重建）。旧代码 8/8 红：3 例精确命中缺陷本身（`expected: <UNHEALTHY> but was: <HEALTHY>`），其余因新接缝缺失失败。`CDCDisconnectReconnectIntegrationTest`（@Tag integration，端口不可达时 assumption 跳过；拉消费者驱动线程等价 CDCManager 的 pollAll 调度）：真实 MySQL 上 KILL `Binlog Dump` 线程 → UNHEALTHY → 退避重连 HEALTHY → 断连后新写入照常送达；真实 PG 15（wal_level=logical + test_decoding）上 `pg_terminate_backend` 终止 walsender → UNHEALTHY → 重建连接/流 → 续读送达。旧代码两例皆红：MySQL `start().get(30s)` 直接 `TimeoutException`（阻塞 connect 永不返回）；PG 死流在 stop 时爆 `Failed to stop connector`（health 恒 HEALTHY 无检测路径）。

### CDC-H4 轮询连接器 commit() 在第一个冒号处截断时间戳水位 [已修复]
- 位置：`cdc/.../impl/DatabasePollingCDCConnector.java:124-134`（doCommit）、`136-147`（doResetToPosition），对照 `:342`、`:320`
- 触发：按文档 API 流程 `connector.commit(event.getPosition())`，且轮询列是 TIMESTAMP/DATETIME（**默认列**就是 `updated_at`）。位置格式为 `table + ":" + Timestamp.toString()` → `"orders:2024-01-01 10:15:30.0"`。
- 影响：`position.split(":")` 取 `parts[1]` 存下 `"2024-01-01 10"`。下次轮询 `WHERE updated_at > '2024-01-01 10'` → 要么每次报错（连接器卡死，错误被 pollTablesForChanges 吞掉）要么比较点提前 → 大量重复。
- 审计置信度：高
- 验证与修复：`doCommit`/`doResetToPosition` 改为 `split(":", 2)`（保留首个冒号后的完整时间戳），同时保留"空表名/空值视为畸形忽略"语义（既有 `commitAndResetSkipMalformedPositions` 用例覆盖）。

### CDC-H5 CDCManager.getCurrentPositionsAll() 在任一连接器尚无位置时抛 NPE [已修复]
- 位置：`cdc/.../CDCManager.java:200-206`
- 触发：`start()` 后首个 binlog/复制事件或首轮轮询扫描之前调用。
- 影响：确定性 NPE：`Collectors.toMap` 用 `Map.merge`，拒绝 null value。标准监控调用即崩。
- 审计置信度：高
- 验证与修复：改为逐连接器取值并跳过尚无位置者（javadoc 注明省略语义）。

### 中（Medium）

### CDC-M1 无背压：无界队列 + 每轮无界扫描 [已修复]
- 位置：`DatabasePollingCDCConnector.java`（`pollTableForChanges` 扫描与 `eventQueue`）；`MySQLBinlogCDCConnector.java`（`eventQueue`）；`PostgreSQLLogicalReplicationCDCConnector.java`（`eventQueue`）
- 影响：消费慢于生产或大表基线扫描时 OOM。
- 审计置信度：高
- 验证与修复：三个连接器统一为**有界队列 + 不丢事件的阻塞式背压**，轮询扫描改为**分批拉取**；新增共享配置解析 `BackpressureSettings`（包私有）：
  1) `eventQueue` 由 `ConcurrentLinkedQueue` 改为 `ArrayBlockingQueue`，容量经属性 `event.queue.capacity`（默认 10000）。生产侧以 50ms 分片 `offer` 阻塞重试：轮询连接器停止/中断时返回 false 中止扫描——**emit-then-advance**，持久化水位只随实际入队的行推进，中止轮次未发的行重启后重扫；MySQL/PG 推送连接器停止时抛 `IllegalStateException`（由 `handleBinlogEvent`/WAL 读取路径捕获并上报 onConnectorError），异常发生在尾部水位推进（`updateCurrentPosition`/`lastReceivedLSN` 赋值）**之前**，水位不越过未交付事件，重启重放（at-least-once，无缺口）。
  2) 轮询扫描改为 `LIMIT pollBatchLimit+1` 探针分批（属性 `poll.batch.limit`，默认 1000）：≤limit 行即排空；=limit+1 判为截断，且**水位平局组不跨批切割**——边界值相等的尾部行留给下一批（经 `> lastEmitted` 重读，绝不丢、绝不重），整批同值的病态数据告警后强制推进防死循环；单轮 `MAX_BATCHES_PER_ROUND=1000` 兜底（如自增列全 NULL 水位无法推进时不无限转）。基线（`initializeLastPolledValues` 用 `SELECT MAX`，本就单值）与快照/大表扫描同受每语句上限约束——**单条 SQL 永不全表**。
  3) 两属性缺失/非数值/≤0 一律回退默认并 log.warn——typo 不得静默解除保护；`positiveInt` 对 null configuration 也回退默认，保住既有「构造器容忍 null config」契约。
- 回归测试：`PollingBackpressureBatchingTest`（@Tag integration，H2 内存库 + 录制代理 DataSource）：0 行/1 行/恰一批（单语句）/19 行跨 5 批逐条不重不乱、每条扫描 SQL 断言带 `LIMIT n+1` 与续扫谓词、水位平局组不切割（limit=4、值 1,2,3,9,9,9,9 → 7 行恰好各一次、水位=9）、容量 3 时扫描线程阻塞不丢事件且逐条排空后恢复（10/10 有序、水位=10）、停止时中止且水位停在最后入队行（t:3）、非法 `poll.batch.limit` 回退默认（SQL=`LIMIT 1001`）；`EventQueueBackpressureTest`（纯单测，mock binlog 事件反射编排）：默认/非法容量=10000、容量可配（ArrayBlockingQueue 断言）、队列满时生产线程阻塞不增长队列、排空后恢复且 3/3 事件有序送达、水位随交付推进到 :400、停止时水位停在未交付事件之前（:200）且队列保留、drop 以 onConnectorError 上报（响亮非静默）；`BackpressureSettingsTest`（解析回退矩阵：缺失/abc/0/-5/12.5/空白→默认，42/" 7 "/Integer 64 透传）。受影响旧行为的既有测试同步更新（旧代码按旧契约写）：三处扫描 SQL stub 补 `LIMIT 1001`、`doPollSkipsEntriesVanishedUnderConcurrentDrain` 的 racy 队列改实现 BlockingQueue（非阻塞成员行为不变）、snapshot 用例补 `running` 标志（新入队路径 running-aware）。

### CDC-M2 lastPolledValues（HashMap）跨线程数据竞争 [已修复]
- 位置：`DatabasePollingCDCConnector.java:32,130,143,226,318-320`；`AbstractCDCConnector.java:90-116`（poll() 无同步）
- 影响：并发扫描同水位 → 重复事件；非同步 HashMap 并发写可损坏结构。
- 审计置信度：高（竞态存在），中（实际频率）
- 验证与修复：三个写入方确认跨线程——调度器轮询（`newLastValue` 守卫写入）、用户线程 `commit()`/`resetPosition()`（doCommit/doResetToPosition 直接 put）、公共快照 `getLastPolledValues()` 任意线程读；行 249/342 均先判空、doCommit 只存非空 String，故 null 不友好的 ConcurrentHashMap 可作一行等价替换（getter 的 `new HashMap<>(...)` 拷贝语义不变）。并发扫描同水位一半（AbstractCDCConnector poll 无同步）不属本条：`eventQueue` 已是 ConcurrentLinkedQueue，doPoll 出队路径无共享可变结构。
- 回归测试：`LastPolledValuesRaceTest`（纯单测，只用修复前公共/同包面：8 线程并发 doCommit 各异键 × 1 线程持续快照拷贝 × 24000 键）——旧代码以 `ConcurrentModificationException`×2957（拷贝中检测到结构损坏）精确失败；新代码连跑 4 次全绿（CHM 下为结构性确定）。:cdc 全模块单测其余不改一字全绿。

### CDC-M3 调度器在 listener 中途注销时仍会取出并丢弃事件 [已修复]
- 位置：`AbstractCDCConnector.java:233-241,301-309`（行号已随修复偏移：现调度块在 `startScheduledPolling`，二次读 null 在 `notifyEvent`）
- 影响：`poll()` 已排空批次后 listener 变 null → 事件静默丢弃（窄窗口）。
- 审计置信度：高（路径确定），低（概率）
- 验证与修复：机制核实——调度任务先判 `eventListener == null` 再 `poll()` 排空批次，`notifyEvent` 在投递时**二次重读** `eventListener`：窗口期内 `setEventListener(null)` 使已出队批次静默丢弃。修复：调度块内**单次捕获** `CDCEventListener listener = eventListener`——null 则直接 return（批次留给 pull 消费者，既有语义不变）；非空则排空后把批次直接投递给捕获引用（异常隔离与 `notifyEvent` 同款 catch/warn）。注销竞态下的最坏行为从"静默丢事件"变为"最后一投递仍达已捕获的监听者"。其余通知路径（poll 内 capture 计数、error/health/commit 通知）不取队列载荷、维持 `notifyEvent` 原样不动。
- 回归测试：`CDCListenerDeregistrationDropTest`（纯单测，测试内 `AbstractCDCConnector` 子类走生产同款接线 `doStart → startScheduledPolling`，只用修复前 API 可对旧代码编译）——门控 `doPoll`：排空 3 事件后挂起 → 测试在 drain→notify 窗口内 `setEventListener(null)` → 释放门闩 → 旧代码批次被静默丢弃（`delivered` 恒空，断言超时失败，精确命中缺陷）；新代码批次仍送达已捕获监听者（3/3），连跑 2 次全绿。:cdc 全模块单测（含 M2 race、M5 位点、既有 EventHandling/M4 泄漏等）不改一字全绿。

### CDC-M4 失败路径泄漏调度器（非守护线程，阻止 JVM 退出）与 Hikari 池 ✅已修复
- 位置：`AbstractCDCConnector.java:57-88`（doStop 先于 scheduler 关闭块抛出则调度器泄漏）；`DatabasePollingCDCConnector.java:87-91`
- 影响：`stop()` 中 `doStop()` 抛出 → 跳过 `scheduler.shutdown()`，非守护线程泄漏、JVM 无法退出；`start()` 中 `doStart()` 在 `startScheduledPolling()` 之后失败 → 调度器对着 `running=false` 的连接器永转；轮询连接器建池后基线查询失败 → Hikari 池与其连接永不关闭。
- 验证与修复：`stop()` 的调度器关闭移入 `finally`（原异常照常上抛、健康状态如实变 unhealthy）；`start()` 失败路径补调度器关闭；`DatabasePollingCDCConnector.doStart()` 对建池后的步骤加 try/catch，失败即关闭池（关闭异常 `addSuppressed` 不掩盖原异常）。回归测试 `CDCFailurePathLeakTest`（三个失败路径各一条断言，只用新旧共有 API 可对旧代码编译；旧代码 3/3 按预期失败：`isShutdown()`/`isClosed()` 均为 false；新代码 3/3 绿，且 start/stop 失败仍如实传给调用方）。

### CDC-M5 MySQL 事件位置差一 [commit 后重启重复投递 [已修复]]
- 位置：`MySQLBinlogCDCConnector.java:231,265,298` vs `:182`（行号已随修复偏移：现派发在 `handleBinlogEvent` switch，盖章在 rows handler 内）
- 影响：事件 E 的行带的是 E **前一个**事件的位置；从该位置恢复会重放 E → 重复写入。首个事件 position 为 null。
- 审计置信度：高
- 验证与修复：机制核实——监听器在 switch 之后统一 `updateCurrentPosition(event)`（推进水位到本事件末尾），而三个 rows handler 在此之前就用 `getCurrentPosition()` 给 ChangeEvent 盖章，故每个事件携带的都是前一事件的水位；commit 这样的位点后 `doResetToPosition` 恰好从 E 之前重放。修复（后续随 CDC-M1 微调后定稿）：WRITE/UPDATE/DELETE（含 EXT_ 变体）的盖章改由 `endPositionOf(event)` 计算——读 header 的 nextPosition 得到本事件末尾，但**不**变更水位——作为参数传入 rows handler；水位仍由监听器尾部的 `updateCurrentPosition(event)` 统一推进，且仅在全部行实际入队（有界队列接受）之后才发生。与 M1 的配合由此成立：停止时入队被拒的异常使尾部推进不执行，水位永不越过未交付事件（重启重放）。对被过滤/无表映射而 0 事件输出的 rows 事件尾部推进水位无害——它本无可提交物，恢复时其 TABLE_MAP 亦无需重放（后续事务自带）。
- 回归测试：`MySQLBinlogPositionOffByOneTest`（纯单测，mock binlog Event/EventData，沿用既有 `MySQLBinlogCDCConnectorEventHandlingTest` 的反射编排 harness，只用修复前 API 可对旧代码编译）——旧代码 2/2 按缺陷特征失败且实际值精确等于“前一事件位置”（`expected :200 but was :100`、`expected :250 but was :150`）；新代码 2/2 绿。既有 `MySQLBinlogCDCConnectorEventHandlingTest`（含 ROTATE 后 `getCurrentPosition()==mysql-bin.000002:4` 钉）与 :cdc 全模块单测不改一字全绿。

### CDC-M6 CDCManager 重启永久破坏健康监控（复用已终止的调度器） [已修复]
- 位置：`CDCManager.java:21,100,125-135,237-245`
- 影响：stop() 关闭单一 scheduler 字段后再次 start() → `RejectedExecutionException`（藏在 thenRun 回调里）→ 健康监控死亡。
- 审计置信度：高
- 验证与修复：scheduler 改为 volatile 字段，startHealthMonitoring 惰性重建，stop() 关闭后置 null。

### CDC-M7 PG parseColumnData 按空白切分值 [行数据静默损坏 ✅已修复]
- 位置：`PostgreSQLLogicalReplicationCDCConnector.java:332-363`（:340 `data.split("\\s+")`）
- 触发：任何含空格的文本值 `name[text]:'John Doe'`。
- 影响：`"John Doe"` 变 `"John"`，`Doe'` 丢弃；字面量 `'null'` 与 SQL NULL 不可区分（:349）；朴素去引号毁掉转义引号。
- 审计置信度：高
- 验证与修复：`parseColumnData` 重写为引号感知的逐字符 tokenizer（`inQuote` 状态机，引号内空白不切分），去引号时 `''→'`；新增 `coerceByPgType` 按 PG 类型把 integer/bigint/numeric/bool 等解析为对应 Java 类型，解析失败回退原始字符串。回归测试 `quotedValuesKeepTheirSpacesAndEscapedQuotes`：`name[text]:'John Doe'`→"John Doe"、`city[text]:'O''Hare'`→"O'Hare"、`note[text]:plain`、"id[integer]:7"→Integer 7。

### 低（Low）

- **CDC-L1** 列名解析器负缓存（`MySQLColumnNameResolver.java:45-52`）：瞬时失败被 `computeIfAbsent` 缓存为空列表，该表列名永久解析不出（col_0/col_1…）直到重启；`DriverManager.getConnection` 在 binlog 事件线程上执行（:61），每张未知表阻塞事件消费至超时。 [已修复：cache 仅存成功非空解析（MySQLColumnNameResolver.java:45-50 注释），负缓存消除]
- **CDC-L2** 配置校验缺口（`CDCConfigurationBuilder.java`）：`validate()`（:325-329）只查 name；`batchSize<=0` → doPoll 永不排空、队列无限增长；负 `pollingIntervalMs` 通过校验但在调度时抛 IAE；`(Boolean) properties.getOrDefault(...)`（:311,316）对字符串 "true" 抛 ClassCastException；PG `statusIntervalMs` `(int)` 截断（:203）；`CDCConnectorFactory.create`（:47）null 类型名抛 NPE 而非 IAE。
- **CDC-L3** 指标 lost updates（`AbstractCDCConnector.java:109-110,124-125,273-274`）：`metrics.get()/set()` 无 CAS。
- **CDC-L4** `CDCManager.addConnector` check-then-act（:30-34）：`containsKey` 后 `put` 可静默替换同名连接器，应 `putIfAbsent`。 [已修复：CDCManager.java:34-36 已改 putIfAbsent（含注释）]
- **CDC-L5** MySQL `doCommit`/`doResetToPosition` 边界（:117-121,131-139）：无 `parts.length` 检查 → 尾冒号位置 AIOOBE；`doResetToPosition` 在 start() 前调用对 `binaryLogClient` NPE。
- **CDC-L6** `ChangeEventQueueSink.invoke`（:49-56）：TimeoutException 未取消在途发送 → 迟到完成在重试时重复事件；InterruptedException 清掉中断标志后传播。
- **CDC-L7** 跨线程字段可见性：`AbstractCDCConnector.currentPosition`（:25）、`MySQLBinlogCDCConnector.binlogFilename`（:29）无 volatile。 [部分修复：currentPosition 已 volatile（AbstractCDCConnector.java:25），binlogFilename 仍无（MySQLBinlogCDCConnector.java:40）]
- **CDC-L8** 快照双重投递窗口（`DatabasePollingCDCConnector.java:313-321`）：整表扫描完才推进 lastPolledValues，扫描中途失败/停止重入队已扫过的行；带快照重启时重复触发 onSnapshotStarted（:202-207）。 [已修复：CDC-M1 批次 emit-then-advance（:312-313 highWater 仅推进已入队行，:396-397）+ 水位跨 stop/start 存活（:131），中断轮次续扫不重投]
- **CDC-L9** 序列化：`CDCSource` 实现了 Serializable 但持有非 transient 的 `CDCConnector`（Hikari/BinaryLogClient 不可序列化）→ NotSerializableException；`ChangeEvent` 无 serialVersionUID。 [部分修复：CDCSource 已有 serialVersionUID（:20），ChangeEvent 仍无；connector 字段非 transient 仍未改]

### cdc 审计确认无问题项

`ChangeEvent` 4 参构造器无递归调用问题；`TableFilter` 通配符正则转义正确；`CDCSource` 空转退出为文档化行为；`CDCMetrics.withEventCounts` 总数计算正确；MySQL ALTER 换 table id 后缓存会重新解析；Hikari 池参数合理；XID 双重 `updateCurrentPosition` 幂等无害；3b262e7 对轮询连接器的实例内修复本身有效（`DatabasePollingRestartResumeTest` 验证），不完整的是 H1/H2（另两个连接器）与跨进程持久化缺失。

---

## runtime + core 模块审计结果（RT-H1~H3、RT-M1~M7、RT-L1~L11）

（InMemoryWindowedStream 的 trigger/merge 逻辑与 core WindowAssigner 为本轮已重写区域，按指示跳过深审。）

### 高（High）

### RT-H1 checkpoint 恢复失效：Map<Integer,String> 的 key 经 JSON 往返变 String，每次恢复都从 0-0 重放 [已修复]
- 位置：`runtime/.../redis/internal/RedisRuntimeCheckpointManager.java:273-293`（快照）、`419-420`（恢复）；序列化在 `checkpoint/.../redis/RedisCheckpointStorage.java:37-41`（默认 Jackson codec）
- 触发：任何带 `restoreFromLatestCheckpoint=true` 的重启。
- 影响：offset 快照能存进去，但内层 map 读回来是 `Map<String,String>`；未检 unchecked 赋值掩盖了这一点，`get(Integer)` 永远 null → `startId` 恒为 `"0-0"` → 每次恢复整流从头重放；`sinkDeduplicationEnabled=false` 时重复副作用。offset checkpoint 功能静默失效。
- 审计置信度：高（机制确定）
- 验证与修复：新增 `offsetForPartition` 查找：Integer key miss 时回退 String key（并兼容两种 key 混存，Integer 优先）；恢复失败日志从 DEBUG 升到 WARN。回归测试 `RedisRuntimeCheckpointManagerOffsetsTest`（4 用例：String-keyed 恢复命中、进程内 Integer 命中、缺失分区回退、混存优先级）。

### RT-H2 stop-the-world checkpoint 期间做全库 SCAN：消费者暂停时长随整个 Redis 库大小伸缩 [已修复]
- 位置：`checkpoint/.../RedisCheckpointStorage.java:57-81`（`keys.getKeys()` 无 pattern）；调用点 `RedisRuntimeCheckpointManager.java:146,552-579,240`
- 触发：每个周期 checkpoint tick（STW 流程先 pause 消费者再 triggerCheckpoint）。
- 影响：`listCheckpoints` 对整个 keyspace SCAN 后逐 key GET 再排序。checkpoint 延迟（即消费暂停时长）与 DB 总键数成正比而非 checkpoint 数；共享/生产 Redis 上每个 tick 都卡住全部管道消费；`deferAckUntilCheckpoint=true` 时直接拉长未 ack 窗口，放大 claimIdleMs 重投递竞态。
- 审计置信度：高
- 验证与修复：两个层次。（1）扫描宽度——B-15 已把 `listCheckpoints` 的遍历改为前缀模式化 SCAN（`KeysScanOptions.defaults().pattern(keyPrefix + "*")`，纯数字后缀过滤保留），本条"与 DB 总键数成正比"的部分随之消除；（2）暂停窗口内的逐 key 完整反序列化——修复前每个 tick 在 pause 与 resume 之间执行 `cleanupOld()` → `listCheckpoints(Integer.MAX_VALUE)`，把保留的全部 checkpoint（每个含完整状态快照）逐个反序列化再排序（老代码事件序 `pause, store, listCheckpoints, …, resume` 实证），暂停时长仍随 `checkpointsToKeep × 快照大小` 伸缩。修复：`RedisRuntimeCheckpointManager.triggerCheckpoint` 新增带 `cleanupRetainedAfterStore` 参数的重载（缺省 true，原有调用方语义不变），`cleanupOld()` 改为 public；STW 路径 `RedisStreamExecutionEnvironment.triggerCheckpointInternal` 拆为"暂停窗口内 checkpoint（cleanup=false）+ resume 后统一 `cleanupOld()`"两段——清扫成本移出暂停窗口，淘汰语义不变（先不完整后最旧、保留 keepCount，见 B-14）。残留（非本条 STW 范围）：启动期 `initNextId`/`restoreFromLatestCheckpointOrNull`/`getLatestSinkCommittedCheckpoint` 仍走全量列表，成本以 `checkpointsToKeep` 为界，且不在消费暂停窗口内。
- 回归测试：`RedisStreamExecutionEnvironmentCleanupOffPauseTest`（pause/resume/listCheckpoints 事件序断言清扫发生在 resume 之后；3 次 trigger + keep=2 断言最旧者淘汰、留存 {2,3}）+ `RedisRuntimeCheckpointManagerCleanupDeferralTest`（4 参延迟清扫重载存在性、deferred trigger 零清扫、显式 `cleanupOld()` 裁剪语义）。旧代码复现：3 用例在修复前全数失败——env 级 `the retention sweep ran while the consumers were still paused (events=[pause, store, listCheckpoints, store, resume])`、`no sweep may precede the first resume`、manager 级 `triggerCheckpoint has no deferred-cleanup variant`。

### RT-H3 窗口/定时器状态在 emit 前被清除：sink 发送失败即永久丢失该窗口已累加数据 [设计缺陷]
- 位置：`runtime/.../redis/internal/RedisStreamBuilder.java`（reduce 516-539、aggregate 575-595、sum 713-738、count 758-777、apply 654-672）
- 触发：窗口 fire（due zset 先移除）后 `sink.invoke` 抛异常（Redis 抖动、sink 异常、checkpoint 中止）。
- 影响：消息进 RETRY 重投递，但窗口状态已在 `finally` 里删掉 → 重投递的元素单独重新累计，窗口随后以残缺数据 fire——静默错误结果。apply 更糟：状态清除发生在缓冲结果 emit 之前。
- 处理：fire-and-purge 非原子是该设计的固有问题，需两阶段/结果缓冲提交重构；记为后续任务。

### 中（Medium）

- **RT-M1** `checkpointDrainTimeout=ZERO` 使排空循环死循环且消费者永久 pause（`RedisStreamExecutionEnvironment.java:657-678`；deadline 检查被 `>0` 门控，ZERO 通过校验）→ checkpointing 标志永不释放，作业死锁无报错。✅已修复：builder 将 null/ZERO/负值统一回退 30s 默认；排空循环的 deadline 检查改为无条件（防御性）。回归断言加入 `RedisRuntimeConfigBuilderCoverageTest`。
- **RT-M2** 事件时间定时器队列满时静默丢弃注册（`RedisPipelineRunner.java:406-429`）→ 对应窗口/回调永不 fire，仅 60s 限速 warn。
- **RT-M3** watermark 与窗口 fire 纯消息驱动：空闲分区/子任务永不 fire；`windowMaxFiresPerRecord=256` 截断后剩余窗口要等下一条记录；`markIdle()` 是 no-op（`RedisPipelineRunner.java:100-113,163-184`、`RedisStreamBuilder.java:451-454,791-810`）。
- **RT-M4** `restoreState` 先删后建无原子性：中途失败状态已清空、作业以无状态继续（`RedisRuntimeCheckpointManager.java:469-549`；失败只 debug/warn）。
- **RT-M5** offset 快照期间 Redis 错误被 DEBUG 吞掉且存 null → 恢复时该分区静默回退 0-0（`RedisRuntimeCheckpointManager.java:283-292`）。✅已缓解：日志升为 WARN 并明示"该分区将回退 0-0"；恢复失败日志同样升 WARN（彻底修复需失败即中止 checkpoint，涉及策略决策，留待后续）。
- **RT-M6** sink 去重是 check-then-act 两跳（`RSetCache.contains` 与 `add` 之间夹着 `sink.invoke`，`RedisPipelineRunner.java:186-230`）：并发重投递下双写（文档已注明 best-effort）。
- **RT-M7** 同源分叉的两个 pipeline 按消费组分摊而非广播，且同种窗口算子 stateName 冲突共享状态（`RedisStreamBuilder.java:413,185-194`）→ 分叉用法下窗口结果静默错误；无校验无文档。

### 低（Low）

- **RT-L1** sink commit 失败仍返回非 null Checkpoint（`RedisStreamExecutionEnvironment.java:727-737`），调用方无法区分。 [部分修复：2PC 重构后 commit 失败的 checkpoint 保持未标记且可经 recoverAndCommit 恢复，调用方可经 sinkCommittedMarker 区分（RedisStreamExecutionEnvironment.java:1076-1105 注释契约）；返回值仍非 null（该 checkpoint 确已持久化，属设计内）]
- **RT-L2** DeferredAcks key `topic|group` 分隔符歧义 + `ackAll` 用最后 poll 的 id 而非 max 推进 frontier（:805-887）；含 `|` 的 topic 使 ack 静默失败。 [部分修复：frontier 已原子 Lua max-CAS 防回退（MQ-11，:1308-1318）；`split("\\|", 2)` 分隔符歧义仍在（:1287），maxId 仍取列表末值（依赖 poll 升序）]
- **RT-L3** 分区数回退为 1 时，checkpoint/恢复/frontier 只覆盖分区 0（`TopicPartitionRegistry.java:48-61` 等）。
- **RT-L4** 同 jobName 双进程 checkpoint id 撞车互相覆盖（`RedisRuntimeCheckpointManager.java:95-126`）。 [已修复：initNextId 构造快照 + 领导权接管时 refreshCheckpointIdFromStorage 重对齐计数器（单调 max，RedisRuntimeCheckpointManager.java:138-165）]
- **RT-L5** `sinkCommittedMarker` 无 TTL 且 `checkpointsToKeep=0` 时清理禁用 → 无界增长（:177-185,552-556）。 [部分修复：marker 随 checkpoint 淘汰一并删除（cleanupOld :841-846）；`keep<=0` 仍整体禁用清理、marker 本身仍无 TTL]
- **RT-L6** `listCheckpoints` 仅按时间戳排序，同毫秒 tie 时 getLatest 可能取旧（`RedisCheckpointStorage.java:76`）。
- **RT-L7** 窗口 member 编码对含 `\u0001` 的字符串 key 解析错乱（`RedisStreamBuilder.java:371,779-823`）。
- **RT-L8** `NumberAggregationUtils.add` long 溢出静默、BigInteger/BigDecimal 截断（`NumberAggregationUtils.java:29-32`）。
- **RT-L9** `addSource` 无法停止不终止的 source（`StreamExecutionEnvironment.java:75-122`；`cancel()` 从不被调）。 [部分修复：SourceContext 提供 isStopped() 协作式停止协议（内存运行时）；不检查该标志的 source 仍无法中止，cancel() 仍不存在]
- **RT-L10** `InstanceIdGenerator.generateLocalInstanceId` 忽略 serviceName 且 javadoc 与实现不符（core `InstanceIdGenerator.java:39-43`）。 [已修复（按文档化语义）：javadoc 现明示 "hostname:port" 与实现一致（:38）；serviceName 入参按文档即为不用]
- **RT-L11** `StateDescriptor` 无校验：null type 时 `toString()` NPE、`RedisKeyedValueState.value()` 反序列化 NPE（core `StateDescriptor.java:19-32,57`）。

### runtime/core 审计确认无问题项

`RedisKeyedStateStore` 的 currentKey/currentPartitionId 为 ThreadLocal 且 operator 在 try/finally 中设置/清除（多子任务/定时器线程无 key 泄漏）；`SubscriptionOptions` 子任务重建无字段丢失；DEFER_ACK 协议头检查顺序无 ack/去重竞态，frontier 不会后退；commit-frontier 建组语义正确且 BUSYGROUP 已处理；`executeAsync` 失败清理完整；`fireDueEventTimers` 锁外回调 + seq 决胜无饥饿；watermark 溢出保护与 CAS 单调性正确；`InMemoryDataStream` 迭代器契约完整；`InMemoryCheckpointCoordinator` 派生流不重复注册 store；sink 生命周期幂等；`SystemUtils` 双检锁正确。

---

## 验证与修复记录（本轮收尾）

本轮系统性审计共产出 **103 项**发现（另：B-04 后补验证修复）：B-01..B-45（core/window/join/table/aggregation/reliability/cep/registry/config/checkpoint）、MQ-01..15、CDC-C1/H1-H5/M1-M7/L1-L9、RT-H1-H3/M1-M7/L1-L11。

### 已修复并带回归测试（29 项 + 1 项缓解）

| 模块 | 修复项 |
|---|---|
| core/window/aggregation/runtime | B-01（会话窗口合并）、B-11（窗口尺寸校验）、B-21（floorMod 对齐）、B-39（CountTrigger 校验）、RT-M1（drain 超时死锁） |
| join | B-02（非对称窗口顺序依赖）、B-16（并发 CME） |
| table | B-03（aggregate 丢累加值）、B-17（null 分组 key） |
| reliability | B-12（clear 后过滤器失效） |
| registry | B-32（首失败即熔断，顺带修复"窗口在成功调用上填满时不裁决"）、B-04（健康上报 key 错配：事件/缓存/反注册/查询四路全失效）、B-05（metrics 采集为空不再跳过心跳）、B-26（健康 checker 注册竞态：putIfAbsent，杜绝双开泄漏线程）、B-25（订阅竞态：compute 原子 create-or-reuse，杜绝双监听器与订阅泄漏） |
| mq | MQ-02（DLQ RETRY 双写）、MQ-03（租约非原子）、MQ-05（重放前缀硬编码）、MQ-07（退避溢出）、MQ-09（claimIdleMs=0）、MQ-10（JSON 控制字符） |
| cdc | CDC-C1（真实流格式全丢）、CDC-H1/H2（重启丢水位/队列）、CDC-H4（commit 冒号截断）、CDC-H5（NPE）、CDC-M6（调度器复用）、CDC-M7（值解析按空白切分） |
| runtime redis | RT-H1（checkpoint 恢复失效）、RT-M5（快照错误被吞，缓解：WARN + 明示回退语义） |

另：稳定了一个先前就存在的 flaky 测试（`AbstractCDCConnectorTest` 后台调度器与手动 poll 竞态，改为 interval=0 的拉模式连接器）。

### 记为后续任务（未修复，原因见各条目）

- **重构级**（需专项设计，非局部修复）：MQ-01/MQ-04（DLQ pending 回收与删除策略语义）、RT-H2（checkpoint 全库 SCAN）、B-20（完成匹配的有界化）——均已在此后专项修复；B-24（KTable 物化清理）经血缘登记+代际保留+级联删除修复（见上）。
- 设计决策类：RT-H3（fire-and-purge 原子性，需两阶段提交）、B-06（配置通知重同步，涉及 API 契约）。
- 风险可控/影响良性：MQ-11（frontier 回退方向安全）、RT-M3/M6/M7（文档化语义）、B-36（文档已声明测试用途）等。
- 其余 ⏳ 条目为审计发现但本轮未逐条复现验证（范围限制），均已给出触发条件、位置与修复方向，可直接作为下轮输入。

### 低危清单核销（2026-10-07 逐条对照活代码）

上面的 CDC-L1~L9 / RT-L1~L11 两条紧凑清单已逐条对照当前代码核销并回标：6 条已修复（CDC-L1/L4/L8、RT-L4/L10 及 RT-L1 的主要危害面）、5 条部分修复（CDC-L7/L9、RT-L2/L5/L9，残留半项见各条注记）、其余仍未修。当前存活的真实队列（全部 Low）：

- CDC-L2 配置校验缺口（validate 仍只查 name）；CDC-L3 metrics 非 CAS 读改写；CDC-L5 doCommit 无 parts.length 检查；CDC-L6 sink 超时不取消在途发送。
- RT-L3 分区回退 1 只覆盖分区 0；RT-L6 listCheckpoints 同毫秒 tie 无次级排序；RT-L7 窗口 member `\u0001` 分隔符未转义；RT-L8 NumberAggregationUtils long 溢出静默；RT-L11 StateDescriptor 无 null 校验。
- 半修残留：CDC-L7 的 binlogFilename 无 volatile；CDC-L9 的 ChangeEvent 无 serialVersionUID；RT-L2 的 `topic|group` 分隔符歧义；RT-L5 的 keep=0 禁清理。

### race 检测说明

Java 工具链中没有 `go test -race` 的直接等价物。等效手段：全量测试套件（含 `@Tag("integration")` 的真实 Redis 集成测试）+ 本轮对并发敏感路径的定向并发测试（join 并发、会话窗口合并、租约原子性、熔断窗口），以及审计中对内存可见性/原子性的逐点分析（B-16/B-19/B-25/B-26/CDC-M2/MQ-08 等条目）。
