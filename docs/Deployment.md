# 安装部署

本页提供最小上线要点，完整过程请见 docs/DEPLOYMENT.md。

## 1) 运行时要求
- Java 17+
- Redis 6+（生产环境推荐集群/哨兵）

## 2) Redisson 集成（推荐）
使用 redisson-spring-boot-starter 配置集群/哨兵：
```gradle
implementation 'org.redisson:redisson-spring-boot-starter:3.29.0'
```

Cluster 示例（redisson-cluster.yaml）：
```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```
application.yml：
```yaml
spring:
  redisson:
    file: classpath:redisson-cluster.yaml
```

## 3) 可观测性
- 开启 Actuator + Prometheus；抓取 `/actuator/prometheus`
- 关注 `mq_*`、`retention_*`、`reliability_*` 指标与告警
- Redis runtime 指标：`redis_streaming_runtime_*`（吞吐/延迟/checkpoint/state/window/watermark 等）
- Trace/日志关联：`RedisRuntimeConfig.mdcEnabled(true)` + `mdcSampleRate(0~1)`（MDC keys：`rs.*`）

## 4) 上线自检
- Redis 连通性/权限校验通过
- 消费者组分配均衡，pending 扫描/接管（claim）策略就绪
- DLQ 回放流程已演练，增长告警已配置

## 5) 多实例与滚动升级建议（要点）
- 同一 job 建议通过 consumer group 水平扩展；并结合 `MqOptions.maxLeasedPartitionsPerConsumer` 避免超配 lease。
- checkpoint 为单进程 stop-the-world（不跨实例 barrier）；多实例部署时请优先使用幂等 sink 或 Redis-only 原子 sink 方案确保端到端效果一致性。
- 滚动升级：先扩容新版本实例，观察 leased partitions 与错误率稳定后，再逐步缩容旧版本实例。
