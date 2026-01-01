# Registry Module Design

[中文](Registry-Design) | [English](Registry-Design-en)

---

## Overview

Redis-based service registration, discovery, and configuration management system. Inspired by Nacos architecture but optimized for Redis characteristics, providing a lightweight, high-performance microservice governance solution.

## Core Roles

### 1. Service Provider

**Core Responsibilities**:
- Service Registration: Register service instance info to Redis on startup
- Heartbeat Maintenance: Send periodic heartbeats to keep instance active
- Graceful Shutdown: Deregister service instance on shutdown

**Redis Implementation**:
```redis
# Service instance info - Hash structure
HSET services:{serviceName}:{instanceId} "host" "192.168.1.100"
HSET services:{serviceName}:{instanceId} "port" "8080"
HSET services:{serviceName}:{instanceId} "healthy" "true"

# Service instance list - Set structure
SADD service_instances:{serviceName} "{instanceId}"

# Heartbeat timestamp - Sorted Set structure
ZADD heartbeat:{serviceName} {timestamp} "{instanceId}"
```

### 2. Service Consumer

**Core Responsibilities**:
- Service Discovery: Query available service instance list
- Service Subscription: Listen for service change notifications
- Load Balancing: Select target instance from instance list

**Redis Implementation**:
```redis
# Service discovery - Get instance list from Set
SMEMBERS service_instances:{serviceName}
HGETALL services:{serviceName}:{instanceId}

# Service subscription - Pub/Sub channel
SUBSCRIBE service_change:{serviceName}
```

### 3. Registry Center

**Core Responsibilities**:
- Instance Management: Maintain complete lifecycle of service instances
- Health Checking: Actively detect service instance health status
- Change Notification: Push service changes to subscribers
- Data Cleanup: Automatically clean expired and unhealthy instances

## Core Interface Design

### ServiceProvider Interface

```java
public interface ServiceProvider {
    // Service registration
    void register(ServiceInstance instance);

    // Service deregistration
    void deregister(ServiceInstance instance);

    // Send heartbeat
    void sendHeartbeat(ServiceInstance instance);

    // Batch heartbeat (performance optimization)
    void batchSendHeartbeats(List<ServiceInstance> instances);

    // Lifecycle management
    void start();
    void stop();
    boolean isRunning();
}
```

### ServiceConsumer Interface

```java
public interface ServiceConsumer {
    // Service discovery
    List<ServiceInstance> getAllInstances(String serviceName);

    // Healthy instance discovery
    List<ServiceInstance> getHealthyInstances(String serviceName);

    // Service subscription
    void subscribe(String serviceName, ServiceChangeListener listener);

    // Unsubscribe
    void unsubscribe(String serviceName, ServiceChangeListener listener);

    // Lifecycle management
    void start();
    void stop();
    boolean isRunning();
}
```

### NamingService Interface

Integrates ServiceProvider and ServiceConsumer, providing unified naming service interface:

```java
public interface NamingService extends ServiceProvider, ServiceConsumer {
    // Provides unified naming service interface
}
```

## Key Implementation Details

### Heartbeat Mechanism

```java
@Scheduled(fixedDelay = 30000) // 30-second heartbeat interval
public void sendHeartbeat() {
    RScoredSortedSet<String> heartbeatSet = redisson.getScoredSortedSet(heartbeatKey);
    heartbeatSet.add(System.currentTimeMillis(), instanceId);
}

@Scheduled(fixedDelay = 60000) // Check expired instances every 60 seconds
public void removeExpiredInstances() {
    long expiredTime = System.currentTimeMillis() - 90000; // 90 seconds without heartbeat
    Collection<String> expiredInstances = heartbeatSet.valueRange(0, expiredTime);

    if (!expiredInstances.isEmpty()) {
        // Batch cleanup expired instances
        cleanupExpiredInstances(serviceName, expiredInstances);
        // Notify service changes
        notifyServiceChange(serviceName, "removed", expiredInstances);
    }
}
```

### Metadata and Metrics Storage

**Storage Format**: JSON string

```redis
# Metadata (static business tags)
HSET instance_key "metadata" "{\"version\":\"1.0.0\",\"region\":\"us-east\"}"

# Metrics (dynamic monitoring data)
HSET instance_key "metrics" "{\"cpu\":45.5,\"memory\":2048,\"qps\":1000}"
```

### Lua Script Optimization

**Heartbeat Update Script** (supports separate metadata and metrics updates):
```lua
-- Multi-mode heartbeat update
local update_mode = ARGV[3]  -- "heartbeat_only" | "metrics_update" | "metadata_update" | "full_update"

-- Always update heartbeat timestamp
redis.call('ZADD', heartbeat_key, heartbeat_time, instance_id)

-- Update different fields based on mode
if update_mode == 'metrics_update' then
    redis.call('HSET', instance_key, 'metrics', metrics_json)
end
```

**Filter Query Script** (supports metadata and metrics filtering):
```lua
-- Support both metadata and metrics filtering
local metadata_match = check_filters(metadata_json, metadata_filters)
local metrics_match = check_filters(metrics_json, metrics_filters)

if metadata_match and metrics_match then
    table.insert(matched_instances, instance_id)
end
```

## Redis Key Prefix Configuration

### Default Key Pattern
```
services:{serviceName}:{instanceId}
service_instances:{serviceName}
heartbeat:{serviceName}
service_change:{serviceName}
```

### Key Pattern with Prefix
```
{prefix}:services:{serviceName}:{instanceId}
{prefix}:service_instances:{serviceName}
{prefix}:heartbeat:{serviceName}
{prefix}:service_change:{serviceName}
```

### Configuration Example
```java
// Use default prefix
RedisRegistryConfig config = new RedisRegistryConfig();

// Use custom prefix
RedisRegistryConfig config = new RedisRegistryConfig("myapp");
```

## Protocol Support

Supports health checks for multiple protocols:

| Protocol | Health Check Method |
|----------|---------------------|
| HTTP/HTTPS | HTTP GET request |
| TCP | TCP connection test |
| gRPC | gRPC health check protocol |
| WebSocket | WebSocket connection test |

---

**Version**: 0.1.0
**Last Updated**: 2025-10-13

🔗 Related Documentation:
- [[Overall Architecture|Architecture-en]]
- [[Detailed Design|Design-en]]
- [[Spring Boot Integration|Spring-Boot-Starter-en]]
