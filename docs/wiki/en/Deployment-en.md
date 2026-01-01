# Deployment (EN)

This page outlines a minimal production setup. For full steps, see docs/DEPLOYMENT.md.

## 1) Runtime Requirements
- Java 17+
- Redis 6+ (cluster/sentinel recommended for HA)

## 2) Redisson Configuration (recommended)
Use redisson-spring-boot-starter to integrate cluster/sentinel:
```gradle
implementation 'org.redisson:redisson-spring-boot-starter:3.29.0'
```

Cluster example (redisson-cluster.yaml):
```yaml
clusterServersConfig:
  nodeAddresses: ["redis://10.0.0.1:6379", "redis://10.0.0.2:6379"]
  password: your_pwd
  scanInterval: 2000
  connectTimeout: 10000
  timeout: 3000
```
application.yml:
```yaml
spring:
  redisson:
    file: classpath:redisson-cluster.yaml
```

## 3) Observability
- Enable Actuator and Prometheus registry; scrape `/actuator/prometheus`
- Use Grafana dashboards for `mq_*`, `retention_*`, `reliability_*` metrics

## 4) Rollout Checklist
- Health checks green; Redis connection stable
- Consumer group assignments balanced; pending scans/claims configured
- DLQ replay policy confirmed; alerting on growth
