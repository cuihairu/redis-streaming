# Streaming 框架架构设计文档

## 架构概览

### 整体架构 (5层设计)

```
应用层 -> 集成层 -> 高级功能层 -> 功能模块层 -> 基础设施层 -> Redis
```

### 设计原则

1. **分层解耦**: 5 层架构，职责清晰
2. **接口抽象**: API 与实现分离
3. **Redis 中心**: 所有状态基于 Redis
4. **轻量化**: 无需额外组件
5. **可扩展**: 支持自定义扩展

## 核心模块

### Tier 1: Core (核心抽象)
- DataStream API
- KeyedStream API  
- WindowedStream API
- State 抽象

### Tier 2: Infrastructure (基础设施)
- **MQ**: Redis Streams 消息队列
- **Registry**: 服务注册发现
- **State**: 分布式状态管理
- **Checkpoint**: 检查点机制

### Tier 3: Functional (功能模块)
- **Aggregation**: 窗口聚合
- **Table**: 流表二元性
- **Join**: 流式 Join
- **CDC**: 变更数据捕获
- **Sink/Source**: 连接器

### Tier 4: Advanced (高级功能)
- **Reliability**: 可靠性保证
- **CEP**: 复杂事件处理

### Tier 5: Integration (集成)
- **Metrics**: Prometheus 监控
- **Spring Boot**: 自动配置

## 技术选型

### Redis 数据结构映射

| 功能 | Redis 结构 |
|------|-----------|
| 消息队列 | Streams |
| 服务注册 | Hash + Pub/Sub |
| ValueState | String |
| MapState | Hash |
| ListState | List |
| SetState | Set |
| PV计数 | String (INCR) |
| UV计数 | HyperLogLog |
| Top-K | Sorted Set |
| KTable | Hash |

## 扩展点

- 自定义 Source/Sink
- 自定义聚合函数
- 自定义 CEP 模式
- 自定义监控指标

---

**版本**: 0.1.0
