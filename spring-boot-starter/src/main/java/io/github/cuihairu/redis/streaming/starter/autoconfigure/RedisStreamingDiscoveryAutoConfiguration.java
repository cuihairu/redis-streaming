package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.cuihairu.redis.streaming.registry.NamingService;
import io.github.cuihairu.redis.streaming.registry.ServiceDiscovery;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Service-discovery scoped auto-configuration beans (extracted from {@link RedisStreamingAutoConfiguration}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "redis-streaming.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamingDiscoveryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean({NamingService.class, ServiceDiscovery.class})
    public ServiceDiscovery serviceDiscovery(RedissonClient redissonClient, RedisStreamingProperties properties) {
        log.info("Initializing ServiceDiscovery with healthy-only: {}",
                properties.getDiscovery().isHealthyOnly());

        ServiceDiscovery discovery = new RedisNamingService(redissonClient);
        discovery.start();
        return discovery;
    }
}
