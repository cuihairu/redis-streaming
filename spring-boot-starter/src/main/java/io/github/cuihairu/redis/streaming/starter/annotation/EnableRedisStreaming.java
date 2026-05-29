package io.github.cuihairu.redis.streaming.starter.annotation;

import org.springframework.context.annotation.Import;
import io.github.cuihairu.redis.streaming.starter.autoconfigure.RedisStreamingAutoConfiguration;

import java.lang.annotation.*;

/**
 * Enable Redis Streaming framework
 * Automatically configures service registration, discovery, and configuration management
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RedisStreamingAutoConfiguration.class)
public @interface EnableRedisStreaming {

    /**
     * Whether to enable service registry
     */
    boolean registry() default true;

    /**
     * Whether to enable service discovery
     */
    boolean discovery() default true;

    /**
     * Whether to enable configuration service
     */
    boolean config() default true;
}