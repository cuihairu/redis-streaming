package io.github.cuihairu.redis.streaming.registry;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 服务实例信息接口
 * 继承ServiceIdentity，包含服务实例的完整信息
 */
public interface ServiceInstance extends ServiceIdentity {
    /**
     * 获取主机地址
     */
    String getHost();
    
    /**
     * 获取端口号
     */
    default int getPort(){
        return getProtocol().getDefaultPort();
    }
    
    /**
     * 获取元数据
     */
    Map<String, String> getMetadata();
    
    /**
     * 是否启用
     */
    boolean isEnabled();
    
    /**
     * 是否健康
     */
    boolean isHealthy();
    
    /**
     * 是否使用安全连接(HTTPS)
     */
    default boolean isSecure() {
        return getProtocol().isSecure();
    }
    
    /**
     * 获取协议信息
     */
    default Protocol getProtocol() {
        return StandardProtocol.HTTP;
    }
    
    /**
     * 获取服务URI
     */
    default URI getUri() {
        try {
            Protocol protocol = getProtocol();
            return new URI(protocol.getName(), null, getHost(), getPort(), null, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("Invalid service instance URI", e);
        }
    }
    
    /**
     * 获取协议方案
     */
    default String getScheme() {
        return getProtocol().getName();
    }
    
    /**
     * 获取权重（用于负载均衡）
     */
    default int getWeight() {
        return 1;
    }
    
    /**
     * 获取最后心跳时间
     */
    default LocalDateTime getLastHeartbeatTime() {
        return null;
    }
    
    /**
     * 设置最后心跳时间
     */
    default void setLastHeartbeatTime(LocalDateTime time) {
        // 默认空实现，具体实现类可以重写
    }
    
    /**
     * 获取注册时间
     */
    default LocalDateTime getRegistrationTime() {
        return null;
    }

    /**
     * 是否为临时实例
     * <p>
     * 临时实例 (ephemeral=true):
     * - 依赖客户端心跳保活
     * - 心跳超时后自动删除
     * - 适用于动态微服务、容器化应用
     * - 默认: 15秒无心跳标记不健康，30秒删除
     * <p>
     * 永久实例 (ephemeral=false):
     * - 服务端主动健康检查
     * - 失败时只标记不健康，不删除
     * - 需要手动注销才能删除
     * - 适用于数据库、DNS等固定服务
     *
     * @return true表示临时实例（默认），false表示永久实例
     */
    default boolean isEphemeral() {
        return true;  // 默认为临时实例，与Nacos保持一致
    }
}