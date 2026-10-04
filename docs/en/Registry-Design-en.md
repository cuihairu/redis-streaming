# Registry Module Design

[中文](../Registry-Design.md) | [English](Registry-Design-en.md)

---

## Overview

Redis-based service registration and discovery. The design borrows from Nacos; storage and change notification are built on Redis data structures. Configuration-center capability lives in the separate config module (see the Chinese [config.md](../config.md)).

## Core Roles

### 1. Service Provider

Responsibilities:
- Service Registration: Register service instance info to Redis on startup
- Heartbeat Maintenance: Send periodic heartbeats to keep instance active
- Graceful Shutdown: Deregister service instance on shutdown

Redis operations:
```redis
# Service index - Set structure, holds all registered service names
SADD {prefix}:services "{serviceName}"

# Service instance info - Hash structure
HSET {prefix}:services:{serviceName}:instance:{instanceId} "host" "192.168.1.100"
HSET {prefix}:services:{serviceName}:instance:{instanceId} "port" "8080"
HSET {prefix}:services:{serviceName}:instance:{instanceId} "healthy" "true"

# Heartbeat timestamps - Sorted Set structure (score = timestamp, member = instanceId)
ZADD {prefix}:services:{serviceName}:heartbeats {timestamp} "{instanceId}"
```

### 2. Service Consumer

Responsibilities:
- Service Discovery: Query available service instance list
- Service Subscription: Listen for service change notifications
- Load Balancing: Select target instance from instance list

Redis operations:
```redis
# Service index - list all registered service names
SMEMBERS {prefix}:services

# Instance details - read the Hash of one instance
HGETALL {prefix}:services:{serviceName}:instance:{instanceId}

# Service change notifications - Pub/Sub channel
SUBSCRIBE {prefix}:services:{serviceName}:changes
```

### 3. Registry Center

Responsibilities:
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

ServiceProvider and ServiceConsumer combined in a single entry point:

```java
public interface NamingService extends ServiceProvider, ServiceConsumer {
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

Stored as JSON strings:

```redis
# Metadata (static business tags)
HSET instance_key "metadata" "{\"version\":\"1.0.0\",\"region\":\"us-east\"}"

# Metrics (dynamic monitoring data)
HSET instance_key "metrics" "{\"cpu\":45.5,\"memory\":2048,\"qps\":1000}"
```

### Lua Script Optimization

Heartbeat update script (supports separate metadata and metrics updates):
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

Filter query script (supports metadata and metrics filtering):
```lua
-- Support both metadata and metrics filtering
local metadata_match = check_filters(metadata_json, metadata_filters)
local metrics_match = check_filters(metrics_json, metrics_filters)

if metadata_match and metrics_match then
    table.insert(matched_instances, instance_id)
end
```

## Redis Key Prefix Configuration

Key templates are generated by `keys.RegistryKeys`; the default prefix is `redis_streaming_registry` (`registry.BaseRedisConfig.DEFAULT_KEY_PREFIX`):

```
{prefix}:services                                        # Set: service index (all registered service names)
{prefix}:services:{serviceName}:heartbeats               # ZSet: heartbeat index (score = last heartbeat ms, member = instanceId)
{prefix}:services:{serviceName}:instance:{instanceId}    # Hash: instance details
{prefix}:services:{serviceName}:changes                  # Pub/Sub: service change notification channel
```

### Configuration Example
```java
// Use the default prefix (redis_streaming_registry)
NamingServiceConfig config = new NamingServiceConfig();

// Use a custom prefix
NamingServiceConfig config = new NamingServiceConfig("myapp");
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

**Version**: 0.2.0
**Last Updated**: 2026-10-05

Related documentation:
- [Overall Architecture](Architecture-en.md)
- [Spring Boot Integration](Spring-Boot-Starter-en.md)
