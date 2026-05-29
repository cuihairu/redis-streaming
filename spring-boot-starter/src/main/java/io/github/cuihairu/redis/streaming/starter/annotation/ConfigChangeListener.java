package io.github.cuihairu.redis.streaming.starter.annotation;

import java.lang.annotation.*;

/**
 * Configuration change listener annotation
 * Marks a method as a configuration change event handler
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigChangeListener {
    
    /**
     * Configuration data ID
     */
    String dataId();

    /**
     * Configuration group
     */
    String group() default "DEFAULT_GROUP";

    /**
     * Whether to automatically refresh configuration into the target object
     */
    boolean autoRefresh() default true;
}