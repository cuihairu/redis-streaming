package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

/**
 * 健康检查器接口
 * 定义健康检查的标准方法
 */
public interface HealthChecker {
    /**
     * 检查服务实例的健康状态
     * 
     * @param serviceInstance 服务实例
     * @return true表示健康，false表示不健康
     * @throws Exception 检查过程中可能抛出的异常
     */
    boolean check(ServiceInstance serviceInstance) throws Exception;
}