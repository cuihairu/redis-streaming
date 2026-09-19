package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Config-center scoped auto-configuration beans (extracted from {@link RedisStreamingAutoConfiguration}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "redis-streaming.config", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamingConfigServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigService configService(RedissonClient redissonClient, RedisStreamingProperties properties) {
        log.info("Initializing ConfigService with default group: {}",
                properties.getConfig().getDefaultGroup());

        var cfgProps = properties.getConfig();
        io.github.cuihairu.redis.streaming.config.ConfigServiceConfig cfg =
                new io.github.cuihairu.redis.streaming.config.ConfigServiceConfig(
                        cfgProps.getKeyPrefix(), cfgProps.isEnableKeyPrefix());
        cfg.setHistorySize(cfgProps.getHistorySize());
        ConfigService configService = new RedisConfigService(redissonClient, cfg);
        configService.start();
        return configService;
    }
}
