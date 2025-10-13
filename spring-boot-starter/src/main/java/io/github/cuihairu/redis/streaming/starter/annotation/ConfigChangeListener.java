package io.github.cuihairu.redis.streaming.starter.annotation;

import java.lang.annotation.*;

/**
 * 配置变更监听器注解
 * 标记方法为配置变更事件处理器
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigChangeListener {
    
    /**
     * 配置数据ID
     */
    String dataId();
    
    /**
     * 配置组
     */
    String group() default "DEFAULT_GROUP";
    
    /**
     * 是否自动刷新配置到对象
     */
    boolean autoRefresh() default true;
}