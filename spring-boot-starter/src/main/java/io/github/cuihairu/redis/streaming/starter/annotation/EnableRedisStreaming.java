package io.github.cuihairu.redis.streaming.starter.annotation;

import org.springframework.context.annotation.Import;
import io.github.cuihairu.redis.streaming.starter.autoconfigure.RedisStreamingAutoConfiguration;

import java.lang.annotation.*;

/**
 * 启用Redis Streaming框架
 * 自动配置服务注册发现和配置管理功能
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RedisStreamingAutoConfiguration.class)
public @interface EnableRedisStreaming {

    /**
     * 是否启用服务注册
     */
    boolean registry() default true;

    /**
     * 是否启用服务发现
     */
    boolean discovery() default true;

    /**
     * 是否启用配置服务
     */
    boolean config() default true;
}