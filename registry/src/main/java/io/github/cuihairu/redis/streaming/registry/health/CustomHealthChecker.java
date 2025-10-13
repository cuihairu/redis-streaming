package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

/**
 * 自定义健康检查器
 * 允许用户实现自定义的健康检查逻辑
 */
public abstract class CustomHealthChecker implements HealthChecker {
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        // 首先进行基础的TCP连接检查
        if (!isPortReachable(serviceInstance)) {
            return false;
        }
        
        // 然后执行自定义的业务健康检查
        return doCheck(serviceInstance);
    }
    
    /**
     * 执行自定义的业务健康检查
     * 子类需要实现这个方法来提供具体的健康检查逻辑
     * 
     * @param serviceInstance 服务实例
     * @return true表示健康，false表示不健康
     * @throws Exception 检查过程中可能抛出的异常
     */
    protected abstract boolean doCheck(ServiceInstance serviceInstance) throws Exception;
    
    /**
     * 检查端口是否可达
     */
    private boolean isPortReachable(ServiceInstance serviceInstance) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(
                    serviceInstance.getHost(), 
                    serviceInstance.getPort()
                ), 
                3000 // 3秒超时
            );
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}