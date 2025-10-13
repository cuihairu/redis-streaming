package io.github.cuihairu.redis.streaming.registry.listener;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import java.util.List;

/**
 * 服务变更监听器
 * 监听服务实例的增加、删除和更新事件
 */
public interface ServiceChangeListener {

    /**
     * 服务实例变更事件
     *
     * @param serviceName 服务名称
     * @param action 变更动作
     * @param instance 变更的服务实例
     * @param allInstances 当前所有服务实例
     */
    void onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances);
}