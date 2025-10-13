package io.github.cuihairu.redis.streaming.starter.processor;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ServiceChangeListener 注解处理器
 * <p>
 * 扫描带有 @ServiceChangeListener 注解的方法，并自动注册为服务变更监听器
 * <p>
 * 支持的方法签名：
 * <pre>
 * // 完整参数
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List&lt;ServiceInstance&gt; allInstances)
 *
 * // 简化参数 - 只关心实例
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(ServiceInstance instance)
 *
 * // 简化参数 - 只关心动作和实例
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(ServiceChangeAction action, ServiceInstance instance)
 *
 * // 使用 String action
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(String serviceName, String action, ServiceInstance instance, List&lt;ServiceInstance&gt; allInstances)
 * </pre>
 */
@Slf4j
public class ServiceChangeListenerProcessor implements BeanPostProcessor {

    private final ServiceDiscovery serviceDiscovery;

    public ServiceChangeListenerProcessor(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);

        ReflectionUtils.doWithMethods(targetClass, method -> {
            ServiceChangeListener annotation = AnnotationUtils.findAnnotation(method, ServiceChangeListener.class);
            if (annotation != null) {
                registerServiceChangeListener(bean, method, annotation);
            }
        });

        return bean;
    }

    private void registerServiceChangeListener(Object bean, Method method, ServiceChangeListener annotation) {
        String[] services = annotation.services();
        String[] actions = annotation.actions();
        Set<String> serviceSet = new HashSet<>(Arrays.asList(services));
        Set<String> actionSet = new HashSet<>(Arrays.asList(actions));

        // 创建监听器适配器
        io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener listener =
                (serviceName, action, instance, allInstances) -> {
                    // 过滤服务名称
                    if (!serviceSet.isEmpty() && !serviceSet.contains(serviceName)) {
                        return;
                    }

                    // 过滤动作类型
                    String actionName = action.name().toLowerCase();
                    if (!actionSet.isEmpty() && !contains(actionSet, actionName)) {
                        return;
                    }

                    // 调用目标方法
                    try {
                        invokeListenerMethod(bean, method, serviceName, action, instance, allInstances);
                    } catch (Exception e) {
                        log.error("Failed to invoke service change listener method: {}", method.getName(), e);
                    }
                };

        // 注册到 ServiceDiscovery (为每个服务或全局订阅)
        if (serviceSet.isEmpty()) {
            // 如果没有指定服务，订阅所有服务（这需要特殊处理，暂时订阅到 "*"）
            log.warn("Global service listener not fully supported yet, consider specifying services explicitly");
        } else {
            for (String serviceName : serviceSet) {
                serviceDiscovery.subscribe(serviceName, listener);
                log.info("Registered service change listener: {} on bean: {} for service: {}, actions: {}",
                        method.getName(), bean.getClass().getSimpleName(),
                        serviceName, actionSet.isEmpty() ? "all" : actionSet);
            }
        }
    }

    /**
     * 灵活调用监听方法，支持多种参数组合
     */
    private void invokeListenerMethod(Object bean, Method method, String serviceName,
                                       ServiceChangeAction action, ServiceInstance instance,
                                       List<ServiceInstance> allInstances) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];

            if (paramType == String.class) {
                // 第一个 String 是 serviceName，第二个 String 是 action
                if (i == 0 || (i > 0 && paramTypes[0] != String.class)) {
                    args[i] = serviceName;
                } else {
                    args[i] = action.name().toLowerCase();
                }
            } else if (paramType == ServiceChangeAction.class) {
                args[i] = action;
            } else if (paramType == ServiceInstance.class) {
                args[i] = instance;
            } else if (paramType == List.class) {
                args[i] = allInstances;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported parameter type: " + paramType + " in method: " + method.getName());
            }
        }

        ReflectionUtils.makeAccessible(method);
        method.invoke(bean, args);
    }

    /**
     * 检查集合中是否包含指定字符串（忽略大小写）
     */
    private boolean contains(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }
}
