package io.github.cuihairu.redis.streaming.starter.annotation;

import java.lang.annotation.*;

/**
 * 服务变更监听器注解
 * 标记方法为服务变更事件处理器
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ServiceChangeListener {
    
    /**
     * 监听的服务名称
     */
    String[] services() default {};
    
    /**
     * 监听的变更类型
     */
    String[] actions() default {"added", "removed", "updated"};
}