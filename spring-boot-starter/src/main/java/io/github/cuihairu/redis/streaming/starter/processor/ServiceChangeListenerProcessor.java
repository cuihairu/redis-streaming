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
 * ServiceChangeListener annotation processor
 * <p>
 * Scans methods annotated with @ServiceChangeListener and automatically registers them as service change listeners
 * <p>
 * Supported method signatures:
 * <pre>
 * // Full parameters
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List&lt;ServiceInstance&gt; allInstances)
 *
 * // Simplified parameters - only cares about the instance
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(ServiceInstance instance)
 *
 * // Simplified parameters - only cares about the action and instance
 * @ServiceChangeListener(services = {"user-service"})
 * void onServiceChange(ServiceChangeAction action, ServiceInstance instance)
 *
 * // Using String action
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

        // Create listener adapter
        io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener listener =
                (serviceName, action, instance, allInstances) -> {
                    // Filter by service name
                    if (!serviceSet.isEmpty() && !serviceSet.contains(serviceName)) {
                        return;
                    }

                    // Filter by action type
                    String actionName = action.name().toLowerCase();
                    if (!actionSet.isEmpty() && !contains(actionSet, actionName)) {
                        return;
                    }

                    // Invoke target method
                    try {
                        invokeListenerMethod(bean, method, serviceName, action, instance, allInstances);
                    } catch (Exception e) {
                        log.error("Failed to invoke service change listener method: {}", method.getName(), e);
                    }
                };

        // Register to ServiceDiscovery (for each service or global subscription)
        if (serviceSet.isEmpty()) {
            // If no service is specified, subscribe to all services (requires special handling, currently subscribes to "*" as a workaround)
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
     * Flexibly invoke listener methods, supporting multiple parameter combinations
     */
    private void invokeListenerMethod(Object bean, Method method, String serviceName,
                                       ServiceChangeAction action, ServiceInstance instance,
                                       List<ServiceInstance> allInstances) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];

            if (paramType == String.class) {
                // The first String is serviceName, the second String is action
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
     * Check if the set contains the specified string (case-insensitive)
     */
    private boolean contains(Set<String> set, String value) {
        return set.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }
}
