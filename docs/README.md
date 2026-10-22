# Redis Streaming

A Redis-based stream processing framework for Java 17.

## Documentation Map

### Getting Started
- [Quick Start](Quick-Start.md) - build, test, and run the examples
- [Spring Boot Integration](spring-boot-starter-guide.md) - using the Spring Boot Starter

### Core Concepts
- [Architecture Overview](Architecture.md) - system architecture design
- [Core API](Core.md) - core streaming interfaces
- [Runtime Environment](runtime.md) - the Runtime module in depth
- [API Reference](API.md) - module API index

### Module Documentation

#### Infrastructure
- [Config module](config.md) - configuration center
- [Registry module](Registry.md) - service registration and discovery
- [MQ module](MQ.md) - message queue

#### Streaming Core
- [State module](state.md) - state management
- [Checkpoint module](checkpoint.md) - checkpointing
- [Watermark module](watermark.md) - watermark generation
- [Window module](window.md) - window operations

#### Data Integration
- [Source & Sink](source-sink.md) - data sources and sinks
- [CDC module](CDC.md) - change data capture
- [Aggregation module](Aggregation.md) - aggregations
- [Table module](Table.md) - table operations
- [Join module](Join.md) - stream joins

#### Advanced Features
- [Reliability module](reliability.md) - reliability components
- [CEP module](CEP.md) - complex event processing

#### Observability
- [Metrics & Observability](Metrics.md) - metric monitoring (includes the MQ/Runtime Micrometer metric list; the homegrown metrics module was removed on 2026-10-12)

#### Integration
- [Spring Boot Starter](Spring-Boot-Starter.md) - about the Starter module
- [Examples](Examples.md) - example code

### Design Documents
- [MQ Design](MQ-Design.md) - MQ module design
- [MQ Guide](MQ-Guide.md) - MQ usage guide
- [MQ Broker Interaction](MQ-Broker-Interaction.md) - broker-layer interaction protocol
- [Registry Design](Registry-Design.md) - Registry module design
- [Registry Guide](Registry-Guide.md) - Registry usage guide
- [Exactly-Once](exactly-once.md) - exactly-once semantics
- [Retention & ACK Policy](retention-and-ack-policy.md) - stream retention and ack-delete strategy

### Operations
- [Deployment Guide](Deployment.md) - deployment and operations (configuration defaults)
- [Performance Tuning](Performance.md) - performance optimization
- [Troubleshooting](Troubleshooting.md) - common issues
- [GitHub Actions](GitHub-Actions.md) - CI/CD

### Development
- [Developer Guide](Developer-Guide.md) - how to contribute code
- [Testing Guide](Testing.md) - how to write tests
- [Publishing Process](maven-publish.md) - Maven publishing
- [Potential Bug Ledger](POTENTIAL_BUGS.md) - audit findings and fix records

## Local Preview

```bash
cd docs
npm ci
npm run docs:dev
```

Open http://localhost:5173 to view the docs site (the VitePress default port).

## Documentation Site

Online docs: https://cuihairu.github.io/redis-streaming/

## Versioning

The version number is derived automatically from `v*` git tags by [axion-release](https://github.com/allegro/axion-release) (see `scmVersion` in the root `build.gradle`); it is not maintained by hand. The repository's current latest tag is **v0.2.3**.
