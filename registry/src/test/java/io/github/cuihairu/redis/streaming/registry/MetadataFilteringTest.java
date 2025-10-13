package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试根据 metadata 过滤查询服务实例
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MetadataFilteringTest {

    private static RedissonClient redissonClient;
    private RedisServiceProvider serviceProvider;
    private RedisServiceConsumer serviceConsumer;
    private RedisNamingService namingService;

    @BeforeAll
    public static void setupRedis() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    public static void teardownRedis() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    public void setup() {
        // 清理测试数据
        redissonClient.getKeys().flushdb();

        serviceProvider = new RedisServiceProvider(redissonClient);
        serviceConsumer = new RedisServiceConsumer(redissonClient);
        namingService = new RedisNamingService(redissonClient);

        serviceProvider.start();
        serviceConsumer.start();
        namingService.start();
    }

    @AfterEach
    public void cleanup() {
        if (serviceProvider != null) {
            serviceProvider.stop();
        }
        if (serviceConsumer != null) {
            serviceConsumer.stop();
        }
        if (namingService != null) {
            namingService.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("测试根据单个 metadata 字段过滤")
    public void testFilterBySingleMetadata() throws InterruptedException {
        // 注册 3 个实例，不同版本
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("version", "1.0.0");
        metadata1.put("region", "us-east-1");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata1)
                .build();

        Map<String, String> metadata2 = new HashMap<>();
        metadata2.put("version", "1.0.1");
        metadata2.put("region", "us-east-1");

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .metadata(metadata2)
                .build();

        Map<String, String> metadata3 = new HashMap<>();
        metadata3.put("version", "1.0.0");
        metadata3.put("region", "us-west-1");

        ServiceInstance instance3 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-3")
                .host("192.168.1.102")
                .port(8082)
                .metadata(metadata3)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        serviceProvider.register(instance3);
        Thread.sleep(500);

        // 过滤：version = "1.0.0"
        Map<String, String> filter = new HashMap<>();
        filter.put("version", "1.0.0");

        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filter);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i -> "1.0.0".equals(i.getMetadata().get("version"))));

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
        serviceProvider.deregister(instance3);
    }

    @Test
    @Order(2)
    @DisplayName("测试根据多个 metadata 字段过滤（AND 关系）")
    public void testFilterByMultipleMetadata() throws InterruptedException {
        // 注册 3 个实例
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("version", "1.0.0");
        metadata1.put("region", "us-east-1");
        metadata1.put("zone", "zone-a");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata1)
                .build();

        Map<String, String> metadata2 = new HashMap<>();
        metadata2.put("version", "1.0.0");
        metadata2.put("region", "us-east-1");
        metadata2.put("zone", "zone-b");

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .metadata(metadata2)
                .build();

        Map<String, String> metadata3 = new HashMap<>();
        metadata3.put("version", "1.0.1");
        metadata3.put("region", "us-east-1");
        metadata3.put("zone", "zone-a");

        ServiceInstance instance3 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-3")
                .host("192.168.1.102")
                .port(8082)
                .metadata(metadata3)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        serviceProvider.register(instance3);
        Thread.sleep(500);

        // 过滤：version = "1.0.0" AND zone = "zone-a"
        Map<String, String> filter = new HashMap<>();
        filter.put("version", "1.0.0");
        filter.put("zone", "zone-a");

        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filter);

        assertEquals(1, filtered.size());
        assertEquals("instance-1", filtered.get(0).getInstanceId());
        assertEquals("1.0.0", filtered.get(0).getMetadata().get("version"));
        assertEquals("zone-a", filtered.get(0).getMetadata().get("zone"));

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
        serviceProvider.deregister(instance3);
    }

    @Test
    @Order(3)
    @DisplayName("测试空 metadata 过滤条件（返回所有实例）")
    public void testFilterWithEmptyMetadata() throws InterruptedException {
        // 注册 2 个实例
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("version", "1.0.0");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata1)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        Thread.sleep(500);

        // 空过滤条件
        Map<String, String> emptyFilter = new HashMap<>();
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", emptyFilter);

        assertEquals(2, filtered.size());

        // null 过滤条件
        filtered = serviceConsumer.discoverByMetadata("test-service", null);
        assertEquals(2, filtered.size());

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
    }

    @Test
    @Order(4)
    @DisplayName("测试不匹配的 metadata 过滤（返回空列表）")
    public void testFilterWithNoMatch() throws InterruptedException {
        // 注册实例
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 过滤：version = "2.0.0"（不存在）
        Map<String, String> filter = new HashMap<>();
        filter.put("version", "2.0.0");

        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filter);

        assertEquals(0, filtered.size());

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(5)
    @DisplayName("测试通过 NamingService 使用 metadata 过滤")
    public void testFilterThroughNamingService() throws InterruptedException {
        // 注册实例
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("version", "1.0.0");
        metadata1.put("region", "us-east-1");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata1)
                .build();

        Map<String, String> metadata2 = new HashMap<>();
        metadata2.put("version", "1.0.1");
        metadata2.put("region", "us-east-1");

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .metadata(metadata2)
                .build();

        namingService.register(instance1);
        namingService.register(instance2);
        Thread.sleep(500);

        // 通过 NamingService 过滤
        Map<String, String> filter = new HashMap<>();
        filter.put("version", "1.0.0");

        List<ServiceInstance> filtered = namingService.getInstancesByMetadata("test-service", filter);

        assertEquals(1, filtered.size());
        assertEquals("instance-1", filtered.get(0).getInstanceId());

        // 测试获取健康实例
        List<ServiceInstance> healthyFiltered = namingService.getHealthyInstancesByMetadata("test-service", filter);
        assertEquals(1, healthyFiltered.size());

        // 清理
        namingService.deregister(instance1);
        namingService.deregister(instance2);
    }

    @Test
    @Order(6)
    @DisplayName("测试只返回健康且匹配的实例")
    public void testFilterHealthyInstancesOnly() throws InterruptedException {
        // 注册 2 个实例，一个健康一个不健康
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .healthy(true)
                .enabled(true)
                .metadata(metadata)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .healthy(false)  // 不健康
                .enabled(true)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        Thread.sleep(500);

        // 过滤条件
        Map<String, String> filter = new HashMap<>();
        filter.put("version", "1.0.0");

        // 获取所有匹配的实例（包括不健康的）
        List<ServiceInstance> all = serviceConsumer.discoverByMetadata("test-service", filter);
        assertEquals(2, all.size());

        // 只获取健康的匹配实例
        List<ServiceInstance> healthy = serviceConsumer.discoverHealthyByMetadata("test-service", filter);
        assertEquals(1, healthy.size());
        assertTrue(healthy.get(0).isHealthy());

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
    }
}
