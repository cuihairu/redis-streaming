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
- **位置**：`runtime/.../internal/InMemoryWindowedStream.java`（drive 分桶逻辑）；`window/.../assigners/SessionWindow.java:31-36`
- **触发**：`env.fromCollection(events).keyBy(k).window(SessionWindow.withGap(5min)).count()`，事件间隔 1 分钟。
- **影响**：`SessionWindow.assignWindows` 对每个元素返回 `[ts, ts+gap)` 窗口，引擎按 start/end 精确分桶且无任何合并逻辑（`shouldMerge`/`TimeWindow.merge` 全仓无调用方），每个元素产生独立结果（count=1）。会话窗口功能完全失效。
- **审计置信度**：高
- **验证与修复**：core `WindowAssigner` 新增 `supportsWindowMerging()` 钩子（默认 false，含契约 javadoc：合并语义、trigger 状态丢弃限制）；`SessionWindow` 声明支持；`InMemoryWindowedStream` 在入桶前对同 key 的相交桶做单趟合并（不变量：同 key 桶两两不相交，故单趟扫描完备，代码注释含证明）。回归测试 `InMemoryWindowedStreamSessionMergeTest` 5 个用例：连续事件合并为单一会话 [0,270)=4、间隔超 gap 分裂、恰好等于 gap 不合并（半开区间）、链式桥接合并 [0,350)=5、多 key 独立合并。

### B-02 StreamJoiner 匹配条件依赖到达顺序：非对称 JoinWindow 下同一对数据是否 join 取决于先到方 [已修复]
- **位置**：`join/.../StreamJoiner.java:54,68`（配 `JoinWindow.java:74-77`）
- **触发**：`JoinWindow.afterOnly(10s)`（before=0）。右元素 R(ts=T+5) 先到、左元素 L(ts=T) 后到：`processLeft` 计算 `contains(R.ts, L.ts)` → diff=L−R=−5s，`-5 >= 0` 为假 → 不匹配；若 L 先到则 `processRight` 计算 `contains(L.ts, R.ts)` → +5s∈[0,10] → 匹配。
- **影响**：join 结果取决于到达顺序而非数据本身。两处调用点条件互为镜像，任何非对称窗口必有一侧是错的。INNER/LEFT/RIGHT/FULL_OUTER 全部静默出错。
- **审计置信度**：高
- **验证**：已用 `StreamJoinerOrderIndependenceTest` 复现（旧代码 6 个用例中 5 个失败：afterOnly/beforeOnly 双向 + of(3s,7s) 边界）。修复：`processLeft` 改为 `contains(L.ts, R.ts)`，两条路径统一为左锚定谓词 `R.ts − L.ts ∈ [−before, +after]`（与 Kafka Streams 语义一致），并补充 `JoinWindow.contains` 的锚定契约 javadoc。修复后 join 模块全部测试通过。

### B-03 KGroupedTable.aggregate 同时丢弃初始值与累加值：多行分组坍缩为最后一行 [已修复]
- **位置**：`table/.../impl/InMemoryKGroupedTable.java:45-51`；`table/.../impl/RedisKGroupedTable.java:54-56`
- **触发**：`table.groupBy(...).aggregate(() -> 0L, (k, v) -> v)` 聚合同组 5 行。
- **影响**：adder 签名 `BiFunction<K,V,VR>` 根本拿不到当前累加值；`current = initializer.get()` 计算后即被丢弃。结果= `adder(key, 最后一行的值)`，任何需要历史的聚合（count/sum/reduce）全错。
- **审计置信度**：高
- **验证与修复**：API 变更为 `aggregate(Supplier<VR> initializer, TableAggregator<K,V,VR> adder, TableAggregator<K,V,VR> subtractor)`（新增 `TableAggregator` 三参函数式接口，携带累加值）。两个实现均改为真正的 fold；修复前 InMemory 版测试断言的是"只保留最后一行"的错误行为，已改为真实 sum 断言（a=1+2=3, b=3+4=7, c=5）。

---

## 高（High）

### B-04 消费端健康状态上报完全失效：uniqueId 与 instanceId key 错配 [已修复]
- **位置**：`registry/.../impl/RedisServiceConsumer.java:101-126,290-299,512-519`；`registry/.../health/HealthCheckManager.java:58,81,96-102`
- **触发**：`enableHealthCheck=true` 后发现实例。`HealthCheckManager` 以 `getUniqueId()`（`"serviceName:instanceId"`）为 checker key 并作为 reporter 回调首参；`reportHealthStatus` 却用该值查 `discoveredInstances`（以裸 `instanceId` 为 key）。
- **影响**：健康事件（HEALTH_FAILURE/RECOVERY）永不触发、缓存永不更新；`unsubscribe` 用裸 id 反注册同样永不命中 → checker 永不 stop；`isInstanceHealthy` 恒 false。
- **审计置信度**：高
- **验证与修复**：`RedisServiceConsumer` 新增 `uniqueIdToInstanceId` 反查表：三处发现路径统一经 `cacheInstanceAndRegisterHealthCheck` 登记映射；`reportHealthStatus` 先翻译 uniqueId→裸 id 再查缓存（无映射时回退原值）；`isInstanceHealthy` 先把裸 id 翻译成 uniqueId 再查 checker；`unsubscribe` 清理改用 uniqueId（旧裸 id 调用永不命中，每实例泄漏一个运行中 checker 线程——即 B-08 的放大器）；`stop()` 清空反查表。回归测试：`ConsumerHealthKeyTranslationTest`（翻译层，旧代码 2/2 失败）+ 集成测试 `ConsumerHealthEventIntegrationTest`（真实 Redis：不可达实例 → HEALTH_FAILURE 事件 + `isInstanceHealthy("i1")`=false + unsubscribe 后 checker 数归零；旧代码收不到事件）。

### B-05 metrics 收集为空时心跳被静默跳过：实例在"正常心跳"中被过期清除 [已修复]
- **位置**：`registry/.../impl/RedisServiceProvider.java:333-357`；`registry/.../metrics/MetricsCollectionManager.java:34-73,133-148`
- **触发**：采集超时（默认 5s > 心跳间隔 3s）/采集器失败/enabledMetrics 为空 → `collectMetrics` 返回空 map → `NO_UPDATE`；默认 `enableMetadataChangeDetection=false` → 最终 `NO_UPDATE` → 不执行任何 Redis 写。
- **影响**：负载高峰（恰恰最需要心跳时）ZSet score 与 hash TTL 停止刷新，`heartbeatTimeoutSeconds` 后实例被清除、消费者掉线。仅 TRACE 级日志。
- **审计置信度**：高
- **验证与修复**：`HeartbeatStateManager` 新增 `shouldHeartbeatOnly(serviceName, instanceId)`（按心跳间隔判定）；`processInstanceHeartbeat` 对空采集结果改走该判定而非直接 `NO_UPDATE`——到期即产生 `HEARTBEAT_ONLY`，`executeUpdate` 的 Lua 路径照常刷新 TTL/score；心跳未到期则仍 NO_UPDATE（不产生多余写）。回归测试 `ProviderEmptyMetricsHeartbeatTest`（把决策行还原为旧短路后 2/2 复现失败）与 `HeartbeatStateManagerTest.testShouldHeartbeatOnlyDecidesByHeartbeatInterval`。

### B-06 配置中心监听器纯 pub/sub 无重同步：断连期间错过的通知永久丢失 ⏳
- **位置**：`config/.../impl/RedisConfigService.java:196-237,440-463`
- **触发**：订阅方 Redis 连接闪断期间发生 `publishConfig`。
- **影响**：监听器持有过期配置直至同 dataId 下次发布；无版本对账、无轮询兜底。registry 消费端事件处理同为纯响应式。
- **审计置信度**：高（语义缺失类）

### B-07 配置变更监听器每次发布收到两次通知（本地重放 + pub/sub 回环） [已修复]
- **位置**：`config/.../impl/RedisConfigService.java:424-435`
- **触发**：任何 `publishConfig`/`removeConfig`：先 `topic.publish(evt)` 又同步 `handleConfigChangeEvent(evt)`，消息再经订阅回环送达同一 JVM。
- **影响**：进程内监听器对每次变更收到两次（且来自不同线程并发）；非幂等监听器（计数、一次性 reload）行为错误。
- **审计置信度**：高
- **验证与修复**：`ConfigChangeEvent` 新增 `publisherId` 标记（旧事件无标记仍按原路径投递，跨版本兼容）；`publishConfigChangeEvent` 发布时盖上本实例 `clientId`；订阅回调对 `publisherId==自己` 的回环事件直接跳过——本 JVM 的同步投递只此一份，远端 JVM 仍各收一次。
- **回归测试**：`ConfigChangeSingleDeliveryIntegrationTest`（@Tag("integration")，真实 Redis，3 用例：发布方自身监听器恰好一次且 publish 返回时已送达、回环落地后仍一次、远端监听器恰好一次、删除监听后不再通知）。旧代码复现：git stash 还原 main 代码后跑同一测试，removal/publishing 两条以 "expected 1 but was 2" 失败——双投递坐实；新代码全绿。

### B-08 ClientHealthChecker 每实例一个非守护线程且首次检查在调用线程同步执行 [已修复]
- **位置**：`registry/.../health/ClientHealthChecker.java`；`HealthCheckManager.java`
- **触发**：健康检查开启后 discover N 个实例。
- **影响**：`subscribe()/discover()` 首次注册每实例阻塞至 connect+read 超时（默认 5s）；未 stop 的 checker（B-04 保证会发生）以非守护线程阻止 JVM 退出；线程数 O(实例数)。
- **审计置信度**：高
- **验证与修复**：三项并修——(1) 首检不再内联：`start()` 改为 `scheduleWithFixedDelay(checkHealth, 0, ...)`，首检落到执行器线程，`registerServiceInstance` 每实例不再阻塞至超时（首检完成前 `getLastHealthStatus()` 为默认 true，与注册即 UP 的注册中心语义一致）；(2) 线程转守护：自管调度器与共享池的线程工厂均 `setDaemon(true)`，泄漏的 checker 不再阻止 JVM 退出；(3) 共享池：`HealthCheckManager` 惰性建一个 `ScheduledThreadPoolExecutor`（core=max(2, cores/2)，keepalive 60s + allowCoreThreadTimeOut，空闲零线程），全部实例的检查经 `ClientHealthChecker` 包私有 6 参构造器跑在共享池上，公共 5 参构造器保留自管单线程调度器向后兼容；`stopAll()` 收口关闭共享池（此后注册会重建）。附带把 `checkHealth` 的 catch 从 Exception 放宽到 Throwable——未捕获 Error 会让 fixed-delay 调度静默死亡（无日志、永不再检），捕获后仍按"检查失败=不健康"上报。
- **回归测试**：`HealthCheckThreadingTest`（只用公共 API，可直接对旧代码编译）——旧代码 3/3 按预期失败：首检线程="Test worker"（调用线程内联）、调度线程 isDaemon=false、检查线程名含 "Test worker"（无共享池）；新代码 3/3 绿。既有 `ClientHealthCheckerTest`/`CustomClientHealthCheckerCoverageTest`（含 stop 阻塞探针等待 5s 超时路径、stop 中断路径）全绿，行为兼容。

### B-09 WindowedDeduplicator 的"元素级时间窗"实为集合级 TTL 且每次写入刷新 [已修复]
- **位置**：`reliability/.../deduplication/WindowedDeduplicator.java`
- **触发**：`windowDuration=1h` 持续有流量，元素 "A" 于 t=0 出现、一年后再次出现。
- **影响**：条目永不单独过期；整个 set 的 TTL 在每次 `markAsSeen` 被刷回 windowDuration，持续流量下 set 永不过期 → "A" 一年后仍判重；set 无界增长，与"Memory-bounded"文档相反。
- **审计置信度**：高
- **验证与修复**：数据结构从 plain SET + 整键刷新 TTL 换成 ZSET（`RScoredSortedSet`，score=元素最近出现时间）：`isDuplicate`/`checkAndMark` 按元素 score 判窗（`now - score < window`），写入时 `removeRangeByScore` 剪掉窗口外旧条目使集合有界（≈ 流量速率 × 窗口），并盖 `window+60s` 背板 TTL 只负责在流量停止后回收整键（不再是过期机制）。时钟经包私有构造器注入（`LongSupplier`，公共构造器默认 `System::currentTimeMillis`）；构造期零 Redis 访问并补齐 NPE/IAE 校验。旧版 plain SET 键在首次访问时惰性迁移（getType==SET → readAll → delete → 以迁移时刻为 last-seen 重写为 ZSET，即旧成员视作再多看一次）。剪枝下界用 0 而非 "-Infinity"（后者过 Redisson 编码不可移植）。
- **回归测试**：`WindowedDeduplicatorIntegrationTest`（只用公共 API，可直接对旧代码编译）——旧代码复现 2 项失败：`elementExpiresWhileTrafficContinues`（300ms 窗，标 "A" 后以 50ms 间隔持续泵入 "B" 共 700ms，`isDuplicate("A")` 旧=true（整键 TTL 被流量续命）/新=false ✓）、`legacyPlainSetIsMigratedToScoredLayout`（键类型旧=SET/新=ZSET ✓）；另 2 项（空闲过期、背板 TTL）新旧皆绿作守卫。单元 `WindowedDeduplicatorTest` 重写 9 例（注入时钟判窗/剪枝边界 now−window/背板 TTL/迁移/校验），`DelegateCoverageTest`、`DeduplicatorsTest` 同步迁移到 ZSET 布局。

### B-10 PatternMatcher 开启 allowEventReuse 后活动序列每事件翻倍：指数膨胀 [已修复]
- **位置**：`cep/.../PatternMatcher.java:43-55,76-92`
- **触发**：`allowEventReuse(true)`，N 个连续匹配事件。
- **影响**：序列数 2^N（每事件克隆全部活动序列再新增）→ ~30 个事件即 OOM；仅靠时间窗清理，快速流撑不到过期。
- **审计置信度**：高
- **验证与修复**：实测增长基数为 2^(N+1)−2（newSeq 加入后 extendSequences 连它一起扩展），20 个事件即 2,097,150 个活动序列。沿 B-20 惯例加保留上限：新增 `DEFAULT_MAX_ACTIVE_SEQUENCES=1000` 与 2-arg 构造器（负数 IAE；0 完全关闭扩展跟踪），超出上限按最旧优先裁剪（subList 批量删除，保留最新部分序列——最可能被后续事件扩展），在 process() 扩展后调用。时间窗清理保留；`process()` 完成输出不受影响（每匹配事件仍恰一个完成序列）。裁剪不改变"扩展序列从不产出"的既有事实——活动序列仅供计数与扩展，上限化后内存有界 O(cap×maxSequenceLength)。
- **回归测试**：`PatternMatcherActiveSequenceCapTest`——旧代码复现：临时旧 API 测试（1-arg 构造器）喂 20 事件断言 ≤1000，实测 2,097,150 失败后删除。永久测试 6 例：默认上限精确钳制在 1000、显式 cap=3 保留最新 3 个、cap=0 零跟踪但完成输出不变、负数 IAE、关闭 reuse 无部分序列、时间窗在满载状态下仍正常清空。

### B-11 窗口 assigner 接受 0/负大小：除零、死循环或静默丢数据 [已修复]
- **位置**：`window/.../assigners/TumblingWindow.java:32-35`、`SlidingWindow.java:35-47`；`aggregation/.../TumblingWindow.java:24-29`、`SlidingWindow.java:22-27,46-59`
- **触发**：`TumblingWindow.ofMillis(0)` → `%0` ArithmeticException；`SlidingWindow.ofMillis(10000, -1)` → slide 循环死循环/OOM；负 size → `end<start`、`contains()` 恒假 → 元素静默消失。
- **影响**：构造或首元素崩溃、挂死或静默丢数据，无指向错误配置的校验报错。
- **审计置信度**：高
- **验证与修复**：四个类的构造路径统一加正数校验（window 模块两个私有构造器 + aggregation 模块把 `@AllArgsConstructor` 换成显式校验构造器，杜绝绕过工厂直构）；`SlidingWindow.of` 先构造后比较 slide<=size，null 参数不再 NPE；B-39 的 `CountTrigger` 同步加校验。回归测试 `WindowAssignerValidationTest`（window）与 `WindowValidationTest`（aggregation）。原有断言旧错误行为的用例（`TumblingWindowAssignerTest.testAssignWindowsNegativeTimestamps`、`CountTriggerTest.testWithZeroCount`）改为断言新语义。

### B-12 BloomFilterDeduplicator.clear() 删除过滤器后所有后续操作失败 [已修复]
- **位置**：`reliability/.../deduplication/BloomFilterDeduplicator.java:118-121` vs `73-77`
- **触发**：`clear()` 后调用 `markAsSeen`/`isDuplicate`。
- **影响**：`clear()` 只 `delete()` 不重建（构造器有 `tryInit`）；对已删除过滤器操作抛错（"Bloom filter is not initialized"），重置后每个元素都异常。
- **审计置信度**：高
- **验证与修复**：`clear()` 在 `delete()` 后用保存的原始参数（新增 `expectedInsertions`/`falseProbability` 字段）重新 `tryInit`；`tryInit` 对已存在的 key 是 no-op，并发 clear 安全。回归测试 `clearReinitializesFilterForSubsequentOperations` 断言 delete 后 tryInit(原始参数) 且后续 markAsSeen/isDuplicate 正常。

---

## 中（Medium）

### B-13 Checkpoint 快照无类型无版本：恢复侧 LinkedHashMap/ClassCastException [已修复]
- **位置**：`checkpoint/.../DefaultCheckpoint.java:65-93`；`RedisCheckpointStorage.java:37-48`；`RedisCheckpointCoordinator.java:184-188`
- **触发**：快照放入 POJO → JSON 系 codec 存取 → `getState` 反序列化为 `LinkedHashMap`。
- **影响**：未检查强转 `(T) stateMap.get(key)`，调用点首次使用才 CCE；无 schema/version 字段，状态类变更静默破坏旧 checkpoint；`restoreFromCheckpoint` 只打日志不恢复任何后端（呈现成功实为 no-op）。
- **审计置信度**：高（设计类）
- **验证与修复**：
  - 旧代码复现（集成，真实 Redis）：POJO 与 `HashMap<Integer,String>` 经默认 bucket codec 往返后类型保真（POJO 仍是 POJO、Integer 键仍是 Integer）——审计主张的"默认 codec 下必然退化为 LinkedHashMap/CCE"**不成立**，降级为非默认 codec/自定义 storage 实现下的潜在风险；真实缺陷是 `restoreFromCheckpoint`：仅遍历快照打 debug 日志后打印 "Successfully restored"（RedisCheckpointCoordinator.java:185-194），无任何外部可观察效果——假成功由代码路径直接成立。
  - 修复一（调用点类型检查）：`Checkpoint.StateSnapshot` 新增 `getState(String, Class<T>)` 默认方法（严格校验，类型不符当场抛 `IllegalStateException`，报文含 key/实际类型/期望类型）；`StateSnapshotImpl` 覆写为 Jackson `convertValue` 兜底，把解码后的字段图（如遗留 codec 产生的 `Map`）就地转成目标 POJO。
  - 修复二（快照版本）：`Checkpoint` 新增 `getSnapshotVersion()` 默认 0（= 遗留无版本标记）；`DefaultCheckpoint` 新增 `CURRENT_SNAPSHOT_VERSION=1` 与 `snapshotVersion` 字段——字段初始化值保持 0，使版本化之前持久化的旧 JSON 反序列化后如实报告 legacy，构造器对新实例盖 1；往返经真实 Redis 验证（写 1 读 1，旧 payload 读 0）。
  - 修复三（恢复诚实化 + 能力）：`RedisCheckpointCoordinator.restoreFromCheckpoint(long, BiConsumer<String,Object>)` 新增重载——校验存在且 completed（B-14 语义）后把每个 `(key, value)` 交给调用方 sink，返回移交条数，不存在/未完成返回 -1 且零移交；接口方法改为走同一实现，日志如实说明协调器不持有后端、状态落库由调用方完成，不再输出 "Successfully restored" 假成功。
- **回归测试**：`DefaultCheckpointTest`（新实例版本=1、typed read 同型/转换/透 null/不可能转换报错、无标记实现读作 legacy 0）；`CheckpointSnapshotRoundTripIntegrationTest`（@Tag("integration")，真实 Redis：POJO+Integer 键 Map 往返保真、快照版本往返=1、sink 恢复移交 2 条且内容正确、未知/未完成 checkpoint 拒绝且零移交、遗留解码形状经 typed read 转回 POJO）。既有 `CheckpointIntegrationTest` 未改一字全绿（接口新增默认方法向后兼容）。

### B-14 未完成的 checkpoint 被持久化、被当作 latest 返回、可被恢复 [已修复]
- **位置**：`checkpoint/.../redis/RedisCheckpointCoordinator.java:74-91,168-195`；`RedisCheckpointStorage.java:50-54,91-107`
- **触发**：`triggerCheckpoint()` 落盘后、`completeCheckpoint` 前崩溃/超时；重启后 `getLatestCheckpoint()` 取到不完整者。
- **影响**：恢复只打 "Restoring from incomplete checkpoint" 继续执行；`cleanupOldCheckpoints` 按时间戳淘汰，可能删旧保新（不完整的）。
- **审计置信度**：高
- **验证与修复**：修复：`RedisCheckpointStorage.getLatestCheckpoint()` 只返回最新**已完成**的 checkpoint（按时间戳倒序找第一个 `isCompleted()`，全不完整则返回 null）；`cleanupOldCheckpoints(keepCount)` 淘汰顺序改为"先不完整、再最旧已完成"（各内 oldest-first，保留总数仍为 keepCount，全已完成时行为与原先完全一致）；`restoreFromCheckpoint` 对未完成 checkpoint 由 warn+继续恢复改为拒绝恢复（error 日志 + return）。checkpoint 按 id 仍可 `loadCheckpoint` 检查，不影响可观测性。回归：`RedisCheckpointStorageRecoveryFilterTest`（4 用例：latest 跳过不完整、全不完整→null、cleanup 先淘汰不完整者、断言不再调全库 getKeys()——旧代码 3 例失败）+ `CheckpointIncompleteRecoveryIntegrationTest`（真实 Redis：1/2 ack 后 latest 为 null、补满 ack 后可恢复；cleanup(2) 淘汰的是不完整的最新者而保留更旧的已完成者——旧代码两例全失败，stash 复现）。既有 `CheckpointIntegrationTest.testGetLatestCheckpoint` 原本对未 ack（不完整）checkpoint 断言 latest——属固化缺陷，已改为补满 ack 后断言。

### B-15 RedisCheckpointStorage.listCheckpoints 全 keyspace 扫描并反序列化整个快照 [已修复]
- **位置**：`checkpoint/.../redis/RedisCheckpointStorage.java:57-81`
- **触发**：任何 `getLatestCheckpoint()`（含 coordinator 构造器）。
- **影响**：`keys.getKeys()` 无 pattern 全库遍历；每个候选 key 完整反序列化 checkpoint（含全量状态快照）后才 `.limit(limit)`；limit=1 时 O(全部×快照大小)，共享库上启动即 OOM/卡死。
- **审计置信度**：高
- **验证与修复**：修复：keyspace 遍历改为前缀 SCAN——`keys.getKeys(KeysScanOptions.defaults().pattern(keyPrefix + "*"))`（Redisson 4.x 中旧的 getKeysByPattern(String) 已弃用且本项目 -Werror），只扫描本 storage 前缀；保留纯数字后缀过滤防误读同前缀辅助键。既有单测的 `keys.getKeys()` stub 相应改为 KeysScanOptions stub（impl 无值 equals，用 any(KeysScanOptions.class) 匹配；真实 pattern 行为由集成测试覆盖）。回归：`RedisCheckpointStorageRecoveryFilterTest.listCheckpointsScansOnlyTheStoragePrefix`（verify(never()).getKeys() + 结果正确——旧代码空扫描得 0 条失败）。

### B-16 StreamJoiner 缓冲为 ConcurrentHashMap + 裸 ArrayList：并发遍历/修改竞态 [已修复]
- **位置**：`join/.../StreamJoiner.java:22-23,44-45,50-59,122-148`
- **触发**：双线程并发 `processLeft`/`processRight`（类用 CHM 即为支持并发）。
- **影响**：遍历匹配循环 vs `add`/`cleanup().removeIf` → CME 或静默漏配/重复配；`cleanup()` 每元素 O(总缓冲) 扫描。
- **审计置信度**：高
- **验证与修复**：与 B-02 一并处理：`processLeft/processRight/clear/getLeftBufferSize/getRightBufferSize` 全部加 `synchronized`（粗粒度锁），消除遍历 vs 修改竞态与 `workers` 类 check-then-act 问题。该类定位为测试/简单场景引擎，吞吐损失可接受。每元素 O(n) 的 cleanup 扫描保留（标记为后续优化项，非正确性问题）。
- **回归测试**：`StreamJoinerConcurrencyTest`（补于后续批次）——旧代码复现：定点剥离 5 处 `synchronized` 后 4/4 失败，全部 `ConcurrentModificationException`（16 线程同 key 写入、40+40 左右流并发全交叉配对、读线程并发取缓冲 size、`clear()` 与处理并发）；修复代码上 4/4 通过，并断言精确配对数 `left×right` 与缓冲无损。

### B-17 InMemoryKGroupedTable 对 null 分组 key NPE（Redis 版容忍，行为不一致） [已修复]
- **位置**：`table/.../impl/InMemoryKGroupedTable.java:41-84`
- **触发**：`groupBy` 对某行返回 null 后 `count()/aggregate()/reduce()`。
- **影响**：CHM `merge/compute` 对 null key 抛 NPE；`RedisKGroupedTable` 显式 `continue` 跳过。同样输入内存崩、Redis 正常。
- **审计置信度**：高
- **验证与修复**：`count/aggregate/reduce` 三个操作统一跳过 null 分组 key；回归测试 `nullGroupKeyRowsAreSkippedLikeRedisImplementation` 断言两个实现行为一致。

### B-18 TopKAnalyzer 忽略 windowSize："窗口化 Top-K"实为全时段 Top-K [已修复]
- **位置**：`aggregation/.../analytics/TopKAnalyzer.java:24-31,52-70`
- **触发**：`createTopKAnalyzer(10, Duration.ofMinutes(5))` 做 5 分钟滚动热榜。
- **影响**：`windowSize` 字段从不读取；分数只增不减、只按 rank 裁剪（保留 2k）不按时间；跌出 top-2k 的条目分数永久丢失 → 窗口语义完全错误。
- **审计置信度**：高
- **验证与修复**：重构为时间桶窗口实现，公共 API 签名不变：记录落入 `windowSize/10`（下限 1ms）宽度的桶（`<prefix>:topk:<category>:b:<bucketIndex>`），每次写刷新桶 TTL（窗口 + 2 桶，Redis 自动回收过期桶）；查询合并尾随窗口覆盖的全部桶（`entryRangeReversed` 读 (value,score)，分数按项求和、按分数降序 + 项名并列裁决）。getTopK/getRank/getScore/removeItem/reset 全部改为窗口视图；2k rank 裁剪保留但作用域缩到单桶；旧布局 key 被忽略不破坏。构造器新增校验（k>0、window 正数）；包级私有时钟注入构造器供测试。旧实现测试中 8 个布局耦合用例按新语义重写（意图保留），新增跨桶合并/过期/桶粒度/TTL/参数校验用例。
- **回归测试**：`TopKAnalyzerWindowDecayIntegrationTest`（integration，仅用公共构造器，旧码可编译）——旧代码复现：record 3 次（score=3.0 可见）→ 睡 1.2s（窗口 500ms）→ 旧码 getScore 仍 3.0、getTopK 仍报该项（失败）；对照用例"窗口内聚合"旧码即通过（隔离缺陷）。新码上两用例通过。`TopKAnalyzerWindowTest`（注入时钟）：桶离开尾随窗口后贡献清零、与窗口仍重叠的桶继续计数、相邻桶分数合并、写入 TTL 精确到 now+window+2 桶、1ms 桶下限。

### B-19 BloomFilterDeduplicator.checkAndMark 非原子 contains [add：并发同 key 双双通过 ✅已修复]
- **位置**：`reliability/.../deduplication/BloomFilterDeduplicator.java:100-115`
- **触发**：两线程并发 `checkAndMark(同id)`。
- **影响**：双双返回 false（"新元素"）→ 处理两次；接口文档自称"原子"。`seenCount` 非 volatile 多线程丢失更新。`SetDeduplicator` 用单 `add()` 是对的。
- **审计置信度**：高
- **验证与修复**：修复：checkAndMark 的 contains→add 临界区与 clear() 收敛到同一 `stateLock` 监视器（进程内原子），`seenCount` 改 `AtomicLong`；类 javadoc 明确作用域——Redisson RBloomFilter 无服务端 check-and-add，跨进程首次并发 sighting 仍可能双双通过（需要跨进程精确去重请用 SetDeduplicator 的单 SADD）。回归：`BloomFilterDeduplicatorCheckAndMarkRaceTest`——mock 模拟真实布隆成员语义 + contains 内延时，24 线程 barrier 并发 checkAndMark 同一元素断言恰 1 个"新"（旧代码实测 2 个通过即失败）；另附 8×250 个不同元素并发 markAsSeen 断言计数无丢失。旧代码复现：expected <1> but was <2>。

### B-20 PatternSequenceMatcher 完整匹配永不清理：无界增长 [已修复]
- **位置**：`cep/.../PatternSequenceMatcher.java:23,62,84,187-189`
- **触发**：高频匹配模式长时间运行。
- **影响**：`completeMatches` 只增不删（清理仅作用于部分匹配），`getCompleteMatches()` 每次全量拷贝 → 长跑 OOM。
- **审计置信度**：高
- **验证与修复**：修复：新增 `maxRetainedMatches` 保留上限（默认 1000，新双参构造器指定；负数 IAE、0 表示不留历史但 process() 照常逐条交付匹配），process() 末尾 `trimCompleteMatches()` 淘汰最旧者。消费主通道仍是 process() 返回值，保留历史仅供查询。回归：`PatternSequenceMatcherRetentionTest` 5 用例（默认上限有界、显式上限保留最新 3 条按事件标记断言、上限 0 不留历史仍逐条交付、负数 IAE、单参构造器默认行为）；旧代码复现用仅含单参构造器调用的临时测试（aria：1005 条全保留，"old code grew to 1005"），修复后删除。

### B-21 负时间戳窗口对齐用 `%` 而非 floorMod：窗口错位甚至不包含元素自身 [已修复]
- **位置**：`window/.../TumblingWindow.java:33`、`SlidingWindow.java:38`；`aggregation/.../TumblingWindow.java:27`
- **触发**：`assignWindows(elem, -1)`，size=1000：`-1%1000=-1` → start=0 → 窗口 [0,1000) 不含 ts=-1；正确对齐是 [-1000,0)。
- **影响**：pre-epoch 时间戳（测试时钟、合成数据、1970 前 Instant）下结果静默错位一个窗口。
- **审计置信度**：高（算术）/中（现实影响）
- **验证与修复**：与 B-11 一并修复：window 模块对齐改 `Math.floorMod`、aggregation 模块改 `Math.floorDiv`（含 SlidingWindow.getOverlappingWindows 的 startWindow 计算）。回归用例见两个 `*ValidationTest`（断言 ts=-1 落入 [-1000,0) / [-300,700) 且所有生成窗口包含元素本身）。

### B-22 InMemoryCheckpointCoordinator 非同步映射 + 浅快照 [已修复]
- **位置**：`runtime/.../internal/InMemoryCheckpointCoordinator.java:24-58`；`InMemoryKeyedStateStore.java:36-42`
- **触发**：`registerStore` 与 `triggerCheckpoint` 并发；或快照后用户算子继续改共享可变值。
- **影响**：CME/撕裂快照；`new HashMap<>(store)` 一层浅拷贝，可变值与 live store 共享 → 事后修改污染"已完成"快照。文档自称单线程，但 API 无防护。
- **审计置信度**：高（并发时）/中（总体）
- **验证与修复**：并发部分——`registerStore/triggerCheckpoint/restoreFromCheckpoint/getCheckpoint/getLatestCheckpoint/getRegisteredStores` 统一加 `synchronized`（单一监视器），`latestCheckpoint` 的 volatile 随之不再必要；`getRegisteredStores()` 从 live view 改为返回不可变**副本**，迭代不再与注册竞态。浅快照部分经核实 `InMemoryKeyedStateStore.snapshot()` 已是两层拷贝（外层 + 每个 state 的内层 map），仅用户 value 对象按引用共享——内存引擎无序列化的固有限制，值替换不会污染已完成快照（现有 `testRestoreFromCheckpointWithMultipleStores` 与新快照隔离测试共同钉住该语义），无需改动。
- **回归测试**：`InMemoryCheckpointCoordinatorConcurrencyTest`——旧代码复现：①1600 store 并发注册 + 1800 次并发 trigger → 8 个 store 静默丢失（1592≠1600）；②读线程迭代 live view → `ConcurrentModificationException`；③另一轮复现 reader 20s 观察不到注册完成的可见性缺陷。修复代码上 3/3 通过，另含快照与后续状态变更隔离的语义测试。

### B-23 RedisKTable.join/leftJoin 错误处理自身 NPE：掩盖原始异常 [已修复]
- **位置**：`table/.../impl/RedisKTable.java:273-276,317-320`
- **触发**：join 函数抛错且对端是 InMemoryKTable（`otherTable` 为 null）。
- **影响**：catch 内 `otherTable.tableName` NPE，调用方收到裸 NPE，真实根因丢失。
- **审计置信度**：高
- **验证与修复**：两个 catch 块改为 null 安全：`otherTable != null ? otherTable.tableName : "in-memory table"`，日志保留两张表名且原始 joiner 异常作为 cause 正常包装为 `Join failed`/`Left join failed` 向上抛。
- **回归测试**：`RedisKTableJoinInMemoryPeerErrorTest`——旧代码复现：对 InMemoryKTable 对端 joiner 抛 `IllegalStateException("joiner bug")` 时，调用方收到 `Cannot read field "tableName" because "otherTable" is null` 的裸 NPE（cause 链全丢）；修复后收到 message=`Join failed`、cause=`IllegalStateException("joiner bug")` 的 RuntimeException；join/leftJoin 双路径 + 正常路径共 3 例。

### B-24 RedisKTable 每次转换物化新 Redis hash 且永不删除 ⏳
- **位置**：`table/.../impl/RedisKTable.java:155,184,213,248,294`；`RedisKGroupedTable.java:128`
- **触发**：每微批调用 `filter/mapValues/join/groupBy`。
- **影响**：`tableName + ":op:" + millis` 全量拷贝、无 TTL 无清理 → 长任务 Redis 内存无界增长、key 爆炸。
- **审计置信度**：高

### B-25 订阅 check-then-act 竞态：重复 RTopic 监听器、回调翻倍、订阅泄漏 [已修复]
- **位置**：`registry/.../RedisServiceConsumer.java:244-257`；`config/.../RedisConfigService.java:204-223`
- **触发**：两线程并发 `subscribe(同服务)` / `addListener(同 dataId)`。
- **影响**：双活监听器 → 每条消息回调两次；`unsubscribe` 只清理 map 内那个 RTopic，另一个的 Redis 订阅与 handler 永久泄漏。
- **审计置信度**：高
- **验证与修复**：两处订阅守卫从 `containsKey`+`put` 改为 `ConcurrentHashMap.compute`（per-key 原子的 create-or-reuse），并发订阅只会注册一个 RTopic 监听器，清理路径不变。回归测试 `ConcurrentSubscribeRaceTest`（registry，12 线程栅栏并发 subscribe，断言 addListener/removeAllListeners 恰一次；旧代码复现失败）与 `ConfigServiceConcurrentAddListenerRaceTest`（config 同型，旧代码复现失败）。

### B-26 HealthCheckManager 注册 check-then-act 竞态：泄漏运行中的 checker 线程 [已修复]
- **位置**：`registry/.../health/HealthCheckManager.java:57-90`
- **触发**：并发 discover 同一实例。
- **影响**：双开 checker，被覆盖者线程永续运行、重复探测；反注册只停其一。
- **审计置信度**：高
- **验证与修复**：`registerServiceInstance` 的权威守卫改为 `putIfAbsent`（原 containsKey 仅作快速路径）——落败方不再 put+start，避免被覆盖的 checker 线程永续探测。回归测试 `HealthCheckManagerRegistrationRaceTest`（16 线程栅栏并发注册，断言恰 1 个 checker、恰 1 次初始探测、unregister 后归零；旧代码复现失败）。

### B-27 版本生成器跨线程可生成重复版本串 [已修复]
- **位置**：`config/.../impl/RedisConfigService.java:366-378`
- **触发**：同毫秒并发 `generateVersion()`，与 `SEQ.set(0)` 交错。
- **影响**：两个不同发布携带相同 version；按 version 去重/排序的消费者丢事件或乱序。
- **审计置信度**：中
- **验证与修复**：根因比原描述多两层：①else 分支迟到的 `SEQ.set(0)` 落在两个 if 分支调用之间 → 二者拿到相同序号；②LAST_TS/SEQ 是 **static**（跨实例共享），实例级锁无法防护多实例；③时钟滞后（`now < last`，跨核 currentTimeMillis 偏移的真实形态）走 else 返回过去毫秒的 `-0` → 与历史版本重复。修复：`generateVersion()` 改为 `static synchronized`（类监视器覆盖所有实例），版本基于高水位发放——`now > last` 才开新毫秒序列，否则继续最新毫秒的序号（滞后时钟不重置）。9999 封顶回绕为既有行为未变（1ms 万次发布的理论边界，非本次并发缺陷）。
- **回归测试**：`ConfigVersionGeneratorUniquenessTest`——旧代码复现：①16 线程×4000 次并发生成 → **2354 个重复版本串**；②反射注入 LAST_TS 高水位超前 50s（模拟时钟滞后）→ 同毫秒两次调用返回同一串 `ts-0`（确定性复现）；③顺序调用不受影响（正确通过，证伪"污染式"通过）。修复代码上 3/3 通过。测试自行恢复静态状态，不污染同 JVM 其他用例。

### B-28 historySize=0 语义反转：无界保留历史（LTRIM 0 -1） [已修复]
- **位置**：`config/.../ConfigServiceConfig.java:29-31`；`RedisConfigService.java:85,413-414`
- **触发**：`setHistorySize(0)`。
- **影响**：`LTRIM hist 0 maxhist-1` = `LTRIM 0 -1` 全保留，与"不留历史"意图相反；每发布一条历史无界增长。
- **审计置信度**：高
- **验证与修复**：修复：发布/删除两条 Lua 脚本的历史写入条件改为 `oldc and maxhist>0`（historySize=0 完全跳过历史记录而非 LTRIM 到 keep-all）；Java 回退路径 `saveConfigHistory` 对 `maxHistorySize<=0` 直接返回。回归：`ConfigHistorySizeZeroFallbackTest`（mock RList，historySize=0 断言从不 add/trim——旧代码实测 NeverWantedButInvoked；historySize=1 仍正常 trim(0,0)）+ `ConfigHistorySizeZeroIntegrationTest`（真实 Redis：historySize=0 发布两次+删除后历史键恒为 0——旧代码实测 expected <0> but was <1>；historySize=1 发布 3 次恰保留 1 条）。

### B-29 Provider 清理在空集 check-then-act 移除服务索引：孤儿心跳 ZSet 且永不再清理 [已修复]
- **位置**：`registry/.../impl/RedisServiceProvider.java:536-542`
- **触发**：清理批次清空某服务 ZSet 后、`SREM` 前，新实例恰好注册。
- **影响**：服务被移出索引 → `cleanupExpiredInstances` 不再遍历它；无 TTL 的心跳 ZSet 永久孤儿；getAllServices 与实例列表不一致。
- **审计置信度**：高
- **验证与修复**：修复：`cleanupExpiredInstancesForService` 的空集判断与 SREM 合并为单条原子 Lua（`ZCARD==0 则 SREM 服务索引`，经 RScript 直发），竞态窗口不复存在；同时把该原子步骤从 `if (!result.isEmpty())` 内移到每服务必经处——原位置只有"本批次恰好清掉了实例"才校验索引，早已为空的残留（前次清理被中断、或 B-29 竞态遗留）永不被修复。回归：`ProviderServiceIndexAtomicCleanupTest`（mock RScript：断言 eval 携带 ZCARD/SREM 原子脚本及 heartbeatKey+servicesIndexKey——旧代码零交互即失败）+ `ProviderServiceIndexCleanupIntegrationTest`（@Tag("integration") 真实 Redis：空 ZSet 服务被移出索引、有活跃心跳的服务保留且心跳不被触碰；旧代码因残留位置缺陷实测 expected false but was true 失败）。

### B-30 RedisNamingService 构造子 Provider/Consumer 时静默丢弃健康检查等配置 [已修复]
- **位置**：`registry/.../impl/RedisNamingService.java:43-53`
- **触发**：`namingServiceConfig.setEnableHealthCheck(true)` 等设置后经 namingService 创建。
- **影响**：healthCheck* 与 admin 开关被忽略（只拷贝 keyPrefix 两项）；`getConfig()` 仍返回用户配置 → 错配不可见。
- **审计置信度**：高
- **验证与修复**：修复：构造子创建角色配置时将 `enableHealthCheck`/`healthCheckInterval`/`healthCheckTimeUnit`/`healthCheckTimeout`/`enableAdminService` 五项全部拷贝到 `ServiceConsumerConfig`（Provider 侧无可对应的 Naming 级字段，keyPrefix 两项照旧）。回归：`RedisNamingServiceConfigPropagationTest` 3 用例（自定义五项反射断言到达 consumer 配置——旧代码实测 enableHealthCheck 断言失败；默认值传播；keyPrefix 双角色照常传播）。

### B-31 healthCheckTimeout 零/负值：构造抛 IAE 或 connect 无限阻塞 [已修复]
- **位置**：`registry/.../RedisServiceConsumer.java:74-77`；`HttpHealthChecker.java:30-37,69`；`TcpHealthChecker.java:33-35`
- **触发**：`setHealthCheckTimeout(0)` 无校验。
- **影响**：`connectTimeout(Duration.ofMillis(0))` IAE → 构造失败；或 `socket.connect(addr, 0)` = 无限超时 → 该实例健康检查线程永久冻结。
- **审计置信度**：高
- **验证与修复**：修复：非正超时统一回退 5000ms 默认值——`ServiceConsumerConfig`/`NamingServiceConfig` 的 setter 夹紧（前者补显式 setter 覆盖 Lombok 生成）；`HttpHealthChecker`（connect+read 双超时）、`TcpHealthChecker`、`WebSocketHealthChecker` 构造器各自夹紧（直连构造同样安全）。回归：`HealthCheckerTimeoutNormalizationTest`（0/负→5000，正值保留，三个 checker 反射断言——旧代码全数失败）+ `ConsumerZeroHealthCheckTimeoutStartTest`（healthCheckTimeout=0 时 consumer 可构造并 start——旧代码 HttpClient IAE 构造即炸；两配置类 setter 夹紧断言）。

### B-32 CircuitBreaker 窗口未满即计算失败率：首个失败即开路 [已修复]
- **位置**：`registry/.../client/CircuitBreaker.java:62-78`
- **触发**：默认 `new CircuitBreaker(20, 0.5, ...)`：首调用失败 → 1/1=1.0 ≥ 0.5 → 立即 toOpen。
- **影响**：瞬时错误即隔离实例整个 openDuration。
- **审计置信度**：高
- **验证与修复**：`slideWindow` 对未满窗口返回 0（无裁决），失败率只在窗口填满（`calls >= windowSize`）时评估一次并复位——即 resilience4j `minimumNumberOfCalls` 语义；threshold=0 的"最敏感"配置行为不变。回归测试 `singleFailureDoesNotOpenBreakerOnUnfilledWindow`；原断言"首失败即 OPEN"的两个用例（registry 包 `testStateTransitions`、client 包 `testGetState`）改为填满窗口后断言。

### B-33 注册时未记录 metadata hash：开启元数据检测后首次心跳必发虚假 UPDATED 事件 [已修复]
- **位置**：`registry/.../RedisServiceProvider.java:161-165`；`heartbeat/HeartbeatStateManager.java:185-196`
- **触发**：`enableMetadataChangeDetection=true` 注册。
- **影响**：`markMetadataUpdateCompleted` 用 `get` 而条目尚未 `computeIfAbsent` 创建 → no-op；首次心跳 0≠hash 误判 METADATA_UPDATE → 全体订阅者无谓 re-discover。
- **审计置信度**：高
- **验证与修复**：修复：`markMetadataUpdateCompleted` 的 `instanceStates.get` 改为 `computeIfAbsent`——注册发生在任何决策之前，条目必然不存在，get 是静默 no-op，注册时的基线 hash 从未落盘；metrics/heartbeat-only 两个 mark 保持 `get` 不变（它们只会在决策创建条目后被调用，未知实例标记仍应 no-op）。回归：`HeartbeatStateManagerMetadataRegistrationTest`（注册后首次心跳元数据未变断言 NO_UPDATE——旧代码实测 METADATA_UPDATE；元数据真变仍检出 METADATA_UPDATE，interval 置 0 隔离限流窗口）；既有 `HeartbeatStateManagerCoverageTest.stateInfoAndRemovalHelpers` 原断言"未知实例 metadata 标记为 no-op"属固化缺陷，已按新语义改为断言条目被创建且可 remove。

---

## 低（Low）

### B-34 In-memory 限流器 per-key 状态永不淘汰（无界 map）✅已修复
- **位置**：`reliability/.../ratelimit/InMemorySlidingWindowRateLimiter.java`（Token/Leaky 同型已同批修复）
- **影响**：高基数 key（IP/用户）churn 下限流器自身成内存泄漏。算法本身同步正确。
- **验证与修复**：三个 in-memory 限流器（滑窗/令牌桶/漏桶）的 per-key map 增加写路径惰性清扫：状态变为"语义等价于不存在"（滑窗 deque 全过期 / 令牌桶按已流逝时间回满 / 漏桶按已流逝时间漏空）即从 map 摘除——淘汰对限流判定完全透明，不改变任何 allow/deny 结果。清扫按规模阈值（默认 256，包私有构造器可调）+ 最小间隔（max(1s, 半过期周期)）CAS 门控，稳态调用零额外开销、无后台线程、无生命周期负担；活跃 key 永不被淘汰。新增 `trackedKeyCount()` 供监控。坑位记录：门控时间戳哨兵不能用 `Long.MIN_VALUE`（`now-哨兵` 溢出为负使清扫永不触发，测试立即暴露，改 0）。
- **回归测试**：`InMemoryRateLimiterKeyEvictionTest`（公共构造器 + 反射读私有 map 字段，字段名新旧一致，可直接对旧代码编译）——旧代码 3/3 泄漏断言按预期失败（"expected 1 but was 301"：300 个过期 key + 1 个新 key 全部滞留），新代码 6/6 绿（3 个淘汰 + 3 个活跃 key 不误删）；既有行为测试全绿证明淘汰零语义漂移；`sweepThreshold` 负数校验入 `InMemoryRateLimiterCtorValidationTest`。

### B-35 DeadLetterQueue maxSize 未校验 + clear 与 add 竞态 ⏳
- **位置**：`reliability/.../DeadLetterQueue.java:34-37,47-69,130-134`
- **影响**：`maxSize<=0` → add 恒 false 全静默丢弃；`clear()` 两步非原子，计数可漂移，容量永久缩水。

### B-36 外连接立即发 unmatched，对端稍后到达又发 match：同元素双发 ⏳
- **位置**：`join/.../StreamJoiner.java:62-65,100-103`
- **影响**：LEFT/FULL_OUTER 下游对同一左元素先收 join(L,null) 后收 join(L,R)（无 watermark barrier/retraction；类文档已声明"测试与简单场景"，低）。

### B-37 WindowAggregator 以窗口类简名为 key：同类不同参数窗口互相截断 ⏳
- **位置**：`aggregation/.../WindowAggregator.java:159-163`
- **影响**：`TumblingWindow`(1min) 与 (1hour) 同 key 使用时 key 无 size 维度，`removeRangeByScore` 用各自窗口起点互相删数据。

### B-38 RedisListState.update 先清后写非原子：中途失败状态全丢 ⏳
- **位置**：`state/.../redis/RedisListState.java:42-48`
- **影响**：clear 后 add 中途连接断 → 旧状态已毁新状态未写全，静默丢失。

### B-39 CountTrigger 接受 maxCount<=0：每元素即触发 [已修复]
- **位置**：`window/.../triggers/CountTrigger.java:15-31`
- **影响**：静默错配，无校验报错。
- **验证与修复**：构造器加正数校验抛 IAE（与 B-11 同批）；`CountTriggerTest.testWithZeroCount` 原断言"maxCount=0 每元素触发"的旧缺陷行为，改为断言抛 IAE。

### B-40 TopKAnalyzer 边界裁剪对同分条目非确定 ⏳
- **位置**：`aggregation/.../TopKAnalyzer.java:59-64`
- **影响**：`removeRangeByRank` 同分按字典序裁剪，边界成员随机性。

### B-41 PVCounter 混用事件时间与墙钟保留：迟到事件即到即删、未来事件永生 ⏳
- **位置**：`aggregation/.../analytics/PVCounter.java:74-83`
- **影响**：`add(score=ts)` 后立即 `removeRangeByScore(0, now-window)` → 旧时间戳事件静默不计数。

### B-42 RedisClientMetricsReporter 非原子读改写共享 metrics JSON 且全吞错误 ⏳
- **位置**：`registry/.../client/metrics/RedisClientMetricsReporter.java:59-74`
- **影响**：并发下 `clientInflight` 丢失更新（不归零，扭曲 maxInflight 均衡）。

### B-43 collectWithTimeout 超时任务不取消，泄漏到公共 ForkJoinPool ✅已修复
- **位置**：`registry/.../metrics/MetricsCollectionManager.java`
- **影响**：公共池线程堆积，与 B-05 叠加。
- **验证与修复**：双重修复——(1) 采集调用改跑专用守护线程池（`newCachedThreadPool`，线程名 `metrics-collector-N`，空闲 60s 自灭，无需显式生命周期），挂死的采集器不再占用公共 ForkJoinPool 线程（common pool 容量 = cores−1，几个挂死探针即可饿死全 JVM 的并行流/异步任务）；(2) `future.get` 超时路径补 `future.cancel(true)` 中断采集器，任务不再滞留。超时语义不变：TimeoutException 仍由调用方按 WARN 吞掉。测试踩坑记录：`MetricsConfig.getEnabledMetrics()` 返回不可变 `Set.of(...)`，须用 `setEnabledMetrics` 整体替换。
- **回归测试**：`MetricsCollectionTimeoutTest`（只用公共 API + 线程栈扫描，可对旧代码编译）——旧代码失败理由精确匹配："a timed-out collector must not linger on the common ForkJoinPool (B-43) ==> expected: <null> but was: <Unsafe.park 栈含 collectMetric 帧>"（超时探针卡死在 common-pool worker 上）；新代码全绿：采集在专用池执行、超时后中断、公共池无残留 collectMetric 帧。既有 MetricsCollectionManager 覆盖测试全绿。

### B-44 publishConfig 降级路径非原子且换版本号重写 ⏳
- **位置**：`config/.../impl/RedisConfigService.java:101-143`
- **影响**：Lua 可能已生效又走 5+ 步 Java 回退（新 version 双写双事件）；仅 warn 标记降级。

### B-45 discoveredInstances 缓存永不失效且跨服务按裸 instanceId 键控 ⏳
- **位置**：`registry/.../RedisServiceConsumer.java:52,185,292-299,381-408`
- **影响**：实例移除/过期后仍被健康检查、计数错；不同服务同 instanceId（默认 hostname）互相覆盖。

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

### MQ-01 DLQ 消费者从不回收 pending 条目：handler 失败即永久滞留 PEL ⏳
- **位置**：`mq/.../dlq/RedisDeadLetterConsumer.java:89-188`（readGroup 用 `neverDelivered()`，catch 只 log @179-181，RETRY 失败路径 @163-172 不 ack）
- **触发**：`DeadLetterHandler.handle` 抛异常，或 RETRY 重放失败（ok=false）。两种情况都把 id 留在消费者组 PEL。
- **影响**：静默丢消息——DLQ 条目永远不会被重读（`neverDelivered()` 只读从未投递的），全类无 `listPending`/`claim`（主消费者 `RedisMessageConsumer.processPendingMessages` 有，此处没有），条目永久 pending。
- **审计置信度**：高（本条与 MQ-04、MQ-01 涉及 DLQ 消费循环重构，待专项处理）

### MQ-02 DlqConsumerAdapter 的 RETRY 重放两次 XADD：业务主题收到重复消息 [已修复]
- **位置**：`mq/.../impl/DlqConsumerAdapter.java:80-97`（toResult 的 RETRY 分支自己 XADD @94）与 `:26-46`（replay lambda XADD @38）；`RedisDeadLetterConsumer.java:144-172`（case RETRY 调 `replayHandler.publish`）
- **触发**：被 `DlqConsumerAdapter` 包装的 handler 对 DLQ 条目返回 `RETRY`。
- **影响**：业务消费者收到并处理两次（重复副作用/重复计数）。
- **审计置信度**：高
- **验证与修复**：删除 `DlqConsumerAdapter.toResult` RETRY 分支自身的 XADD，重放统一由 delegate 的 replayHandler 单次发布（adapter 构造时始终注入非 null replay）。`DlqConsumerAdapterTest` 两个断言旧双重写入的用例改为断言 `toResult` 不再触碰流（`verifyNoInteractions`）。

### MQ-03 LeaseManager 获取租约非原子（SET+EXPIRE）、释放非原子（GET+DELETE）：永久卡死与所有权窃取 [已修复]
- **位置**：`mq/.../lease/LeaseManager.java:19-37`（`setIfAbsent` 后 `expire`）、`:64-74`（`releaseIfOwner` GET 后 DELETE）
- **触发**：(a) `setIfAbsent(ownerId)` 与 `expire` 之间崩溃/断连 → key 无 TTL 永存；(b) release 中 worker A 读到 cur=="A" 后 key 过期、B `tryAcquire` 成功写入 B，随后 A 的 `delete()` 删掉 B 的新租约。
- **影响**：(a) 该 topic/group/partition 永远无法再获租约，消费永久停摆（需人工 DEL）；(b) 两个消费者同时认为自己持有分区 → 并发重复消费。
- **修复方向**：`setIfAbsent(value, Duration)` 原子获取；release 用 Lua compare-and-delete。
- **审计置信度**：高
- **验证与修复**：`tryAcquire` 改为单次 `setIfAbsent(value, Duration)`（原子 SET NX EX）；`renewIfOwner` 改为 Lua compare-and-pexpire；`releaseIfOwner` 改为 Lua compare-and-delete（Redisson `RScript.ReturnType.LONG`）。`LeaseManagerTest` 重写为验证原子调用形态与脚本内容。

### MQ-04 all-groups-ack 删除策略把"活跃组"等同于"有租约的组"：停机组的未消费消息被删 ⏳
- **位置**：`mq/.../broker/impl/DefaultBroker.java:137-171`（计数 @150-161，删除 @162-166）
- **触发**：`ackDeletePolicy="all-groups-ack"`，两消费组共用分区；B 组停机（租约 key 过期）时 A 组 ack。`active` 只算 A → `ackset.size()>=active` → `stream.remove(...)`。
- **影响**：B 组数据丢失：条目在 B 读到之前被 XDEL；B 重启后消息已消失。
- **审计置信度**：高

### MQ-05 DLQ 重放路径硬编码 `stream:topic` 前缀，忽略配置前缀：重放消息石沉大海且 DLQ 条目被 ack [已修复]
- **位置**：`mq/.../dlq/RedisDeadLetterService.java:155`；`mq/.../dlq/RedisDeadLetterConsumer.java:153`
- **触发**：`MqOptions.streamKeyPrefix != "stream:topic"` 时走 RETRY fallback 或 `RedisDeadLetterService.replay` 无 ReplayHandler 的路径。
- **影响**：消费路径丢数据：`ok=true`（写到了错误的 key）→ `stream.ack` 删掉 DLQ 条目，而重放消息写进了无人消费的流。
- **修复方向**：统一走 `StreamKeys.partitionStream(...)`。
- **审计置信度**：高
- **验证与修复**：`RedisDeadLetterConsumer:153` 与 `RedisDeadLetterService:155` 的硬编码前缀均替换为 `StreamKeys.partitionStream(topic, pid)`；`DlqConsumerAdapter` 构造时同时配置 `StreamKeys.configure(controlPrefix, streamPrefix)`（此前只配置了 DlqKeys，StreamKeys 仍是默认前缀）。

### 中（Medium）

### MQ-06 毒消息在 pending 扫描器中无限循环：非 payload 缺失的解析错误被重抛，永远不进 DLQ ⏳
- **位置**：`mq/.../impl/StreamEntryCodec.java:107`（`Instant.parse`）；`RedisMessageConsumer.java:484-491`（非 payload-missing 重抛）、`360-392`（扫描器 claim，catch @390 只 log）
- **触发**：任何 `timestamp` 字段非 ISO-8601 的流条目（外部生产者/手工修复/损坏写入）。`isPayloadMissing` 只匹配 "Payload not found"/"Failed to load payload"。
- **影响**：条目留在 PEL；每个 `pendingScanIntervalSec` 都被 claim → parse 抛 → log → 继续，无退避、无 DLQ、无最大投递截断；日志洪水 + 永久卡死条目。
- **审计置信度**：高

### MQ-07 指数退避移位溢出变负数：重试风暴零延迟轰炸 Redis [已修复]
- **位置**：`mq/.../retry/ExponentialBackoffRetryPolicy.java:25`（`baseMs * (1L << (attempt-1))`）；消费点 `RedisMessageConsumer.java:654-656,670`
- **触发**：`maxRetries >= ~54`（生产者可设，`Message.maxRetries` 从流数据解析）。attempt 54+ 时乘积超 Long.MAX；attempt 64 时 `1L<<63` 为负。
- **影响**：`delayMs` 为负 → `Math.min(neg, max)` 为负 → 立即重入队：每次失败零退避地 read/XADD/XACK 循环轰炸 Redis。
- **修复方向**：对 `v<=0` 先钳到 maxBackoffMs（饱和处理）。
- **审计置信度**：高（算术确定性成立）
- **验证与修复**：`nextBackoffMs` 改为饱和计算：shift 钳到 ≤62，乘法前先判 `baseMs > maxBackoffMs / factor` 则直接返回 maxBackoffMs（永不溢出、永不为负）。`ExponentialBackoffRetryPolicyTest` 新增 5 用例（正常增长、封顶、attempt 1..200 全程非负且不超上限、大 base 饱和、base=0 立即重试）。

### MQ-08 in-flight 信号量幽灵释放：背压上限被永久抬高 ⏳
- **位置**：`mq/.../impl/RedisMessageConsumer.java:836-860`（acquire 循环在 running/closed 翻转时未获取即返回）、`:862-870`（release 无条件调用）、调用点 `:505/527`、`:378/387`
- **触发**：worker 阻塞在 `inFlightLimiter.acquire()` 时调用 `stop()/close()`：循环条件变假、方法未获取即返回；`finally` 仍 `releaseInFlightPermit()`。
- **影响**：`Semaphore.release()` 无配对 acquire → 可用许可超过配置最大值，背压上限被静默永久削弱（stop/start 循环的实例上会累积）。
- **审计置信度**：高

### MQ-09 `claimIdleMs(0)` 被接受：pending 扫描器立刻偷走正在处理的消息 [已修复]
- **位置**：`mq/.../config/MqOptions.java:89`（钳到 `>=0` 而非 `>=1`）；使用点 `RedisMessageConsumer.java:360-363`
- **触发**：`MqOptions.builder().claimIdleMs(0)`（负值也会被钳成 0）。任何 pending 超过 0ms 的条目——即组内任何正在处理的消息——被 claim 并发重处理。
- **影响**：保证重复/并行处理在途消息 + ACK 风暴（首个 handler 的 ACK 与重处理者的重入队竞争），慢 handler 场景等效 at-most-once。
- **修复方向**：builder 钳到 `Math.max(1, v)`。
- **审计置信度**：高（机制确定，需非默认配置）
- **验证与修复**：builder 改为 `Math.max(1, v)`；`MqOptionsTest` 原"0 被允许"用例（断言的就是缺陷行为）改为断言钳到 1。

### MQ-10 JdbcBrokerPersistence 手写 JSON 不转义控制字符：headers 列损坏 [已修复]
- **位置**：`mq/.../broker/jdbc/JdbcBrokerPersistence.java:60-82`
- **触发**：header key/value 含 `\n`、`\t`、`\r` 等（异常详情 header 常含换行）。
- **影响**：写出的 headers JSON 非法（RFC 8259 禁止裸换行）；消费方解析失败或静默丢 headers。
- **审计置信度**：高
- **验证与修复**：`escapeJson` 重写为逐字符转义（`\n \r \t \b \f` 命名转义、其余 <0x20 用 `\u00XX`）；`JdbcBrokerPersistenceJsonEscapingTest` 新增控制字符用例：断言输出无裸控制字符且 Jackson 解析还原原值。

### 低（Low）

### MQ-11 commit frontier 更新是非原子 read-modify-write：并发 ACK 可使 frontier 回退 ⏳
- **位置**：`mq/.../impl/RedisMessageConsumer.java:805-816`
- **触发**：两个 worker/扫描线程并发 ack 同组同分区不同消息；都读到同一 `prev`，较小 id 后写。
- **影响**：frontier 回退；`StreamRetentionHousekeeper`（按最小 frontier trim）少 trim（安全方向），lag 指标不准。应改 Lua HSET-with-compare。
- **审计置信度**：高（竞态真实，影响良性方向）

### MQ-12 管理路径全库 SCAN：`getKeys()` 不带 pattern ⏳
- **位置**：`mq/.../dlq/RedisDeadLetterAdmin.java:33`（pattern @28 已算出但没用）；`mq/.../admin/impl/RedisMessageQueueAdmin.java:464`
- **触发**：大共享 Redis 上 `listTopics()`（或 pc<=1 主题的 `deleteConsumerGroup`）。
- **影响**：阻塞式全库 SCAN，管理路径延迟/负载尖峰。
- **审计置信度**：高

### MQ-13 保留量/硬上限回退路径把无界区间整体载入内存 ⏳
- **位置**：`mq/.../broker/impl/RedisBrokerPersistence.java:118-125`（`range(batch, MIN, MAX)` 只为拿 id）；`RedisMessageQueueAdmin.java:396`（`trimQueueByAge` 用 `Integer.MAX_VALUE`）
- **触发**：积压远超 `retentionMaxLenPerPartition`（缩容配置）或对大流调 `trimQueueByAge`。
- **影响**：整流（含 value）反序列化进堆；维护调用期间 OOM 风险。只需要 id，却拿了全量 map。
- **审计置信度**：高

### MQ-14 null payload 经过 retry 桶往返后变成空字符串 ⏳
- **位置**：`mq/.../impl/RedisMessageConsumer.java:909`（Lua `HGET ... or ''`）、`:913`（XADD 无条件带 payload 字段）、`:715-719`（重试入队跳过 null payload 的 put）
- **触发**：null payload 消息失败并走 scheduled-retry 路径。
- **影响**：payload 类型跨重试改变（null → ""），按 null 分支的 handler 首次重试后行为改变。
- **审计置信度**：高

### MQ-15 rebalance 与租约续期任务可在多线程调度器上并发：check-then-act 产生重复 worker ⏳
- **位置**：`mq/.../impl/RedisMessageConsumer.java:955-978`（rebalance）、`:994-1002`（renew 移除）、`:67-68`（两个 `newScheduledThreadPool(schedulerThreads)`，默认 2）
- **触发**：`schedulerThreads > 1` 时 rebalance 与 renewLeases 交错：renew 移除丢租约 worker 的同时 rebalance 看到 `containsKey==false` 再启一个同分区 worker。
- **影响**：两个 worker 线程并发读同组同分区（消息仍按 consumer 名单次投递，无重复消费，但读交错、双重 `releaseIfOwner`、worker 数指标超 `maxLeased`）。`workers.size() >= maxLeased` 同为 check-then-act。
- **审计置信度**：中（窗口窄，后果有限）

### mq 审计确认无问题项

近期修复均成立：XADD 前 `data.values().removeIf(isNull)`；重试路径 String payload 直通（无二次编码）；enqueue-before-ACK 顺序正确；`moveDueRetries` 的 ZRANGEBYSCORE+ZREM+DEL 在同一 Lua 内原子。Redisson `StringCodec` 对非 String 值做 JSON 编码，headers Map 在 `StreamEntryCodec` 的往返可靠（`StreamEntryCodecRoundTripTest` 覆盖）。DEFER_ACK 的 no-ack 行为是文档化的运行时协调语义（`MqHeaders.java:30-36`，`RuntimeDeferAckAckAllIntegrationTest` 覆盖），不计为 bug。

---

## cdc 模块审计结果（CDC-C1、CDC-H1~H5、CDC-M1~M7、CDC-L1~L9）

### 严重（Critical）

### CDC-C1 PostgreSQL 解析器对真实 test_decoding 流静默丢弃所有变更事件（格式不匹配） [已修复]
- **位置**：`cdc/.../impl/PostgreSQLLogicalReplicationCDCConnector.java:245-254`
- **触发**：任何真实 PostgreSQL `test_decoding` 流。test_decoding 每个变更输出为**单行** `table public.users: INSERT: id[integer]:1 ...`；循环先匹配 table 模式就 `continue`，同行的 INSERT/UPDATE/DELETE 匹配器永远看不到。
- **影响**：完全静默丢数据：连接器"启动成功"、健康状态 HEALTHY、零事件产出。单元测试只过了是因为喂的是合成格式（table 行与 INSERT 行分开两行，`PostgreSQLLogicalReplicationCDCConnectorParsingTest.java:32-37`），掩盖了 bug。
- **审计置信度**：高
- **验证与修复**：删除 table 匹配后的无条件 `continue`，让操作匹配器继续看同一行的剩余部分（真实单行格式命中；合成的纯 table 行无操作符则自然落空）。新增回归测试 `parseLogicalMessageHandlesRealSingleLineTestDecodingFormat`（三种操作各一行单行格式，断言 3 个事件与表名/前后镜像）。

### 高（High）

### CDC-H1 MySQL 连接器重启丢弃已保存水位并清空未交付队列（3b262e7 只修了轮询连接器） [已修复]
- **位置**：`cdc/.../impl/MySQLBinlogCDCConnector.java:52-56,69-72,94`
- **触发**：同一连接器实例 `stop()` 后 `start()`（或任何重跑 `doStart` 的重连路径）。
- **影响**：`doStart` 从**配置**重读 binlog 文件名/位置，覆盖 `handleRotateEvent`/`updateCurrentPosition` 推进的实时水位。未配置文件名时（常见）从服务器当前位重启 → 停机窗口内事件全部丢失；配置了固定位 → 全量重放 → 重复。且 `doStop` 调 `eventQueue.clear()`（:94），已捕获未交付事件被丢——正是 3b262e7 为轮询连接器修掉的同类问题。
- **审计置信度**：高
- **验证与修复**：`doStart` 只在字段为 null（首次启动）时才从配置读文件名、只在位置为 0 时才从配置读位置，重启保留实时水位；`doStop` 不再清空 eventQueue（注释说明语义）。跨进程重启仍无持久化（与轮询连接器相同的既有边界，见审计备注）。

### CDC-H2 PostgreSQL 连接器 doStop 清空未交付队列；内存恢复跳过丢失事件 [已修复]
- **位置**：`cdc/.../impl/PostgreSQLLogicalReplicationCDCConnector.java:103,205-207`
- **触发**：事件已解析进 `eventQueue` 但消费方未拉取时 stop/start。
- **影响**：重启后 `startReplicationStream()` 从 `lastReceivedLSN`（最后**收到**而非最后**交付**的 LSN）恢复，被清空的未交付事件不再重发 → 永久丢失。3b262e7 的"重启保留水位与未交付队列"只在 `DatabasePollingCDCConnector` 实现。
- **审计置信度**：高
- **验证与修复**：`doStop` 不再清空 eventQueue；重启先排空保留的队列再从 lastReceivedLSN 续流，未交付事件不再丢失。

### CDC-H3 断连后无重连（MySQL 与 PostgreSQL）——静默永久停摆 ⏳
- **位置**：`MySQLBinlogCDCConnector.java:59-78`（一次性 `connect()`，无 LifecycleListener）；`PostgreSQLLogicalReplicationCDCConnector.java:213-233`（catch SQLException 后继续轮询死流）
- **触发**：网络中断、MySQL 重启、PG 故障切换。
- **影响**：`mysql-binlog-connector-java` 0.29.2 不自动重连；断连不可检测。`poll()` 持续返回空列表、health 保持 HEALTHY。PG 侧 LSN 反馈停止 → 服务端 WAL 在 slot 中无限堆积。
- **审计置信度**：高

### CDC-H4 轮询连接器 commit() 在第一个冒号处截断时间戳水位 [已修复]
- **位置**：`cdc/.../impl/DatabasePollingCDCConnector.java:124-134`（doCommit）、`136-147`（doResetToPosition），对照 `:342`、`:320`
- **触发**：按文档 API 流程 `connector.commit(event.getPosition())`，且轮询列是 TIMESTAMP/DATETIME（**默认列**就是 `updated_at`）。位置格式为 `table + ":" + Timestamp.toString()` → `"orders:2024-01-01 10:15:30.0"`。
- **影响**：`position.split(":")` 取 `parts[1]` 存下 `"2024-01-01 10"`。下次轮询 `WHERE updated_at > '2024-01-01 10'` → 要么每次报错（连接器卡死，错误被 pollTablesForChanges 吞掉）要么比较点提前 → 大量重复。
- **审计置信度**：高
- **验证与修复**：`doCommit`/`doResetToPosition` 改为 `split(":", 2)`（保留首个冒号后的完整时间戳），同时保留"空表名/空值视为畸形忽略"语义（既有 `commitAndResetSkipMalformedPositions` 用例覆盖）。

### CDC-H5 CDCManager.getCurrentPositionsAll() 在任一连接器尚无位置时抛 NPE [已修复]
- **位置**：`cdc/.../CDCManager.java:200-206`
- **触发**：`start()` 后首个 binlog/复制事件或首轮轮询扫描之前调用。
- **影响**：确定性 NPE：`Collectors.toMap` 用 `Map.merge`，拒绝 null value。标准监控调用即崩。
- **审计置信度**：高
- **验证与修复**：改为逐连接器取值并跳过尚无位置者（javadoc 注明省略语义）。

### 中（Medium）

### CDC-M1 无背压：无界队列 + 每轮无界扫描 ⏳
- **位置**：`DatabasePollingCDCConnector.java:31,107-121,271-273`（`SELECT * ... ORDER BY` 无 LIMIT）；`MySQLBinlogCDCConnector.java:27`；`PostgreSQLLogicalReplicationCDCConnector.java:36`
- **影响**：消费慢于生产或大表基线扫描时 OOM。
- **审计置信度**：高

### CDC-M2 lastPolledValues（HashMap）跨线程数据竞争 ⏳
- **位置**：`DatabasePollingCDCConnector.java:32,130,143,226,318-320`；`AbstractCDCConnector.java:90-116`（poll() 无同步）
- **影响**：并发扫描同水位 → 重复事件；非同步 HashMap 并发写可损坏结构。
- **审计置信度**：高（竞态存在），中（实际频率）

### CDC-M3 调度器在 listener 中途注销时仍会取出并丢弃事件 ⏳
- **位置**：`AbstractCDCConnector.java:233-241,301-309`
- **影响**：`poll()` 已排空批次后 listener 变 null → 事件静默丢弃（窄窗口）。
- **审计置信度**：高（路径确定），低（概率）

### CDC-M4 失败路径泄漏调度器（非守护线程，阻止 JVM 退出）与 Hikari 池 ⏳
- **位置**：`AbstractCDCConnector.java:57-88`（doStop 先于 scheduler 关闭块抛出则调度器泄漏）；`DatabasePollingCDCConnector.java:87-91,230`
- **审计置信度**：高

### CDC-M5 MySQL 事件位置差一 [commit 后重启重复投递 ⏳]
- **位置**：`MySQLBinlogCDCConnector.java:231,265,298` vs `:182`
- **影响**：事件 E 的行带的是 E **前一个**事件的位置；从该位置恢复会重放 E → 重复写入。首个事件 position 为 null。
- **审计置信度**：高

### CDC-M6 CDCManager 重启永久破坏健康监控（复用已终止的调度器） [已修复]
- **位置**：`CDCManager.java:21,100,125-135,237-245`
- **影响**：stop() 关闭单一 scheduler 字段后再次 start() → `RejectedExecutionException`（藏在 thenRun 回调里）→ 健康监控死亡。
- **审计置信度**：高
- **验证与修复**：scheduler 改为 volatile 字段，startHealthMonitoring 惰性重建，stop() 关闭后置 null。

### CDC-M7 PG parseColumnData 按空白切分值 [行数据静默损坏 ✅已修复]
- **位置**：`PostgreSQLLogicalReplicationCDCConnector.java:332-363`（:340 `data.split("\\s+")`）
- **触发**：任何含空格的文本值 `name[text]:'John Doe'`。
- **影响**：`"John Doe"` 变 `"John"`，`Doe'` 丢弃；字面量 `'null'` 与 SQL NULL 不可区分（:349）；朴素去引号毁掉转义引号。
- **审计置信度**：高
- **验证与修复**：`parseColumnData` 重写为引号感知的逐字符 tokenizer（`inQuote` 状态机，引号内空白不切分），去引号时 `''→'`；新增 `coerceByPgType` 按 PG 类型把 integer/bigint/numeric/bool 等解析为对应 Java 类型，解析失败回退原始字符串。回归测试 `quotedValuesKeepTheirSpacesAndEscapedQuotes`：`name[text]:'John Doe'`→"John Doe"、`city[text]:'O''Hare'`→"O'Hare"、`note[text]:plain`、"id[integer]:7"→Integer 7。

### 低（Low）

- **CDC-L1** 列名解析器负缓存（`MySQLColumnNameResolver.java:45-52`）：瞬时失败被 `computeIfAbsent` 缓存为空列表，该表列名永久解析不出（col_0/col_1…）直到重启；`DriverManager.getConnection` 在 binlog 事件线程上执行（:61），每张未知表阻塞事件消费至超时。
- **CDC-L2** 配置校验缺口（`CDCConfigurationBuilder.java`）：`validate()`（:325-329）只查 name；`batchSize<=0` → doPoll 永不排空、队列无限增长；负 `pollingIntervalMs` 通过校验但在调度时抛 IAE；`(Boolean) properties.getOrDefault(...)`（:311,316）对字符串 "true" 抛 ClassCastException；PG `statusIntervalMs` `(int)` 截断（:203）；`CDCConnectorFactory.create`（:47）null 类型名抛 NPE 而非 IAE。
- **CDC-L3** 指标 lost updates（`AbstractCDCConnector.java:109-110,124-125,273-274`）：`metrics.get()/set()` 无 CAS。
- **CDC-L4** `CDCManager.addConnector` check-then-act（:30-34）：`containsKey` 后 `put` 可静默替换同名连接器，应 `putIfAbsent`。
- **CDC-L5** MySQL `doCommit`/`doResetToPosition` 边界（:117-121,131-139）：无 `parts.length` 检查 → 尾冒号位置 AIOOBE；`doResetToPosition` 在 start() 前调用对 `binaryLogClient` NPE。
- **CDC-L6** `ChangeEventQueueSink.invoke`（:49-56）：TimeoutException 未取消在途发送 → 迟到完成在重试时重复事件；InterruptedException 清掉中断标志后传播。
- **CDC-L7** 跨线程字段可见性：`AbstractCDCConnector.currentPosition`（:25）、`MySQLBinlogCDCConnector.binlogFilename`（:29）无 volatile。
- **CDC-L8** 快照双重投递窗口（`DatabasePollingCDCConnector.java:313-321`）：整表扫描完才推进 lastPolledValues，扫描中途失败/停止重入队已扫过的行；带快照重启时重复触发 onSnapshotStarted（:202-207）。
- **CDC-L9** 序列化：`CDCSource` 实现了 Serializable 但持有非 transient 的 `CDCConnector`（Hikari/BinaryLogClient 不可序列化）→ NotSerializableException；`ChangeEvent` 无 serialVersionUID。

### cdc 审计确认无问题项

`ChangeEvent` 4 参构造器无递归调用问题；`TableFilter` 通配符正则转义正确；`CDCSource` 空转退出为文档化行为；`CDCMetrics.withEventCounts` 总数计算正确；MySQL ALTER 换 table id 后缓存会重新解析；Hikari 池参数合理；XID 双重 `updateCurrentPosition` 幂等无害；3b262e7 对轮询连接器的实例内修复本身有效（`DatabasePollingRestartResumeTest` 验证），不完整的是 H1/H2（另两个连接器）与跨进程持久化缺失。

---

## runtime + core 模块审计结果（RT-H1~H3、RT-M1~M7、RT-L1~L11）

（InMemoryWindowedStream 的 trigger/merge 逻辑与 core WindowAssigner 为本轮已重写区域，按指示跳过深审。）

### 高（High）

### RT-H1 checkpoint 恢复失效：Map<Integer,String> 的 key 经 JSON 往返变 String，每次恢复都从 0-0 重放 [已修复]
- **位置**：`runtime/.../redis/internal/RedisRuntimeCheckpointManager.java:273-293`（快照）、`419-420`（恢复）；序列化在 `checkpoint/.../redis/RedisCheckpointStorage.java:37-41`（默认 Jackson codec）
- **触发**：任何带 `restoreFromLatestCheckpoint=true` 的重启。
- **影响**：offset 快照能存进去，但内层 map 读回来是 `Map<String,String>`；未检 unchecked 赋值掩盖了这一点，`get(Integer)` 永远 null → `startId` 恒为 `"0-0"` → 每次恢复整流从头重放；`sinkDeduplicationEnabled=false` 时重复副作用。offset checkpoint 功能静默失效。
- **审计置信度**：高（机制确定）
- **验证与修复**：新增 `offsetForPartition` 查找：Integer key miss 时回退 String key（并兼容两种 key 混存，Integer 优先）；恢复失败日志从 DEBUG 升到 WARN。回归测试 `RedisRuntimeCheckpointManagerOffsetsTest`（4 用例：String-keyed 恢复命中、进程内 Integer 命中、缺失分区回退、混存优先级）。

### RT-H2 stop-the-world checkpoint 期间做全库 SCAN：消费者暂停时长随整个 Redis 库大小伸缩 ⏳
- **位置**：`checkpoint/.../RedisCheckpointStorage.java:57-81`（`keys.getKeys()` 无 pattern）；调用点 `RedisRuntimeCheckpointManager.java:146,552-579,240`
- **触发**：每个周期 checkpoint tick（STW 流程先 pause 消费者再 triggerCheckpoint）。
- **影响**：`listCheckpoints` 对整个 keyspace SCAN 后逐 key GET 再排序。checkpoint 延迟（即消费暂停时长）与 DB 总键数成正比而非 checkpoint 数；共享/生产 Redis 上每个 tick 都卡住全部管道消费；`deferAckUntilCheckpoint=true` 时直接拉长未 ack 窗口，放大 claimIdleMs 重投递竞态。
- **审计置信度**：高

### RT-H3 窗口/定时器状态在 emit 前被清除：sink 发送失败即永久丢失该窗口已累加数据 [设计缺陷]
- **位置**：`runtime/.../redis/internal/RedisStreamBuilder.java`（reduce 516-539、aggregate 575-595、sum 713-738、count 758-777、apply 654-672）
- **触发**：窗口 fire（due zset 先移除）后 `sink.invoke` 抛异常（Redis 抖动、sink 异常、checkpoint 中止）。
- **影响**：消息进 RETRY 重投递，但窗口状态已在 `finally` 里删掉 → 重投递的元素单独重新累计，窗口随后以残缺数据 fire——静默错误结果。apply 更糟：状态清除发生在缓冲结果 emit 之前。
- **处理**：fire-and-purge 非原子是该设计的固有问题，需两阶段/结果缓冲提交重构；记为后续任务。

### 中（Medium）

- **RT-M1** `checkpointDrainTimeout=ZERO` 使排空循环死循环且消费者永久 pause（`RedisStreamExecutionEnvironment.java:657-678`；deadline 检查被 `>0` 门控，ZERO 通过校验）→ checkpointing 标志永不释放，作业死锁无报错。✅已修复：builder 将 null/ZERO/负值统一回退 30s 默认；排空循环的 deadline 检查改为无条件（防御性）。回归断言加入 `RedisRuntimeConfigBuilderCoverageTest`。
- **RT-M2** 事件时间定时器队列满时静默丢弃注册（`RedisPipelineRunner.java:406-429`）→ 对应窗口/回调永不 fire，仅 60s 限速 warn。
- **RT-M3** watermark 与窗口 fire 纯消息驱动：空闲分区/子任务永不 fire；`windowMaxFiresPerRecord=256` 截断后剩余窗口要等下一条记录；`markIdle()` 是 no-op（`RedisPipelineRunner.java:100-113,163-184`、`RedisStreamBuilder.java:451-454,791-810`）。
- **RT-M4** `restoreState` 先删后建无原子性：中途失败状态已清空、作业以无状态继续（`RedisRuntimeCheckpointManager.java:469-549`；失败只 debug/warn）。
- **RT-M5** offset 快照期间 Redis 错误被 DEBUG 吞掉且存 null → 恢复时该分区静默回退 0-0（`RedisRuntimeCheckpointManager.java:283-292`）。✅已缓解：日志升为 WARN 并明示"该分区将回退 0-0"；恢复失败日志同样升 WARN（彻底修复需失败即中止 checkpoint，涉及策略决策，留待后续）。
- **RT-M6** sink 去重是 check-then-act 两跳（`RSetCache.contains` 与 `add` 之间夹着 `sink.invoke`，`RedisPipelineRunner.java:186-230`）：并发重投递下双写（文档已注明 best-effort）。
- **RT-M7** 同源分叉的两个 pipeline 按消费组分摊而非广播，且同种窗口算子 stateName 冲突共享状态（`RedisStreamBuilder.java:413,185-194`）→ 分叉用法下窗口结果静默错误；无校验无文档。

### 低（Low）

- **RT-L1** sink commit 失败仍返回非 null Checkpoint（`RedisStreamExecutionEnvironment.java:727-737`），调用方无法区分。
- **RT-L2** DeferredAcks key `topic|group` 分隔符歧义 + `ackAll` 用最后 poll 的 id 而非 max 推进 frontier（:805-887）；含 `|` 的 topic 使 ack 静默失败。
- **RT-L3** 分区数回退为 1 时，checkpoint/恢复/frontier 只覆盖分区 0（`TopicPartitionRegistry.java:48-61` 等）。
- **RT-L4** 同 jobName 双进程 checkpoint id 撞车互相覆盖（`RedisRuntimeCheckpointManager.java:95-126`）。
- **RT-L5** `sinkCommittedMarker` 无 TTL 且 `checkpointsToKeep=0` 时清理禁用 → 无界增长（:177-185,552-556）。
- **RT-L6** `listCheckpoints` 仅按时间戳排序，同毫秒 tie 时 getLatest 可能取旧（`RedisCheckpointStorage.java:76`）。
- **RT-L7** 窗口 member 编码对含 `\u0001` 的字符串 key 解析错乱（`RedisStreamBuilder.java:371,779-823`）。
- **RT-L8** `NumberAggregationUtils.add` long 溢出静默、BigInteger/BigDecimal 截断（`NumberAggregationUtils.java:29-32`）。
- **RT-L9** `addSource` 无法停止不终止的 source（`StreamExecutionEnvironment.java:75-122`；`cancel()` 从不被调）。
- **RT-L10** `InstanceIdGenerator.generateLocalInstanceId` 忽略 serviceName 且 javadoc 与实现不符（core `InstanceIdGenerator.java:39-43`）。
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

- **重构级**（需专项设计，非局部修复）：MQ-01/MQ-04（DLQ pending 回收与删除策略语义）、CDC-H3（断线重连框架）、RT-H2（checkpoint 全库 SCAN）、B-20（完成匹配的有界化）、B-24（KTable 物化清理）。
- **设计决策类**：RT-H3（fire-and-purge 原子性，需两阶段提交）、B-06（配置通知重同步，涉及 API 契约）。
- **风险可控/影响良性**：MQ-11（frontier 回退方向安全）、RT-M3/M6/M7（文档化语义）、B-36（文档已声明测试用途）等。
- 其余 ⏳ 条目为审计发现但本轮未逐条复现验证（范围限制），均已给出触发条件、位置与修复方向，可直接作为下轮输入。

### race 检测说明

Java 生态无 `go test -race` 的直接等价物。等效手段：全量测试套件（含 `@Tag("integration")` 的真实 Redis 集成测试）+ 本轮对并发敏感路径的定向并发测试（join 并发、会话窗口合并、租约原子性、熔断窗口），以及审计中对内存可见性/原子性的逐点分析（B-16/B-19/B-25/B-26/CDC-M2/MQ-08 等条目）。
