package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * 命名服务接口
 * 参考 Nacos NamingService 设计，提供统一的服务注册与发现能力
 *
 * 核心概念：
 * - Service Provider（服务提供者）：注册服务实例，发送心跳维持健康状态
 * - Service Consumer（服务消费者）：查询服务实例，订阅服务变更
 *
 * <p>接口设计的两种视角：</p>
 * <pre>
 * 业务角色视角:                    技术操作视角:
 * ServiceProvider           ←→    ServiceRegistry
 * ServiceConsumer           ←→    ServiceDiscovery
 *
 * NamingService 统一接口，同时继承两种视角
 * </pre>
 *
 * This interface extends both ServiceProvider and ServiceConsumer (business role perspective),
 * as well as ServiceRegistry and ServiceDiscovery (technical operation perspective)
 * to provide a unified interface while maintaining clear separation of concerns.
 */
public interface NamingService extends ServiceProvider, ServiceConsumer, ServiceRegistry, ServiceDiscovery {
    /**
     * Get healthy instances of a service for load balancing
     * This is an alias for getInstances(serviceName, true)
     *
     * @param serviceName the name of the service to discover
     * @return list of healthy service instances
     * @throws IllegalStateException if the service is not running
     */
    default List<ServiceInstance> getHealthyInstances(String serviceName) {
        return getInstances(serviceName, true);
    }

    /**
     * 根据 metadata 过滤获取服务实例（支持比较运算符）
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系），支持比较运算符：
     * <ul>
     *   <li>"field": "value" - 等于（默认）</li>
     *   <li>"field:==": "value" - 等于（显式）</li>
     *   <li>"field:!=": "value" - 不等于</li>
     *   <li>"field:>": "value" - 大于</li>
     *   <li>"field:>=": "value" - 大于等于</li>
     *   <li>"field:<": "value" - 小于</li>
     *   <li>"field:<=": "value" - 小于等于</li>
     * </ul>
     *
     * <p>比较规则：</p>
     * <ol>
     *   <li>优先尝试数值比较（推荐用于 weight, age, cpu 等）</li>
     *   <li>失败则使用字符串比较（字典序，谨慎使用）</li>
     * </ol>
     *
     * <p>示例：</p>
     * <pre>
     * // ✅ 推荐：数值比较
     * Map&lt;String, String&gt; filters = new HashMap&lt;&gt;();
     * filters.put("weight:>=", "10");      // 权重 >= 10
     * filters.put("cpu_usage:<", "80");    // CPU使用率 < 80
     *
     * // ✅ 推荐：字符串相等
     * filters.put("region", "us-east-1");  // 精确匹配
     * filters.put("status:!=", "down");    // 状态不等于
     * </pre>
     *
     * @return 匹配的服务实例列表
     */
    List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * 根据 metadata 过滤获取健康的服务实例
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系）
     * @return 匹配且健康的服务实例列表
     */
    List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters);
}
