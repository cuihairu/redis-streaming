---
layout: home

hero:
  name: Redis Streaming
  text: 基于 Redis 的流处理框架
  tagline: 高性能、可扩展的实时数据处理解决方案
  actions:
    - theme: brand
      text: 快速开始
      link: /Quick-Start
    - theme: alt
      text: 架构设计
      link: /Architecture

features:
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><circle cx="12" cy="12" r="2.8"/><path d="M3 8c2.7 0 2.7 8 5.4 8S11.1 8 13.8 8 16.5 16 19.2 16h1.8"/><path d="M3 16c2.7 0 2.7-8 5.4-8"/></svg>'
    title: 核心模块
    details: DataStream、State、Checkpoint、Window 等核心 API
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><rect x="4" y="4" width="16" height="5.5" rx="1.5"/><rect x="4" y="12" width="16" height="5.5" rx="1.5"/><path d="M7.5 6.75h.01"/><path d="M7.5 14.75h.01"/><path d="M16.5 20.5h-9"/></svg>'
    title: 基础设施
    details: Config 配置中心、Registry 服务注册、MQ 消息队列
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M7.5 3.5v4"/><path d="M16.5 3.5v4"/><rect x="4" y="7.5" width="16" height="5" rx="2"/><path d="M12 12.5v4"/><path d="M8 16.5h8"/><path d="M12 16.5v4"/></svg>'
    title: 数据集成
    details: Source & Sink 连接器、CDC 变更捕获、Aggregation 聚合
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="m12 3.5 2 5.2 5.5 1.5-5.5 1.9-2 5.4-2-5.4L4.5 10.2 10 8.7z"/><path d="M18.5 16.5l.9 2.1 2.1.6-2.1.7-.9 2.1-.9-2.1-2.1-.7 2.1-.6z"/></svg>'
    title: 高级特性
    details: Table 表操作、Join 流连接、CEP 复杂事件处理
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M12 3 5 6v5.2c0 4.4 2.9 7.4 7 8.8 4.1-1.4 7-4.4 7-8.8V6l-7-3z"/><path d="m8.8 12 2.2 2.2 4.2-4.4"/></svg>'
    title: 可靠性保障
    details: Exactly-Once 语义、Reliability 组件、Metrics 监控
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M12 21c0-6.5 3-12.5 8.5-15.5-1 8.5-4.2 13-8.5 15.5z"/><path d="M12 21C12 14.5 9 8.5 3.5 5.5 4.5 14 7.7 18.5 12 21z"/><path d="M12 21v-4"/></svg>'
    title: Spring Boot
    details: 开箱即用的 Spring Boot Starter 集成
---

## 模块索引

**核心层**
- [Core](/Core) · [Runtime](/runtime) · [State](/state) · [Checkpoint](/checkpoint)

**流处理**
- [Window](/window) · [Watermark](/watermark) · [Aggregation](/Aggregation)

**数据操作**
- [Table](/Table) · [Join](/Join) · [CDC](/CDC) · [Source & Sink](/source-sink)

**基础设施**
- [Config](/config) · [Registry](/Registry) · [MQ](/MQ)

**可靠性**
- [Reliability](/reliability) · [Metrics](/Metrics) · [Exactly-Once](/exactly-once)

**集成**
- [Spring Boot Starter](/Spring-Boot-Starter) · [Examples](/Examples)
