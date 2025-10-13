package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册中心集成测试套件
 * 测试与 Redis 的真实交互
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RegistryIntegrationTest {

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
        // 清理测试数据避免污染
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
    @DisplayName("测试基础注册流程")
    public void testBasicRegistration() throws InterruptedException {
        // 创建并注册临时实例
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .ephemeral(true)
                .build();

        serviceProvider.register(instance);

        // 等待注册完成
        Thread.sleep(500);

        // 发现服务
        List<ServiceInstance> instances = serviceConsumer.discover("test-service");
        assertEquals(1, instances.size());

        ServiceInstance discovered = instances.get(0);
        assertEquals("test-service", discovered.getServiceName());
        assertEquals("test-001", discovered.getInstanceId());
        assertEquals("192.168.1.100", discovered.getHost());
        assertEquals(8080, discovered.getPort());

        // 注销实例
        serviceProvider.deregister(instance);
        Thread.sleep(500);

        // 验证已注销
        instances = serviceConsumer.discover("test-service");
        assertEquals(0, instances.size());
    }

    @Test
    @Order(2)
    @DisplayName("测试多实例注册")
    public void testMultipleInstanceRegistration() throws InterruptedException {
        // 注册多个实例
        for (int i = 1; i <= 5; i++) {
            ServiceInstance instance = DefaultServiceInstance.builder()
                    .serviceName("multi-service")
                    .instanceId("instance-" + i)
                    .host("192.168.1." + (100 + i))
                    .port(8080 + i)
                    .weight(i)
                    .build();
            serviceProvider.register(instance);
        }

        Thread.sleep(500);

        // 验证所有实例都已注册
        List<ServiceInstance> instances = serviceConsumer.discover("multi-service");
        assertEquals(5, instances.size());

        // 验证权重设置
        for (ServiceInstance instance : instances) {
            String id = instance.getInstanceId();
            int expectedWeight = Integer.parseInt(id.substring(id.lastIndexOf("-") + 1));
            assertEquals(expectedWeight, instance.getWeight());
        }

        // 清理
        for (ServiceInstance instance : instances) {
            serviceProvider.deregister(instance);
        }
    }

    @Test
    @Order(3)
    @DisplayName("测试临时实例 vs 永久实例")
    public void testEphemeralVsPersistentInstances() throws InterruptedException {
        // 注册临时实例
        ServiceInstance ephemeralInstance = DefaultServiceInstance.builder()
                .serviceName("mixed-service")
                .instanceId("ephemeral-001")
                .host("192.168.1.100")
                .port(8080)
                .ephemeral(true)
                .build();

        // 注册永久实例
        ServiceInstance persistentInstance = DefaultServiceInstance.builder()
                .serviceName("mixed-service")
                .instanceId("persistent-001")
                .host("192.168.1.101")
                .port(8081)
                .ephemeral(false)
                .build();

        serviceProvider.register(ephemeralInstance);
        serviceProvider.register(persistentInstance);

        Thread.sleep(500);

        // 验证两个实例都注册成功
        List<ServiceInstance> instances = serviceConsumer.discover("mixed-service");
        assertEquals(2, instances.size());

        // 验证 ephemeral 属性
        for (ServiceInstance instance : instances) {
            if (instance.getInstanceId().startsWith("ephemeral")) {
                assertTrue(instance.isEphemeral());
            } else if (instance.getInstanceId().startsWith("persistent")) {
                assertFalse(instance.isEphemeral());
            }
        }

        // 清理
        serviceProvider.deregister(ephemeralInstance);
        serviceProvider.deregister(persistentInstance);
    }

    @Test
    @Order(4)
    @DisplayName("测试心跳更新")
    public void testHeartbeat() throws InterruptedException {
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("heartbeat-service")
                .instanceId("hb-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 发送心跳
        for (int i = 0; i < 3; i++) {
            serviceProvider.sendHeartbeat(instance);
            Thread.sleep(100);
        }

        // 验证实例仍然活跃
        List<ServiceInstance> instances = serviceConsumer.discoverHealthy("heartbeat-service");
        assertEquals(1, instances.size());

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(5)
    @DisplayName("测试服务变更通知")
    public void testServiceChangeNotification() throws InterruptedException {
        CountDownLatch addLatch = new CountDownLatch(1);
        CountDownLatch removeLatch = new CountDownLatch(1);
        AtomicInteger changeCount = new AtomicInteger(0);

        // 订阅服务变更
        ServiceChangeListener listener = (serviceName, action, instance, allInstances) -> {
            changeCount.incrementAndGet();
            System.out.println("Service change: " + action + " - " + instance.getInstanceId());

            if (action == ServiceChangeAction.ADDED) {
                addLatch.countDown();
            } else if (action == ServiceChangeAction.REMOVED) {
                removeLatch.countDown();
            }
        };

        serviceConsumer.subscribe("notification-service", listener);

        // 注册实例（触发 ADDED 通知）
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("notification-service")
                .instanceId("notif-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        serviceProvider.register(instance);
        boolean addReceived = addLatch.await(3, TimeUnit.SECONDS);
        assertTrue(addReceived, "Should receive ADDED notification");

        // 注销实例（触发 REMOVED 通知）
        serviceProvider.deregister(instance);
        boolean removeReceived = removeLatch.await(3, TimeUnit.SECONDS);
        assertTrue(removeReceived, "Should receive REMOVED notification");

        // 验证收到了2个通知
        assertTrue(changeCount.get() >= 2);
    }

    @Test
    @Order(6)
    @DisplayName("测试元数据管理")
    public void testMetadataManagement() throws InterruptedException {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("metadata-service")
                .instanceId("meta-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 发现服务并验证元数据
        List<ServiceInstance> instances = serviceConsumer.discover("metadata-service");
        assertEquals(1, instances.size());

        ServiceInstance discovered = instances.get(0);
        assertNotNull(discovered.getMetadata());
        assertEquals("1.0.0", discovered.getMetadata().get("version"));
        assertEquals("us-east-1", discovered.getMetadata().get("region"));
        assertEquals("zone-a", discovered.getMetadata().get("zone"));

        // 清理
        serviceProvider.deregister(instance);
    }

    @Test
    @Order(7)
    @DisplayName("测试实例重复注册（覆盖）")
    public void testInstanceReRegistration() throws InterruptedException {
        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("reregister-service")
                .instanceId("same-id")
                .host("192.168.1.100")
                .port(8080)
                .weight(1)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("reregister-service")
                .instanceId("same-id")  // 相同的 instanceId
                .host("192.168.1.101")  // 不同的 host
                .port(8081)              // 不同的 port
                .weight(5)               // 不同的 weight
                .build();

        // 第一次注册
        serviceProvider.register(instance1);
        Thread.sleep(500);

        List<ServiceInstance> instances = serviceConsumer.discover("reregister-service");
        assertEquals(1, instances.size());
        assertEquals("192.168.1.100", instances.get(0).getHost());

        // 第二次注册（覆盖）
        serviceProvider.register(instance2);
        Thread.sleep(500);

        instances = serviceConsumer.discover("reregister-service");
        assertEquals(1, instances.size());
        ServiceInstance discovered = instances.get(0);
        assertEquals("192.168.1.101", discovered.getHost());
        assertEquals(8081, discovered.getPort());
        assertEquals(5, discovered.getWeight());

        // 清理
        serviceProvider.deregister(instance2);
    }

    @Test
    @Order(8)
    @DisplayName("测试不同协议的实例")
    public void testMultipleProtocols() throws InterruptedException {
        ServiceInstance httpInstance = DefaultServiceInstance.builder()
                .serviceName("protocol-service")
                .instanceId("http-001")
                .host("192.168.1.100")
                .port(80)
                .protocol(StandardProtocol.HTTP)
                .build();

        ServiceInstance grpcInstance = DefaultServiceInstance.builder()
                .serviceName("protocol-service")
                .instanceId("grpc-001")
                .host("192.168.1.101")
                .port(9090)
                .protocol(StandardProtocol.GRPC)
                .build();

        serviceProvider.register(httpInstance);
        serviceProvider.register(grpcInstance);
        Thread.sleep(500);

        // 发现所有实例
        List<ServiceInstance> instances = serviceConsumer.discover("protocol-service");
        assertEquals(2, instances.size());

        // 验证协议
        boolean hasHttp = false;
        boolean hasGrpc = false;
        for (ServiceInstance instance : instances) {
            if (instance.getProtocol().equals(StandardProtocol.HTTP)) {
                hasHttp = true;
            } else if (instance.getProtocol().equals(StandardProtocol.GRPC)) {
                hasGrpc = true;
            }
        }
        assertTrue(hasHttp && hasGrpc);

        // 清理
        serviceProvider.deregister(httpInstance);
        serviceProvider.deregister(grpcInstance);
    }

    @Test
    @Order(9)
    @DisplayName("测试最后一个实例注销后的清理")
    public void testCleanupAfterLastInstanceDeregistration() throws InterruptedException {
        String serviceName = "cleanup-service";

        // 注册唯一实例
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName(serviceName)
                .instanceId("only-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        serviceProvider.register(instance);
        Thread.sleep(500);

        // 验证服务存在
        List<ServiceInstance> instances = serviceConsumer.discover(serviceName);
        assertEquals(1, instances.size());

        // 注销最后一个实例
        serviceProvider.deregister(instance);
        Thread.sleep(500);

        // 验证服务索引被清理
        instances = serviceConsumer.discover(serviceName);
        assertEquals(0, instances.size());
    }

    @Test
    @Order(10)
    @DisplayName("测试并发注册")
    public void testConcurrentRegistration() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 多线程并发注册
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // 等待信号一起开始

                    ServiceInstance instance = DefaultServiceInstance.builder()
                            .serviceName("concurrent-service")
                            .instanceId("concurrent-" + index)
                            .host("192.168.1." + (100 + index))
                            .port(8080 + index)
                            .build();

                    serviceProvider.register(instance);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // 发出开始信号
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All threads should complete");

        Thread.sleep(1000); // 等待所有注册完成

        // 验证所有实例都注册成功
        List<ServiceInstance> instances = serviceConsumer.discover("concurrent-service");
        assertEquals(threadCount, instances.size());

        // 清理
        for (ServiceInstance instance : instances) {
            serviceProvider.deregister(instance);
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
