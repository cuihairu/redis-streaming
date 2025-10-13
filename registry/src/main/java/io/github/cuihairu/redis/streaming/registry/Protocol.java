package io.github.cuihairu.redis.streaming.registry;

/**
 * 协议接口
 * 定义服务实例支持的协议规范
 */
public interface Protocol {
    /**
     * 获取协议名称
     */
    String getName();
    
    /**
     * 是否使用安全连接
     */
    boolean isSecure();
    
    /**
     * 获取默认端口
     */
    int getDefaultPort();
    
    /**
     * 获取协议描述
     */
    default String getDescription() {
        return getName();
    }
}