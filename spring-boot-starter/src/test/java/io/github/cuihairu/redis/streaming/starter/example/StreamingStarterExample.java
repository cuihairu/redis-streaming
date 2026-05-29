package io.github.cuihairu.redis.streaming.starter.example;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.starter.annotation.EnableRedisStreaming;
import io.github.cuihairu.redis.streaming.starter.annotation.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.starter.annotation.ConfigChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Spring Boot Starter usage example
 */
@Slf4j
@SpringBootApplication
@EnableRedisStreaming  // Enable Streaming framework
@RestController
public class StreamingStarterExample {
    
    public static void main(String[] args) {
        SpringApplication.run(StreamingStarterExample.class, args);
    }
    
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
    
    /**
     * Listen for user service changes
     */
    @ServiceChangeListener(services = {"user-service"})
    public void onUserServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
        log.info("用户服务变更: {} - {} (当前实例数: {})", action, instance.getInstanceId(), allInstances.size());
    }
    
    /**
     * Listen for database configuration changes
     */
    @ConfigChangeListener(dataId = "database.config", group = "production")
    public void onDatabaseConfigChange(String dataId, String group, String content, String version) {
        log.info("数据库配置变更: {}:{} -> 版本 {}", group, dataId, version);
        log.info("新配置内容: {}", content);
        // You can reload database connections here, etc.
    }
}