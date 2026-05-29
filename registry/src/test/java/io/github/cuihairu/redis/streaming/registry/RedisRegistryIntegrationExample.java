package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Redis registry and configuration center integration example
 * Demonstrates full functionality of service registration/discovery and configuration management
 */
@Tag("integration")
public class RedisRegistryIntegrationExample {
    
    @Test
    public void testServiceRegistryAndDiscovery() throws Exception {
        // Create Redis client
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redissonClient = Redisson.create(config);
        
        // Create service registration and discovery instances
        RedisServiceProvider serviceProvider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer serviceConsumer = new RedisServiceConsumer(redissonClient);
        
        try {
            // Start services
            serviceProvider.start();
            serviceConsumer.start();
            
            // Create service instances
            ServiceInstance instance1 = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-1")
                    .host("192.168.1.100")
                    .port(8080)
                    .weight(1)
                    .metadata(createMetadata("version", "1.0", "region", "us-east"))
                    .build();

            ServiceInstance instance2 = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-2")
                    .host("192.168.1.101")
                    .port(8080)
                    .weight(2)
                    .metadata(createMetadata("version", "1.1", "region", "us-west"))
                    .build();
            
            // Register service instances
            System.out.println("=== 注册服务实例 ===");
            serviceProvider.register(instance1);
            serviceProvider.register(instance2);
            
            // Wait for registration to complete
            Thread.sleep(1000);
            
            // Discover services
            System.out.println("=== 发现服务实例 ===");
            List<ServiceInstance> instances = serviceConsumer.discover("user-service");
            System.out.println("发现 " + instances.size() + " 个服务实例:");
            for (ServiceInstance instance : instances) {
                System.out.println("  - " + instance.getInstanceId() + " @ " + instance.getHost() + ":" + instance.getPort() + 
                                 " (权重: " + instance.getWeight() + ", 版本: " + instance.getMetadata().get("version") + ")");
            }
            
            // Subscribe to service changes
            System.out.println("=== 订阅服务变更 ===");
            CountDownLatch changeLatch = new CountDownLatch(2); // Expect 2 change events
            
            ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {
                System.out.println("服务变更通知: " + action + " - " + instance.getInstanceId() + 
                                 " (当前总数: " + allInstances.size() + ")");
                changeLatch.countDown();
            };
            
            serviceConsumer.subscribe("user-service", listener);
            
            // Send heartbeats
            System.out.println("=== 发送心跳 ===");
            serviceProvider.sendHeartbeat(instance1);
            serviceProvider.sendHeartbeat(instance2);
            
            // Deregister an instance
            System.out.println("=== 注销服务实例 ===");
            serviceProvider.deregister(instance2);

            // Add a new instance
            ServiceInstance instance3 = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-3")
                    .host("192.168.1.102")
                    .port(8080)
                    .weight(3)
                    .metadata(createMetadata("version", "2.0", "region", "eu-west"))
                    .build();
            serviceProvider.register(instance3);
            
            // Wait for change notifications
            boolean received = changeLatch.await(5, TimeUnit.SECONDS);
            System.out.println("变更通知接收状态: " + (received ? "成功" : "超时"));
            
            // Discover services again
            System.out.println("=== 变更后的服务实例 ===");
            instances = serviceConsumer.discoverHealthy("user-service");
            System.out.println("发现 " + instances.size() + " 个健康的服务实例:");
            for (ServiceInstance instance : instances) {
                System.out.println("  - " + instance.getInstanceId() + " @ " + instance.getHost() + ":" + instance.getPort() + 
                                 " (权重: " + instance.getWeight() + ", 版本: " + instance.getMetadata().get("version") + ")");
            }
            
        } finally {
            // Clean up resources
            serviceProvider.stop();
            serviceConsumer.stop();
            redissonClient.shutdown();
        }
    }
    
    @Test
    public void testConfigService() throws Exception {
        // Create Redis client
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redissonClient = Redisson.create(config);
        
        // Create configuration service
        ConfigService configService = new RedisConfigService(redissonClient);
        
        try {
            // Start configuration service
            configService.start();
            
            System.out.println("=== 配置管理演示 ===");
            
            // Publish configuration
            String dataId = "database.config";
            String group = "production";
            String configContent = "{\n" +
                    "  \"host\": \"localhost\",\n" +
                    "  \"port\": 3306,\n" +
                    "  \"database\": \"myapp\",\n" +
                    "  \"maxConnections\": 100\n" +
                    "}";
            
            System.out.println("发布配置: " + group + ":" + dataId);
            boolean published = configService.publishConfig(dataId, group, configContent, "初始数据库配置");
            System.out.println("发布结果: " + (published ? "成功" : "失败"));
            
            // Get configuration
            System.out.println("=== 获取配置 ===");
            String retrievedConfig = configService.getConfig(dataId, group);
            System.out.println("获取到的配置:");
            System.out.println(retrievedConfig);
            
            // Listen for configuration changes
            System.out.println("=== 监听配置变更 ===");
            CountDownLatch configChangeLatch = new CountDownLatch(1);
            
            ConfigChangeListener listener = (id, grp, content, version) -> {
                System.out.println("配置变更通知:");
                System.out.println("  配置ID: " + id);
                System.out.println("  配置组: " + grp);
                System.out.println("  新版本: " + version);
                System.out.println("  新内容: " + content);
                configChangeLatch.countDown();
            };
            
            configService.addListener(dataId, group, listener);
            
            // Update configuration
            String updatedConfig = "{\n" +
                    "  \"host\": \"db.example.com\",\n" +
                    "  \"port\": 3306,\n" +
                    "  \"database\": \"myapp\",\n" +
                    "  \"maxConnections\": 200\n" +
                    "}";
            
            System.out.println("=== 更新配置 ===");
            boolean updated = configService.publishConfig(dataId, group, updatedConfig, "增加最大连接数");
            System.out.println("更新结果: " + (updated ? "成功" : "失败"));
            
            // Wait for configuration change notification
            boolean received = configChangeLatch.await(5, TimeUnit.SECONDS);
            System.out.println("配置变更通知接收状态: " + (received ? "成功" : "超时"));
            
        } finally {
            configService.stop();
            redissonClient.shutdown();
        }
    }
    
    @Test
    public void testIntegratedScenario() throws Exception {
        // Integrated scenario: service registration/discovery + configuration management
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redissonClient = Redisson.create(config);
        
        RedisServiceProvider serviceProvider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer serviceConsumer = new RedisServiceConsumer(redissonClient);
        ConfigService configService = new RedisConfigService(redissonClient);
        
        try {
            System.out.println("=== 综合场景演示 ===");
            
            // Start all services
            serviceProvider.start();
            serviceConsumer.start();
            configService.start();
            
            // 1. Publish service configuration
            String serviceConfigId = "user-service.config";
            String group = "services";
            String serviceConfig = "{\n" +
                    "  \"timeout\": 5000,\n" +
                    "  \"retries\": 3,\n" +
                    "  \"loadBalancer\": \"round_robin\"\n" +
                    "}";
            
            configService.publishConfig(serviceConfigId, group, serviceConfig, "用户服务配置");
            
            // 2. Register service instance (carrying configuration info)
            Map<String, String> metadata = createMetadata(
                    "version", "1.0",
                    "configGroup", group,
                    "configDataId", serviceConfigId
            );

            ServiceInstance serviceInstance = DefaultServiceInstance.builder()
                    .serviceName("user-service")
                    .instanceId("instance-main")
                    .host("192.168.1.100")
                    .port(8080)
                    .metadata(metadata)
                    .build();
            
            serviceProvider.register(serviceInstance);
            
            // 3. Service consumer discovers services and retrieves configuration
            List<ServiceInstance> instances = serviceConsumer.discoverHealthy("user-service");
            System.out.println("发现服务实例: " + instances.size() + " 个");
            
            for (ServiceInstance instance : instances) {
                String configGroup = instance.getMetadata().get("configGroup");
                String configDataId = instance.getMetadata().get("configDataId");
                
                if (configGroup != null && configDataId != null) {
                    String instanceConfig = configService.getConfig(configDataId, configGroup);
                    System.out.println("实例 " + instance.getInstanceId() + " 的配置:");
                    System.out.println(instanceConfig);
                }
            }
            
            // 4. Simulate configuration update and service re-discovery
            String updatedServiceConfig = "{\n" +
                    "  \"timeout\": 3000,\n" +
                    "  \"retries\": 5,\n" +
                    "  \"loadBalancer\": \"weighted_round_robin\"\n" +
                    "}";
            
            configService.publishConfig(serviceConfigId, group, updatedServiceConfig, "优化超时和重试配置");
            
            System.out.println("=== 综合场景演示完成 ===");
            
        } finally {
            // Clean up resources
            serviceProvider.stop();
            serviceConsumer.stop();
            configService.stop();
            redissonClient.shutdown();
        }
    }
    
    private Map<String, String> createMetadata(String... keyValues) {
        Map<String, String> metadata = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i + 1 < keyValues.length) {
                metadata.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return metadata;
    }
}
