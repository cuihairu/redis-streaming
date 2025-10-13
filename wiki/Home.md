# Redis-Streaming Wiki

[中文](Home) | [English](Home-en)

---

欢迎来到 Redis-Streaming 项目 Wiki！

## 📖 关于项目

Redis-Streaming 是一个基于 Redis 的轻量级流处理框架，提供：
- 🔄 **消息队列 (MQ)** - 基于 Redis Streams 的可靠消息队列
- 📡 **服务注册发现** - 分布式服务注册与健康检查
- 💾 **状态管理** - 多种状态存储类型
- ⏰ **流式处理** - 窗口聚合、流式 Join、CEP
- 🛡️ **可靠性保证** - 重试、去重、死信队列

## 🚀 快速导航

### 入门指南
- [[快速开始|Quick-Start]] - 5分钟上手指南
- [[安装部署|Deployment]] - 部署到生产环境
- [[测试指南|Testing]] - 如何运行测试

### 核心功能
- [[服务注册发现|Registry-Guide]] - Registry 完整使用指南
- [[消息队列|MQ-Guide]] - MQ 使用指南
- [[Spring Boot 集成|Spring-Boot-Starter]] - Spring Boot 快速集成

### 架构设计
- [[整体架构|Architecture]] - 系统架构设计
- [[详细设计|Design]] - 详细设计文档
- [[Registry 设计|Registry-Design]] - 注册中心设计文档
- [[MQ 设计|MQ-Design]] - 消息队列设计文档

### 开发指南
- [[开发者指南|Developer-Guide]] - 项目结构、构建、测试
- [[发布流程|Publishing]] - 发布到 Maven Central
- [[CI/CD 工作流|GitHub-Actions]] - GitHub Actions 配置

### 运维管理
- [[性能优化|Performance]] - 性能调优指南
- [[故障排查|Troubleshooting]] - 常见问题解决方案

## 🔗 相关链接

- [GitHub 仓库](https://github.com/cuihairu/redis-streaming)
- [Maven Central](https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming)
- [问题反馈](https://github.com/cuihairu/redis-streaming/issues)
- [贡献指南](https://github.com/cuihairu/redis-streaming/blob/main/CONTRIBUTING.md)

## 📊 项目特性

| 特性 | 描述 | 状态 |
|------|------|------|
| 消息队列 (MQ) | Redis Streams 实现 | ✅ 完成 |
| 服务注册发现 | 多协议健康检查 | ✅ 完成 |
| 状态管理 | 4种状态类型 | ✅ 完成 |
| 窗口聚合 | PV/UV、TopK | ✅ 完成 |
| CDC 集成 | MySQL/PostgreSQL | ✅ 完成 |
| Spring Boot | 自动配置 | ✅ 完成 |

## 📝 版本信息

- **当前版本**: v0.1.0
- **最后更新**: 2025-10-13
- **Java 版本**: 17+
- **Redis 版本**: 6.0+
