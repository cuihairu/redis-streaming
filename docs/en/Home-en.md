# Redis-Streaming Wiki

[中文](Home) | [English](Home-en)

---

Welcome to Redis-Streaming Wiki!

## 📖 About

Redis-Streaming is a lightweight stream processing framework based on Redis, providing:
- 🔄 **Message Queue (MQ)** - Reliable message queue based on Redis Streams
- 📡 **Service Registry & Discovery** - Distributed service registration with health checks
- 💾 **State Management** - Multiple state storage types
- ⏰ **Stream Processing** - Window aggregation, stream joins, CEP
- 🛡️ **Reliability** - Retry, deduplication, dead letter queue

## 🚀 Quick Navigation

### Getting Started
- [[Quick Start|Quick-Start-en]] - 5-minute guide
- [[Deployment|Deployment-en]] - Production deployment
- [[Testing Guide|Testing-en]] - How to run tests

### Core Features
- [[Service Registry|Registry-Guide-en]] - Complete registry guide
- [[Message Queue|MQ-Guide-en]] - MQ usage guide
- [[Spring Boot Integration|Spring-Boot-Starter-en]] - Quick integration

### Architecture
- [[Overall Architecture|Architecture-en]] - System architecture design
- [[Detailed Design|Design-en]] - Detailed design documents
- [[Registry Design|Registry-Design-en]] - Registry module design
- [[MQ Design|MQ-Design-en]] - Message queue design

### Module Index
- [[Core|Core]] · [[Runtime|Runtime]] · [[Registry|Registry]] · [[MQ|MQ]] · [[Config|Config]]
- [[State|State]] · [[Checkpoint|Checkpoint]] · [[Watermark|Watermark]]
- [[Window|Window]] · [[Aggregation|Aggregation]] · [[Table|Table]] · [[Join|Join]] · [[CDC|CDC]]
- [[Sink|Sink]] · [[Source|Source]] · [[Reliability|Reliability]] · [[CEP|CEP]] · [[Metrics|Metrics]] · [[Examples|Examples]]

### Development
- [[Developer Guide|Developer-Guide-en]] - Project structure, build, test
- [[Publishing|Publishing-en]] - Publish to Maven Central
- [[CI/CD Workflow|GitHub-Actions-en]] - GitHub Actions configuration

### Operations
- [[Performance Tuning|Performance-en]] - Performance optimization
- [[Troubleshooting|Troubleshooting-en]] - Common issues and solutions

## 🔗 Links

- [GitHub Repository](https://github.com/cuihairu/redis-streaming)
- [Maven Central](https://search.maven.org/search?q=g:io.github.cuihairu.redis-streaming)
- [Issue Tracker](https://github.com/cuihairu/redis-streaming/issues)
- [Contributing](https://github.com/cuihairu/redis-streaming/blob/main/CONTRIBUTING.md)

## 📊 Features

| Feature | Description | Status |
|---------|-------------|--------|
| Message Queue (MQ) | Redis Streams implementation | ✅ Done |
| Service Registry | Multi-protocol health checks | ✅ Done |
| State Management | 4 state types | ✅ Done |
| Window Aggregation | PV/UV, TopK | ✅ Done |
| CDC Integration | MySQL/PostgreSQL | ✅ Done |
| Spring Boot | Auto-configuration | ✅ Done |

## 📝 Version Info

- **Current Version**: v0.1.0
- **Last Updated**: 2025-10-13
- **Java Version**: 17+
- **Redis Version**: 6.0+
