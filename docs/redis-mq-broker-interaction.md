# Redis MQ 与 Broker (Redis) 交互设计

## 1. 概述

在 `redis-mq` 模块中，Redis 服务器本身充当消息 Broker 的角色。本设计文档将详细说明 `redis-mq` 库如何与 Redis Broker 进行交互，包括连接管理、数据持久化、高可用性以及错误处理等方面。

## 2. 连接管理

### 2.1 Redisson 客户端

*   `redis-mq` 模块将完全依赖 Redisson 客户端库来管理与 Redis Broker 的连接。Redisson 提供了高级的抽象和连接池管理功能。
*   **连接池:** Redisson 内部维护连接池，确保高效的连接复用，避免频繁创建和关闭连接的开销。
*   **配置:** Redis 连接配置（单机、哨兵、集群模式）将通过 Redisson 的配置对象进行统一管理，通常通过 `application.yml` 或编程方式提供。

### 2.2 连接模式支持

*   **单机模式:** 连接到单个 Redis 实例。
*   **哨兵模式 (Sentinel):** 支持 Redis Sentinel 架构，自动发现主节点，实现高可用性。当主节点故障时，Redisson 会自动切换到新的主节点。
*   **集群模式 (Cluster):** 支持 Redis Cluster 架构，Redisson 客户端能够感知集群拓扑变化，将请求路由到正确的槽位。

## 3. 数据持久化与可靠性

### 3.1 Redis Stream 持久化

*   Redis Stream 的数据默认存储在内存中。为了确保消息的持久化，Redis Broker 需要配置 RDB 或 AOF 持久化机制。
*   **RDB (Redis Database):** 定期将内存中的数据快照写入磁盘。
*   **AOF (Append Only File):** 记录所有写操作命令，以日志形式追加到文件末尾。AOF 提供了更高的数据安全性。
*   **`redis-mq` 库的职责:** `redis-mq` 库本身不直接控制 Redis 的持久化配置，但其设计依赖于 Redis Broker 层面正确的持久化配置来保证消息不丢失。

### 3.2 消息确认 (ACK)

*   `redis-mq` 消费者在成功处理消息后，会向 Redis Broker 发送 `XACK` 命令进行确认。
*   未确认的消息会保留在消费者组的 Pending Entry List (PEL) 中，等待重新处理或移入 DLQ。

## 4. 高可用性与故障恢复

### 4.1 Redisson 自动故障转移

*   当 Redis Broker 部署在 Sentinel 或 Cluster 模式下时，Redisson 客户端会自动处理主从切换和节点故障。
*   `redis-mq` 库将利用 Redisson 的这一特性，确保在 Broker 发生故障时，消息生产和消费能够自动恢复。

### 4.2 消费者组的弹性

*   **消费者故障:** 消费者组内的某个消费者故障时，其 PEL 中的消息可以被组内其他消费者认领 (CLAIM) 并继续处理。`redis-mq` 库将提供机制来定期检查和认领这些消息。
*   **Broker 故障恢复:** 当 Redis Broker 从故障中恢复后，`redis-mq` 库的消费者会从上次确认的位置继续消费，或者从 PEL 中恢复未处理的消息。

## 5. 错误处理与重试

### 5.1 连接错误

*   Redisson 客户端负责处理底层的网络连接错误、超时等问题，并进行自动重连。
*   `redis-mq` 库将捕获 Redisson 抛出的与 Broker 交互相关的异常，并根据配置进行重试或降级处理。

### 5.2 消息处理失败

*   如 `redis-mq` 模块设计文档所述，消息处理失败会触发重试机制。
*   重试失败后，消息将被发送到死信队列 (DLQ)，以隔离问题消息，防止阻塞主业务流程。

## 6. 性能考量

### 6.1 批量操作

*   `redis-mq` 库将考虑在生产和消费端支持批量操作，以减少网络往返次数，提高吞吐量。
*   `XADD` 和 `XREADGROUP` 命令本身支持批量操作。

### 6.2 Stream 修剪

*   `redis-mq` 库将提供配置选项，允许用户设置 Stream 的最大长度，通过 `XTRIM` 命令定期修剪，防止 Stream 无限增长导致内存耗尽。

## 7. 总结

`redis-mq` 模块通过 Redisson 客户端与 Redis Broker 紧密协作，构建了一个高可用、可靠且易于使用的消息队列系统。其设计充分利用了 Redis Stream 的特性，并考虑了生产环境中的各种挑战。
