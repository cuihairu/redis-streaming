package io.github.cuihairu.redis.streaming.examples.registry;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.impl.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Custom Redis key prefix example
 * Demonstrates how to use custom prefixes to avoid Redis key conflicts
 */
public class CustomPrefixExample {
    
    public static void main(String[] args) throws Exception {
        // Create Redis client
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redissonClient = Redisson.create(config);
        
        try {
            // Create configuration with custom prefix
            NamingServiceConfig registryConfig = new NamingServiceConfig("myapp");
            
            // Create naming service instance
            NamingService namingService = new RedisNamingService(redissonClient, registryConfig);
            
            // Start service
            namingService.start();
            
            // Register service instance
            ServiceInstance instance = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-1")
                    .host("192.168.1.100")
                    .port(8080)
                    .build();
            
            namingService.register(instance);
            
            // Discover service instances
            var instances = namingService.getAllInstances("user-service");
            System.out.println("发现服务实例数量: " + instances.size());
            
            // Check Redis keys
            System.out.println("Redis键前缀: " + registryConfig.getKeyPrefix());
            System.out.println("启用前缀: " + registryConfig.isEnableKeyPrefix());
            
            // Send heartbeat
            namingService.sendHeartbeat(instance);
            
            // Deregister service instance
            namingService.deregister(instance);
            
            System.out.println("自定义前缀示例执行完成");
            
        } finally {
            redissonClient.shutdown();
        }
    }
}