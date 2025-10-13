package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metadata 存储方式专项测试
 * 验证注册、心跳、读取三个环节的 metadata 存储一致性
 *
 * 注意：当前使用 JSON 字符串格式存储 metadata 和 metrics，而非 metadata_* 前缀字段
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MetadataStorageTest {

    private static RedissonClient redissonClient;
    private RedisServiceProvider serviceProvider;
    private RedisServiceConsumer serviceConsumer;

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
        serviceProvider.start();
        serviceConsumer.start();
    }

    @AfterEach
    public void cleanup() {
        if (serviceProvider != null) {
            serviceProvider.stop();
        }
        if (serviceConsumer != null) {
            serviceConsumer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("测试注册时 metadata 以 JSON 存储")
    public void testMetadataStorageDuringRegistration() throws InterruptedException {
        // 准备带 metadata 的实例
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");
        metadata.put("env", "production");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("metadata-test-service")
                .instanceId("meta-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        // 注册实例
        serviceProvider.register(instance);
        Thread.sleep(500);

        // 直接从 Redis 读取，验证存储格式（使用StringCodec匹配Lua脚本）
        String instanceKey = "redis_streaming_registry:services:metadata-test-service:instance:meta-001";
        RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
        Map<String, String> redisData = instanceMap.readAllMap();

        // 验证：metadata 应该作为 JSON 字符串存储
        assertTrue(redisData.containsKey("metadata"),
                "Should have 'metadata' field as JSON string");

        // 验证：metadata 是 JSON 格式
        String metadataJson = redisData.get("metadata");
        assertNotNull(metadataJson);
        assertTrue(metadataJson.contains("version"), "metadata JSON should contain 'version'");
        assertTrue(metadataJson.contains("1.0.0"), "metadata JSON should contain version value");
        assertTrue(metadataJson.contains("region"), "metadata JSON should contain 'region'");
        assertTrue(metadataJson.contains("us-east-1"), "metadata JSON should contain region value");

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(2)
    @DisplayName("测试心跳更新时 metadata 和 metrics 分离")
    public void testMetadataConsistencyAfterHeartbeat() throws InterruptedException {
        // 注册实例
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("heartbeat-test-service")
                .instanceId("hb-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 第一次验证：注册后的存储格式
        String instanceKey = "redis_streaming_registry:services:heartbeat-test-service:instance:hb-001";
        RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
        Map<String, String> redisDataAfterRegistration = instanceMap.readAllMap();

        // 验证注册后有 metadata 字段
        assertTrue(redisDataAfterRegistration.containsKey("metadata"),
                "Should have 'metadata' field as JSON after registration");

        // 发送心跳（可能触发 metrics 更新）
        serviceProvider.sendHeartbeat(instance);
        Thread.sleep(500);

        // 第二次验证：心跳后的存储格式
        Map<String, String> redisDataAfterHeartbeat = instanceMap.readAllMap();

        // 验证：应该有 metadata 字段（JSON 格式）
        assertTrue(redisDataAfterHeartbeat.containsKey("metadata"),
                "Should have 'metadata' field after heartbeat");

        // 验证：如果有 metrics 更新，应该也是 JSON 格式
        if (redisDataAfterHeartbeat.containsKey("metrics")) {
            String metricsJson = redisDataAfterHeartbeat.get("metrics");
            assertNotNull(metricsJson);
            // metrics 应该是 JSON 格式
            assertTrue(metricsJson.startsWith("{") || metricsJson.startsWith("["),
                    "metrics should be JSON format");
        }

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(3)
    @DisplayName("测试读取实例时 metadata 正确解析")
    public void testMetadataParsingWhenDiscovering() throws InterruptedException {
        // 注册带复杂 metadata 的实例
        Map<String, String> originalMetadata = new HashMap<>();
        originalMetadata.put("version", "2.5.1");
        originalMetadata.put("region", "ap-southeast-1");
        originalMetadata.put("zone", "zone-b");
        originalMetadata.put("datacenter", "dc-01");
        originalMetadata.put("rack", "rack-05");
        originalMetadata.put("build.number", "12345");
        originalMetadata.put("git.commit", "abc123def456");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("discovery-test-service")
                .instanceId("disc-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(originalMetadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 通过服务发现读取实例
        List<ServiceInstance> discoveredInstances = serviceConsumer.discover("discovery-test-service");
        assertEquals(1, discoveredInstances.size());

        ServiceInstance discoveredInstance = discoveredInstances.get(0);
        Map<String, String> discoveredMetadata = discoveredInstance.getMetadata();

        // 验证：读取到的 metadata 完整
        assertNotNull(discoveredMetadata);
        assertEquals(originalMetadata.size(), discoveredMetadata.size(),
                "All metadata fields should be retrieved");

        // 验证：每个字段的值正确
        for (Map.Entry<String, String> entry : originalMetadata.entrySet()) {
            assertTrue(discoveredMetadata.containsKey(entry.getKey()),
                    "Should contain metadata key: " + entry.getKey());
            assertEquals(entry.getValue(), discoveredMetadata.get(entry.getKey()),
                    "Metadata value should match for key: " + entry.getKey());
        }

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(4)
    @DisplayName("测试 metadata 为空时的处理")
    public void testEmptyMetadata() throws InterruptedException {
        // 注册不带 metadata 的实例
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("empty-meta-service")
                .instanceId("empty-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 验证 Redis 中不应该有 metadata_ 字段
        String instanceKey = "redis_streaming_registry:services:empty-meta-service:instance:empty-001";
        RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
        Map<String, String> redisData = instanceMap.readAllMap();

        long metadataFieldCount = redisData.keySet().stream()
                .filter(key -> key.startsWith("metadata_"))
                .count();

        assertEquals(0, metadataFieldCount,
                "Should have no metadata_ fields when metadata is empty");

        // 验证服务发现时 metadata 为空 Map
        List<ServiceInstance> discoveredInstances = serviceConsumer.discover("empty-meta-service");
        assertEquals(1, discoveredInstances.size());

        ServiceInstance discoveredInstance = discoveredInstances.get(0);
        assertNotNull(discoveredInstance.getMetadata());
        assertTrue(discoveredInstance.getMetadata().isEmpty());

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(5)
    @DisplayName("测试 metadata 部分更新")
    public void testPartialMetadataUpdate() throws InterruptedException {
        // 注册实例
        Map<String, String> initialMetadata = new HashMap<>();
        initialMetadata.put("version", "1.0.0");
        initialMetadata.put("load", "0.3");
        initialMetadata.put("status", "healthy");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("update-test-service")
                .instanceId("update-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(initialMetadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 更新 metadata（通过重新注册）
        Map<String, String> updatedMetadata = new HashMap<>();
        updatedMetadata.put("version", "1.0.0"); // 保持不变
        updatedMetadata.put("load", "0.8"); // 更新
        updatedMetadata.put("status", "busy"); // 更新

        ServiceInstance updatedInstance = DefaultServiceInstance.builder()
                .serviceName("update-test-service")
                .instanceId("update-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(updatedMetadata)
                .build();

        serviceProvider.register(updatedInstance); // 覆盖注册
        Thread.sleep(500);

        // 通过服务发现读取
        List<ServiceInstance> discoveredInstances = serviceConsumer.discover("update-test-service");
        assertEquals(1, discoveredInstances.size());

        ServiceInstance discoveredInstance = discoveredInstances.get(0);
        Map<String, String> finalMetadata = discoveredInstance.getMetadata();

        // 验证：更新成功
        assertEquals("1.0.0", finalMetadata.get("version"), "version should remain unchanged");
        assertEquals("0.8", finalMetadata.get("load"), "load should be updated");
        assertEquals("busy", finalMetadata.get("status"), "status should be updated");

        // 清理
        serviceProvider.deregister(updatedInstance);
    }

    @Test
    @Order(6)
    @DisplayName("测试 metadata 特殊字符处理")
    public void testMetadataWithSpecialCharacters() throws InterruptedException {
        // 包含特殊字符的 metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("app.name", "my-application");
        metadata.put("server.port", "8080");
        metadata.put("spring.profiles.active", "production");
        metadata.put("url", "http://example.com:8080/api");
        metadata.put("description", "Service with: colons, commas, and spaces");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("special-char-service")
                .instanceId("special-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 通过服务发现验证（不再检查 Redis 内部格式）
        List<ServiceInstance> discoveredInstances = serviceConsumer.discover("special-char-service");
        assertEquals(1, discoveredInstances.size());

        ServiceInstance discoveredInstance = discoveredInstances.get(0);
        Map<String, String> discoveredMetadata = discoveredInstance.getMetadata();

        // 验证特殊字符正确保留
        assertEquals("my-application", discoveredMetadata.get("app.name"));
        assertEquals("8080", discoveredMetadata.get("server.port"));
        assertEquals("production", discoveredMetadata.get("spring.profiles.active"));
        assertEquals("http://example.com:8080/api", discoveredMetadata.get("url"));
        assertEquals("Service with: colons, commas, and spaces",
                discoveredMetadata.get("description"));

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(7)
    @DisplayName("测试多实例 metadata 隔离")
    public void testMetadataIsolationBetweenInstances() throws InterruptedException {
        // 注册两个实例，metadata 不同
        Map<String, String> metadata1 = new HashMap<>();
        metadata1.put("version", "1.0.0");
        metadata1.put("instance", "first");

        Map<String, String> metadata2 = new HashMap<>();
        metadata2.put("version", "2.0.0");
        metadata2.put("instance", "second");
        metadata2.put("extra", "data");

        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("isolation-service")
                .instanceId("isolation-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata1)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("isolation-service")
                .instanceId("isolation-002")
                .host("192.168.1.101")
                .port(8081)
                .metadata(metadata2)
                .build();

        serviceProvider.register(instance1);
        serviceProvider.register(instance2);
        Thread.sleep(500);

        // 发现所有实例
        List<ServiceInstance> instances = serviceConsumer.discover("isolation-service");
        assertEquals(2, instances.size());

        // 验证每个实例的 metadata 独立且正确
        for (ServiceInstance instance : instances) {
            if (instance.getInstanceId().equals("isolation-001")) {
                assertEquals(2, instance.getMetadata().size());
                assertEquals("1.0.0", instance.getMetadata().get("version"));
                assertEquals("first", instance.getMetadata().get("instance"));
                assertFalse(instance.getMetadata().containsKey("extra"));
            } else if (instance.getInstanceId().equals("isolation-002")) {
                assertEquals(3, instance.getMetadata().size());
                assertEquals("2.0.0", instance.getMetadata().get("version"));
                assertEquals("second", instance.getMetadata().get("instance"));
                assertEquals("data", instance.getMetadata().get("extra"));
            }
        }

        // 清理
        serviceProvider.deregister(instance1);
        serviceProvider.deregister(instance2);
    }

    @Test
    @Order(8)
    @DisplayName("测试 metadata 大量字段")
    public void testMetadataWithManyFields() throws InterruptedException {
        // 创建包含大量字段的 metadata
        Map<String, String> metadata = new HashMap<>();
        for (int i = 1; i <= 50; i++) {
            metadata.put("field_" + i, "value_" + i);
        }

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("many-fields-service")
                .instanceId("many-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 通过服务发现验证（不再检查 Redis 内部存储格式）
        List<ServiceInstance> discoveredInstances = serviceConsumer.discover("many-fields-service");
        assertEquals(1, discoveredInstances.size());

        ServiceInstance discoveredInstance = discoveredInstances.get(0);
        Map<String, String> discoveredMetadata = discoveredInstance.getMetadata();

        // 验证所有字段都能正确读取
        assertEquals(50, discoveredMetadata.size(), "Should retrieve all 50 metadata fields");

        // 抽样验证几个字段
        assertEquals("value_1", discoveredMetadata.get("field_1"));
        assertEquals("value_25", discoveredMetadata.get("field_25"));
        assertEquals("value_50", discoveredMetadata.get("field_50"));

        // 清理
        serviceProvider.deregister(instance);
    }
}
