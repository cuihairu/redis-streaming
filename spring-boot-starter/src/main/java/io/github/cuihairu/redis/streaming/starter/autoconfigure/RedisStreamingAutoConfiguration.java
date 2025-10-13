package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.streaming.config.ConfigService;
import io.github.cuihairu.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.starter.processor.ServiceChangeListenerProcessor;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis Streaming框架自动配置
 * 根据配置按需加载服务注册发现和配置管理功能
 */
@Slf4j
@Configuration
@ConditionalOnClass({RedissonClient.class})
@EnableConfigurationProperties(RedisStreamingProperties.class)
public class RedisStreamingAutoConfiguration {

    /**
     * 创建RedissonClient
     *
     * 注意：如果项目中已经配置了 redisson-spring-boot-starter，
     * 该Bean会被跳过（@ConditionalOnMissingBean），使用项目的RedissonClient配置。
     *
     * 这里提供的是简化的单机配置，适合快速开发和测试。
     * 生产环境建议使用 redisson-spring-boot-starter 提供完整的集群/哨兵/SSL等配置。
     */
    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(RedisStreamingProperties properties) {
        Config config = new Config();
        RedisStreamingProperties.RedisProperties redis = properties.getRedis();

        // 简化配置，仅支持单机模式
        // 完整配置请使用 redisson-spring-boot-starter
        config.useSingleServer()
                .setAddress(redis.getAddress())
                .setPassword(redis.getPassword())
                .setDatabase(redis.getDatabase())
                .setConnectTimeout(redis.getConnectTimeout())
                .setTimeout(redis.getTimeout())
                .setConnectionPoolSize(redis.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize());

        log.info("Initializing RedissonClient with address: {} (Simple single-server mode)", redis.getAddress());
        log.info("For production with cluster/sentinel, use redisson-spring-boot-starter");
        return Redisson.create(config);
    }

    /**
     * 服务注册配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.registry", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RegistryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public NamingService namingService(RedissonClient redissonClient, RedisStreamingProperties properties) {
            log.info("Initializing NamingService with heartbeat interval: {}s",
                    properties.getRegistry().getHeartbeatInterval());

            NamingService registry = new RedisNamingService(redissonClient);
            registry.start();
            return registry;
        }

        /**
         * 注册 ServiceChangeListener 注解处理器
         * 自动扫描并注册带有 @ServiceChangeListener 注解的方法
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(NamingService.class)
        public ServiceChangeListenerProcessor serviceChangeListenerProcessor(NamingService namingService) {
            log.info("Initializing ServiceChangeListenerProcessor for @ServiceChangeListener annotation");
            // NamingService 继承了 ServiceConsumer，ServiceConsumer 继承了 ServiceDiscovery
            return new ServiceChangeListenerProcessor((ServiceDiscovery) namingService);
        }
    }

    /**
     * 服务发现配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class DiscoveryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ServiceDiscovery serviceDiscovery(RedissonClient redissonClient, RedisStreamingProperties properties) {
            log.info("Initializing ServiceDiscovery with healthy-only: {}",
                    properties.getDiscovery().isHealthyOnly());

            ServiceDiscovery discovery = new RedisNamingService(redissonClient);
            discovery.start();
            return discovery;
        }
    }

    /**
     * 配置服务配置
     */
    @Configuration
    @ConditionalOnProperty(prefix = "redis-streaming.config", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ConfigServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ConfigService configService(RedissonClient redissonClient, RedisStreamingProperties properties) {
            log.info("Initializing ConfigService with default group: {}",
                    properties.getConfig().getDefaultGroup());

            ConfigService configService = new RedisConfigService(redissonClient);
            configService.start();
            return configService;
        }
    }
}