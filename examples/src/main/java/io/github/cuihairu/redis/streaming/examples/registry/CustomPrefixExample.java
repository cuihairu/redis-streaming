package io.github.cuihairu.redis.streaming.examples.registry;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.impl.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 自定义Redis键前缀示例
 * 演示如何使用自定义前缀来避免Redis键冲突
 */
public class CustomPrefixExample {
    
    public static void main(String[] args) throws Exception {
        // 创建Redis客户端
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redissonClient = Redisson.create(config);
        
        try {
            // 创建自定义前缀的配置
            NamingServiceConfig registryConfig = new NamingServiceConfig("myapp");
            
            // 创建命名服务实例
            NamingService namingService = new RedisNamingService(redissonClient, registryConfig);
            
            // 启动服务
            namingService.start();
            
            // 注册服务实例
            ServiceInstance instance = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-1")
                    .host("192.168.1.100")
                    .port(8080)
                    .build();
            
            namingService.register(instance);
            
            // 发现服务实例
            var instances = namingService.getAllInstances("user-service");
            System.out.println("发现服务实例数量: " + instances.size());
            
            // 检查Redis中的键
            System.out.println("Redis键前缀: " + registryConfig.getKeyPrefix());
            System.out.println("启用前缀: " + registryConfig.isEnableKeyPrefix());
            
            // 发送心跳
            namingService.sendHeartbeat(instance);
            
            // 注销服务实例
            namingService.deregister(instance);
            
            System.out.println("自定义前缀示例执行完成");
            
        } finally {
            redissonClient.shutdown();
        }
    }
}