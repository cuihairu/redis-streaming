package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import java.util.List;
import java.util.Map;

/**
 * 服务发现接口
 * 提供服务实例的发现和监听功能
 */
public interface ServiceDiscovery {

    /**
     * 发现服务实例
     *
     * @param serviceName 服务名称
     * @return 服务实例列表
     */
    List<ServiceInstance> discover(String serviceName);

    /**
     * 发现健康的服务实例
     *
     * @param serviceName 服务名称
     * @return 健康的服务实例列表
     */
    List<ServiceInstance> discoverHealthy(String serviceName);

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
     *   <li>"field:&lt;": "value" - 小于</li>
     *   <li>"field:&lt;=": "value" - 小于等于</li>
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
     * filters.put("cpu_usage:&lt;", "80");    // CPU使用率 &lt; 80
     *
     * // ✅ 推荐：字符串相等
     * filters.put("region", "us-east-1");  // 精确匹配
     * filters.put("status:!=", "down");    // 状态不等于
     * </pre>
     *
     * @return 匹配的服务实例列表
     */
    List<ServiceInstance> discoverByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * 根据 metadata 过滤获取健康的服务实例
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系）
     * @return 匹配且健康的服务实例列表
     */
    List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters);

    /**
     * 订阅服务变更
     *
     * @param serviceName 服务名称
     * @param listener 服务变更监听器
     */
    void subscribe(String serviceName, ServiceChangeListener listener);

    /**
     * 取消订阅服务变更
     *
     * @param serviceName 服务名称
     * @param listener 服务变更监听器
     */
    void unsubscribe(String serviceName, ServiceChangeListener listener);

    /**
     * 启动服务发现
     */
    void start();

    /**
     * 停止服务发现
     */
    void stop();

    /**
     * 检查服务发现是否正在运行
     *
     * @return 如果正在运行返回true，否则返回false
     */
    boolean isRunning();
}