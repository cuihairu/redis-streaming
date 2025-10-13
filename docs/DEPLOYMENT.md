# Streaming 框架部署指南

## 环境要求

### 基础环境
- Java 17+
- Redis 6.0+
- 可选: Docker, Docker Compose

## 快速部署

### 1. 使用 Docker Compose (推荐)

```bash
# 克隆项目
git clone https://github.com/cuihairu/streaming.git
cd streaming

# 启动 Redis
docker-compose up -d

# 验证
docker-compose ps
```

### 2. 手动部署 Redis

**Linux/Mac**:
```bash
# 安装 Redis
brew install redis  # Mac
apt-get install redis  # Ubuntu

# 启动
redis-server

# 验证
redis-cli ping  # 应返回 PONG
```

**Docker**:
```bash
docker run -d \
  -p 6379:6379 \
  --name redis \
  redis:7-alpine
```

## 应用部署

### 方式1: Gradle 构建

```bash
# 构建项目
./gradlew clean build

# 运行示例
./gradlew :examples:run
```

### 方式2: Spring Boot 应用

```yaml
# application.yml
streaming:
  redis:
    url: redis://localhost:6379
  registry:
    service-name: my-service
    auto-register: true
```

```java
@SpringBootApplication
@EnableStreaming
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## 生产环境部署

### Redis 高可用

#### 方式1: Redis Sentinel
```bash
# 主从复制 + Sentinel
redis-server /path/to/master.conf
redis-server /path/to/slave.conf
redis-sentinel /path/to/sentinel.conf
```

#### 方式2: Redis Cluster
```bash
# 创建集群
redis-cli --cluster create \
  127.0.0.1:7000 127.0.0.1:7001 \
  127.0.0.1:7002 127.0.0.1:7003 \
  127.0.0.1:7004 127.0.0.1:7005 \
  --cluster-replicas 1
```

### 应用配置

```yaml
streaming:
  redis:
    # Sentinel 模式
    sentinel:
      master: mymaster
      nodes:
        - redis-sentinel-1:26379
        - redis-sentinel-2:26379
        - redis-sentinel-3:26379
    
    # Cluster 模式
    cluster:
      nodes:
        - redis-1:6379
        - redis-2:6379
        - redis-3:6379
  
  # 连接池配置
  pool:
    size: 50
    min-idle: 10
```

## 监控部署

### Prometheus + Grafana

```yaml
# docker-compose.yml
version: '3'
services:
  prometheus:
    image: prom/prometheus
    ports:
      - "9091:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
  
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'streaming'
    static_configs:
      - targets: ['localhost:9090']
```

## 性能调优

### JVM 参数
```bash
java -Xms2g -Xmx4g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar app.jar
```

### Redis 配置
```conf
# redis.conf
maxmemory 4gb
maxmemory-policy allkeys-lru
save 900 1
save 300 10
save 60 10000
appendonly yes
appendfsync everysec
```

## 健康检查

```bash
# Redis 健康检查
redis-cli ping

# 应用健康检查
curl http://localhost:8080/actuator/health

# 指标检查
curl http://localhost:9090/metrics
```

## 日志配置

```xml
<!-- logback.xml -->
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/streaming.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/streaming.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

## 故障排查

### 常见问题

**1. Redis 连接失败**
```bash
# 检查 Redis 状态
redis-cli ping

# 检查网络
telnet localhost 6379

# 查看日志
docker logs redis
```

**2. 内存溢出**
```bash
# 查看 Redis 内存
redis-cli info memory

# 清理过期数据
redis-cli --scan --pattern 'expired:*' | xargs redis-cli del
```

**3. 性能下降**
```bash
# 查看慢查询
redis-cli slowlog get 10

# 监控命令
redis-cli monitor
```

## 备份恢复

### Redis 备份
```bash
# RDB 备份
redis-cli save
cp /var/lib/redis/dump.rdb /backup/

# AOF 备份
cp /var/lib/redis/appendonly.aof /backup/
```

### 数据恢复
```bash
# 停止 Redis
redis-cli shutdown

# 恢复数据
cp /backup/dump.rdb /var/lib/redis/

# 启动 Redis
redis-server
```

---

**版本**: 0.1.0
**更新日期**: 2025-01-10
