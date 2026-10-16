---
layout: home

hero:
  name: Redis Streaming
  text: Redis-based streaming processing framework
  tagline: DataStream API, state, windows and exactly-once semantics across 20 Gradle modules
  actions:
    - theme: brand
      text: Quick Start
      link: /en/Quick-Start
    - theme: alt
      text: Architecture
      link: /en/Architecture

features:
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><circle cx="12" cy="12" r="2.8"/><path d="M3 8c2.7 0 2.7 8 5.4 8S11.1 8 13.8 8 16.5 16 19.2 16h1.8"/><path d="M3 16c2.7 0 2.7-8 5.4-8"/></svg>'
    title: Core Modules
    details: DataStream, State, Checkpoint, Window and other core APIs
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><rect x="4" y="4" width="16" height="5.5" rx="1.5"/><rect x="4" y="12" width="16" height="5.5" rx="1.5"/><path d="M7.5 6.75h.01"/><path d="M7.5 14.75h.01"/><path d="M16.5 20.5h-9"/></svg>'
    title: Infrastructure
    details: Config center, service registry, Redis Streams message queue
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M7.5 3.5v4"/><path d="M16.5 3.5v4"/><rect x="4" y="7.5" width="16" height="5" rx="2"/><path d="M12 12.5v4"/><path d="M8 16.5h8"/><path d="M12 16.5v4"/></svg>'
    title: Data Integration
    details: Source & Sink connectors, CDC, window aggregation
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="m12 3.5 2 5.2 5.5 1.5-5.5 1.9-2 5.4-2-5.4L4.5 10.2 10 8.7z"/><path d="M18.5 16.5l.9 2.1 2.1.6-2.1.7-.9 2.1-.9-2.1-2.1-.7 2.1-.6z"/></svg>'
    title: Advanced Features
    details: KTable, stream joins, CEP pattern detection
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M12 3 5 6v5.2c0 4.4 2.9 7.4 7 8.8 4.1-1.4 7-4.4 7-8.8V6l-7-3z"/><path d="m8.8 12 2.2 2.2 4.2-4.4"/></svg>'
    title: Reliability
    details: Exactly-once semantics, reliability components, Micrometer metrics
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="24" height="24"><path d="M12 21c0-6.5 3-12.5 8.5-15.5-1 8.5-4.2 13-8.5 15.5z"/><path d="M12 21C12 14.5 9 8.5 3.5 5.5 4.5 14 7.7 18.5 12 21z"/><path d="M12 21v-4"/></svg>'
    title: Spring Boot
    details: Auto-configuration and annotations for Spring Boot
---

## English Documentation

The English section covers the pages listed below. The complete module reference (all 20 Gradle modules) is maintained in Chinese — use the language switcher in the top right to move between the two versions.

**Getting Started**
- [Quick Start](/en/Quick-Start) · [Spring Boot Starter](/en/Spring-Boot-Starter) · [Examples](/en/Examples)

**Core Concepts**
- [Architecture](/en/Architecture) · [API Overview](/en/API)

**Infrastructure Modules**
- [MQ Guide](/en/MQ-Guide) · [MQ Design](/en/MQ-Design) · [MQ Broker Interaction](/en/MQ-Broker-Interaction)
- [Registry Guide](/en/Registry-Guide) · [Registry Design](/en/Registry-Design)

**Operations**
- [Deployment](/en/Deployment) · [Performance](/en/Performance) · [Troubleshooting](/en/Troubleshooting) · [CI/CD](/en/GitHub-Actions)

**Development**
- [Developer Guide](/en/Developer-Guide) · [Testing](/en/Testing) · [Publishing](/en/Publishing)

### Full Module Reference (Chinese)

- Core abstractions — [Core](/Core) · [Runtime](/runtime)
- Infrastructure — [Config](/config) · [Registry](/Registry) · [MQ](/MQ) · [State](/state) · [Checkpoint](/checkpoint) · [Watermark](/watermark)
- Functional modules — [Window](/window) · [Aggregation](/Aggregation) · [Table](/Table) · [Join](/Join) · [CDC](/CDC) · [Source & Sink](/source-sink)
- Advanced features — [Reliability](/reliability) · [CEP](/CEP)
- Observability — [Metrics](/Metrics)
- Design docs — [Exactly-Once](/exactly-once) · [Architectural overview](/Architecture) · [Spring Boot Starter](/Spring-Boot-Starter)
