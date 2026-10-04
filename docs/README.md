# Redis Streaming

基于 Redis 的 Java 17 流处理框架。

## 文档导航

### 快速开始
- [快速开始](Quick-Start.md) - 构建、测试并运行示例
- [Spring Boot 集成](spring-boot-starter-guide.md) - Spring Boot Starter 使用

### 核心概念
- [架构概述](Architecture.md) - 系统架构设计
- [核心 API](Core.md) - 流处理核心接口
- [运行时环境](runtime.md) - Runtime 模块详解
- [API 参考](API.md) - 模块 API 索引

### 模块文档

#### 基础设施
- [Config 模块](config.md) - 配置中心
- [Registry 模块](Registry.md) - 服务注册与发现
- [MQ 模块](MQ.md) - 消息队列

#### 流处理核心
- [State 模块](state.md) - 状态管理
- [Checkpoint 模块](checkpoint.md) - 检查点机制
- [Watermark 模块](watermark.md) - 水位线生成
- [Window 模块](window.md) - 窗口操作

#### 数据集成
- [Source & Sink](source-sink.md) - 数据源和数据汇
- [CDC 模块](CDC.md) - 变更数据捕获
- [Aggregation 模块](Aggregation.md) - 聚合操作
- [Table 模块](Table.md) - 表操作
- [Join 模块](Join.md) - 流连接

#### 高级特性
- [Reliability 模块](reliability.md) - 可靠性组件
- [CEP 模块](CEP.md) - 复杂事件处理

#### 可观测性
- [Metrics 模块](Metrics.md) - 指标监控（含 MQ/Runtime Micrometer 指标清单）

#### 集成
- [Spring Boot Starter](Spring-Boot-Starter.md) - Starter 模块说明
- [Examples](Examples.md) - 示例代码

### 设计文档
- [MQ 设计](MQ-Design.md) - MQ 模块设计
- [MQ 指南](MQ-Guide.md) - MQ 使用指南
- [MQ Broker 交互](MQ-Broker-Interaction.md) - Broker 层交互协议
- [Registry 设计](Registry-Design.md) - Registry 模块设计
- [Registry 指南](Registry-Guide.md) - Registry 使用指南
- [Exactly-Once](exactly-once.md) - 精确一次语义
- [保留与 ACK 策略](retention-and-ack-policy.md) - 流保留与 ack 删除策略

### 运维指南
- [部署指南](Deployment.md) - 部署和运维（配置项默认值）
- [性能调优](Performance.md) - 性能优化
- [故障排查](troubleshooting.md) - 常见问题
- [GitHub Actions](GitHub-Actions.md) - CI/CD

### 开发指南
- [开发指南](Developer-Guide.md) - 如何贡献代码
- [测试指南](Testing.md) - 如何编写测试
- [发布流程](maven-publish.md) - Maven 发布
- [潜在 Bug 台账](POTENTIAL_BUGS.md) - 审计发现与修复记录

## 本地预览

```bash
cd docs
npm ci
npm run docs:dev
```

访问 http://localhost:5173 查看文档站点（VitePress 默认端口）。

## 文档站点

在线文档: https://cuihairu.github.io/redis-streaming/

## 版本

版本号由 [axion-release](https://github.com/allegro/axion-release) 从 `v*` git 标签自动推导（见根目录 `build.gradle` 的 `scmVersion`），不手工维护；仓库当前最新标签为 **v0.2.3**。
