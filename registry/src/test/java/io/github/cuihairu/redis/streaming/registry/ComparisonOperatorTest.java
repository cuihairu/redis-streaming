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
 * 测试比较运算符功能
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ComparisonOperatorTest {

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
    @DisplayName("测试数值大于运算符 (>)")
    public void testNumericGreaterThan() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("weight", "10")),
            createInstance("instance-2", Map.of("weight", "20")),
            createInstance("instance-3", Map.of("weight", "30"))
        );

        Map<String, String> filters = Map.of("weight:>", "15");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            Integer.parseInt(i.getMetadata().get("weight")) > 15));
    }

    @Test
    @Order(2)
    @DisplayName("测试数值大于等于运算符 (>=)")
    public void testNumericGreaterThanOrEqual() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("age", "18")),
            createInstance("instance-2", Map.of("age", "25")),
            createInstance("instance-3", Map.of("age", "17"))
        );

        Map<String, String> filters = Map.of("age:>=", "18");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            Integer.parseInt(i.getMetadata().get("age")) >= 18));
    }

    @Test
    @Order(3)
    @DisplayName("测试数值小于运算符 (<)")
    public void testNumericLessThan() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("cpu_usage", "50")),
            createInstance("instance-2", Map.of("cpu_usage", "70")),
            createInstance("instance-3", Map.of("cpu_usage", "90"))
        );

        Map<String, String> filters = Map.of("cpu_usage:<", "80");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            Integer.parseInt(i.getMetadata().get("cpu_usage")) < 80));
    }

    @Test
    @Order(4)
    @DisplayName("测试数值小于等于运算符 (<=)")
    public void testNumericLessThanOrEqual() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("latency", "50")),
            createInstance("instance-2", Map.of("latency", "100")),
            createInstance("instance-3", Map.of("latency", "150"))
        );

        Map<String, String> filters = Map.of("latency:<=", "100");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            Integer.parseInt(i.getMetadata().get("latency")) <= 100));
    }

    @Test
    @Order(5)
    @DisplayName("测试不等于运算符 (!=)")
    public void testNotEqual() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("status", "running")),
            createInstance("instance-2", Map.of("status", "maintenance")),
            createInstance("instance-3", Map.of("status", "running"))
        );

        Map<String, String> filters = Map.of("status:!=", "maintenance");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            !"maintenance".equals(i.getMetadata().get("status"))));
    }

    @Test
    @Order(6)
    @DisplayName("测试显式等于运算符 (==)")
    public void testExplicitEqual() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("version", "1.0.0")),
            createInstance("instance-2", Map.of("version", "2.0.0")),
            createInstance("instance-3", Map.of("version", "1.0.0"))
        );

        Map<String, String> filters = Map.of("version:==", "1.0.0");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            "1.0.0".equals(i.getMetadata().get("version"))));
    }

    @Test
    @Order(7)
    @DisplayName("测试多个运算符组合（AND 关系）")
    public void testMultipleOperators() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("weight", "10", "cpu", "50", "region", "us-east-1")),
            createInstance("instance-2", Map.of("weight", "20", "cpu", "60", "region", "us-east-1")),
            createInstance("instance-3", Map.of("weight", "30", "cpu", "40", "region", "us-west-1"))
        );

        Map<String, String> filters = new HashMap<>();
        filters.put("weight:>=", "15");
        filters.put("cpu:<", "70");
        filters.put("region", "us-east-1");

        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(1, filtered.size());
        ServiceInstance instance = filtered.get(0);
        assertEquals("instance-2", instance.getInstanceId());
        assertEquals("20", instance.getMetadata().get("weight"));
        assertEquals("60", instance.getMetadata().get("cpu"));
    }

    @Test
    @Order(8)
    @DisplayName("测试浮点数比较")
    public void testFloatingPointComparison() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("price", "9.99")),
            createInstance("instance-2", Map.of("price", "19.99")),
            createInstance("instance-3", Map.of("price", "29.99"))
        );

        Map<String, String> filters = Map.of("price:<", "25.00");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(i ->
            Double.parseDouble(i.getMetadata().get("price")) < 25.00));
    }

    @Test
    @Order(9)
    @DisplayName("测试字符串字典序比较")
    public void testLexicographicComparison() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("zone", "zone-a")),
            createInstance("instance-2", Map.of("zone", "zone-b")),
            createInstance("instance-3", Map.of("zone", "zone-c"))
        );

        // 字典序比较
        Map<String, String> filters = Map.of("zone:>", "zone-a");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().noneMatch(i ->
            "zone-a".equals(i.getMetadata().get("zone"))));
    }

    @Test
    @Order(10)
    @DisplayName("测试数字字符串按数值比较（不是字典序）")
    public void testNumericStringAsNumber() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("count", "10")),
            createInstance("instance-2", Map.of("count", "2")),
            createInstance("instance-3", Map.of("count", "100"))
        );

        // 如果是字典序："10" < "2" < "100"（错误）
        // 如果是数值序：2 < 10 < 100（正确）
        Map<String, String> filters = Map.of("count:>", "10");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(1, filtered.size());
        assertEquals("instance-3", filtered.get(0).getInstanceId());
        assertEquals("100", filtered.get(0).getMetadata().get("count"));
    }

    @Test
    @Order(11)
    @DisplayName("测试字段名包含冒号的情况")
    public void testFieldNameWithColon() throws InterruptedException {
        // 注意：这是边界情况测试
        registerInstances(
            createInstance("instance-1", Map.of("my:field", "10")),
            createInstance("instance-2", Map.of("my:field", "20"))
        );

        // 使用最后一个冒号作为分隔符
        Map<String, String> filters = Map.of("my:field:>=", "15");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(1, filtered.size());
        assertEquals("20", filtered.get(0).getMetadata().get("my:field"));
    }

    @Test
    @Order(12)
    @DisplayName("测试通过 NamingService 使用比较运算符")
    public void testOperatorsThroughNamingService() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("weight", "10")),
            createInstance("instance-2", Map.of("weight", "20")),
            createInstance("instance-3", Map.of("weight", "30"))
        );

        Map<String, String> filters = Map.of("weight:>=", "20");
        List<ServiceInstance> filtered = namingService.getInstancesByMetadata("test-service", filters);

        assertEquals(2, filtered.size());
    }

    @Test
    @Order(13)
    @DisplayName("测试健康实例过滤结合比较运算符")
    public void testHealthyInstancesWithOperators() throws InterruptedException {
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("weight", "10");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-1")
                .host("192.168.1.100")
                .port(8080)
                .healthy(true)
                .metadata(metadata1)
                .build();

        Map<String, String> metadata2 = new HashMap<>();
        metadata2.put("weight", "20");

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-2")
                .host("192.168.1.101")
                .port(8081)
                .healthy(false)  // 不健康
                .metadata(metadata2)
                .build();

        Map<String, String> metadata3 = new HashMap<>();
        metadata3.put("weight", "30");

        ServiceInstance instance3 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("instance-3")
                .host("192.168.1.102")
                .port(8082)
                .healthy(true)
                .metadata(metadata3)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        serviceProvider.register(instance3);
        Thread.sleep(500);

        Map<String, String> filters = Map.of("weight:>=", "15");

        // 所有匹配的实例（包括不健康的）
        List<ServiceInstance> all = serviceConsumer.discoverByMetadata("test-service", filters);
        assertEquals(2, all.size());

        // 只获取健康的匹配实例
        List<ServiceInstance> healthy = serviceConsumer.discoverHealthyByMetadata("test-service", filters);
        assertEquals(1, healthy.size());
        assertEquals("instance-3", healthy.get(0).getInstanceId());

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
        serviceProvider.deregister(instance3);
    }

    @Test
    @Order(14)
    @DisplayName("测试负数比较")
    public void testNegativeNumbers() throws InterruptedException {
        registerInstances(
            createInstance("instance-1", Map.of("temperature", "-10")),
            createInstance("instance-2", Map.of("temperature", "0")),
            createInstance("instance-3", Map.of("temperature", "10"))
        );

        Map<String, String> filters = Map.of("temperature:>", "0");
        List<ServiceInstance> filtered = serviceConsumer.discoverByMetadata("test-service", filters);

        assertEquals(1, filtered.size());
        assertEquals("10", filtered.get(0).getMetadata().get("temperature"));
    }

    // ==================== Helper Methods ====================

    private ServiceInstance createInstance(String instanceId, Map<String, String> metadata) {
        return DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId(instanceId)
                .host("192.168.1." + instanceId.hashCode() % 255)
                .port(8080)
                .metadata(new HashMap<>(metadata))
                .build();
    }

    private void registerInstances(ServiceInstance... instances) throws InterruptedException {
        for (ServiceInstance instance : instances) {
            serviceProvider.register(instance);
        }
        Thread.sleep(500);
    }
}
