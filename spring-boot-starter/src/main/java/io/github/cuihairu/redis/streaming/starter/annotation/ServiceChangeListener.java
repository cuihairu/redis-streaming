package io.github.cuihairu.redis.streaming.starter.annotation;

import java.lang.annotation.*;

/**
 * Service change listener annotation
 * Marks a method as a service change event handler
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ServiceChangeListener {
    
    /**
     * Service names to listen to
     */
    String[] services() default {};

    /**
     * Change types to listen to
     */
    String[] actions() default {"added", "removed", "updated"};
}