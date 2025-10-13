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
 * Spring Boot Starter使用示例
 */
@Slf4j
@SpringBootApplication
@EnableRedisStreaming  // 启用Streaming框架
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
     * 监听用户服务变更
     */
    @ServiceChangeListener(services = {"user-service"})
    public void onUserServiceChange(String serviceName, String action, ServiceInstance instance, List<ServiceInstance> allInstances) {
        log.info("用户服务变更: {} - {} (当前实例数: {})", action, instance.getInstanceId(), allInstances.size());
    }
    
    /**
     * 监听数据库配置变更
     */
    @ConfigChangeListener(dataId = "database.config", group = "production")
    public void onDatabaseConfigChange(String dataId, String group, String content, String version) {
        log.info("数据库配置变更: {}:{} -> 版本 {}", group, dataId, version);
        log.info("新配置内容: {}", content);
        // 可以在这里重新加载数据库连接等
    }
}