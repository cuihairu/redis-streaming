package io.github.cuihairu.redis.streaming.registry;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务实例的默认实现
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultServiceInstance implements ServiceInstance {
    
    /**
     * 服务名称
     */
    private String serviceName;
    
    /**
     * 实例ID
     */
    private String instanceId;
    
    /**
     * 主机地址
     */
    private String host;
    
    /**
     * 端口号
     */
    private int port;
    
    /**
     * 协议
     */
    @Builder.Default
    private Protocol protocol = StandardProtocol.HTTP;
    
    /**
     * 是否启用
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * 是否健康
     */
    @Builder.Default
    private boolean healthy = true;
    
    /**
     * 权重
     */
    @Builder.Default
    private int weight = 1;
    
    /**
     * 元数据
     */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * 最后心跳时间
     */
    private LocalDateTime lastHeartbeatTime;
    
    /**
     * 注册时间
     */
    @Builder.Default
    private LocalDateTime registrationTime = LocalDateTime.now();

    /**
     * 是否为临时实例
     * true: 临时实例，依赖客户端心跳，超时自动删除
     * false: 永久实例，服务端健康检查，只标记不删除
     */
    @Builder.Default
    private boolean ephemeral = true;

    @Override
    public int getPort() {
        return this.port > 0 ? this.port : getProtocol().getDefaultPort();
    }

    @Override
    public Protocol getProtocol() {
        return this.protocol != null ? this.protocol : StandardProtocol.HTTP;
    }
}