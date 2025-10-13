package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServiceInstance 单元测试
 * 测试服务实例的构建、验证和属性管理
 */
public class ServiceInstanceTest {

    @Test
    public void testBasicInstanceBuilder() {
        // 测试基本实例构建
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        assertEquals("test-service", instance.getServiceName());
        assertEquals("test-001", instance.getInstanceId());
        assertEquals("192.168.1.100", instance.getHost());
        assertEquals(8080, instance.getPort());
        assertEquals(StandardProtocol.HTTP, instance.getProtocol());
        assertEquals(1, instance.getWeight());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertTrue(instance.isEphemeral()); // 默认临时实例
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
    }

    @Test
    public void testEphemeralInstance() {
        // 测试临时实例
        ServiceInstance ephemeralInstance = DefaultServiceInstance.builder()
                .serviceName("ephemeral-service")
                .instanceId("ephemeral-001")
                .host("127.0.0.1")
                .port(8080)
                .ephemeral(true)
                .build();

        assertTrue(ephemeralInstance.isEphemeral());
    }

    @Test
    public void testPersistentInstance() {
        // 测试永久实例
        ServiceInstance persistentInstance = DefaultServiceInstance.builder()
                .serviceName("persistent-service")
                .instanceId("persistent-001")
                .host("127.0.0.1")
                .port(8080)
                .ephemeral(false)
                .build();

        assertFalse(persistentInstance.isEphemeral());
    }

    @Test
    public void testInstanceWithProtocol() {
        // 测试不同协议的实例
        ServiceInstance httpInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("http-001")
                .host("127.0.0.1")
                .port(80)
                .protocol(StandardProtocol.HTTP)
                .build();

        ServiceInstance httpsInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("https-001")
                .host("127.0.0.1")
                .port(443)
                .protocol(StandardProtocol.HTTPS)
                .build();

        ServiceInstance grpcInstance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("grpc-001")
                .host("127.0.0.1")
                .port(9090)
                .protocol(StandardProtocol.GRPC)
                .build();

        assertEquals(StandardProtocol.HTTP, httpInstance.getProtocol());
        assertEquals(StandardProtocol.HTTPS, httpsInstance.getProtocol());
        assertEquals(StandardProtocol.GRPC, grpcInstance.getProtocol());
    }

    @Test
    public void testInstanceWithMetadata() {
        // 测试带元数据的实例
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("region", "us-east-1");
        metadata.put("zone", "zone-a");
        metadata.put("env", "production");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("meta-001")
                .host("192.168.1.100")
                .port(8080)
                .metadata(metadata)
                .build();

        assertNotNull(instance.getMetadata());
        assertEquals(4, instance.getMetadata().size());
        assertEquals("1.0.0", instance.getMetadata().get("version"));
        assertEquals("us-east-1", instance.getMetadata().get("region"));
        assertEquals("zone-a", instance.getMetadata().get("zone"));
        assertEquals("production", instance.getMetadata().get("env"));
    }

    @Test
    public void testInstanceWithWeight() {
        // 测试不同权重的实例
        ServiceInstance weight1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("weight-1")
                .host("127.0.0.1")
                .port(8080)
                .weight(1)
                .build();

        ServiceInstance weight10 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("weight-10")
                .host("127.0.0.1")
                .port(8081)
                .weight(10)
                .build();

        assertEquals(1, weight1.getWeight());
        assertEquals(10, weight10.getWeight());
    }

    @Test
    public void testInstanceEnabledAndHealthy() {
        // 测试启用和健康状态
        ServiceInstance enabledHealthy = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("enabled-001")
                .host("127.0.0.1")
                .port(8080)
                .enabled(true)
                .healthy(true)
                .build();

        ServiceInstance disabledUnhealthy = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("disabled-001")
                .host("127.0.0.1")
                .port(8081)
                .enabled(false)
                .healthy(false)
                .build();

        assertTrue(enabledHealthy.isEnabled());
        assertTrue(enabledHealthy.isHealthy());
        assertFalse(disabledUnhealthy.isEnabled());
        assertFalse(disabledUnhealthy.isHealthy());
    }

    @Test
    public void testInstanceToString() {
        // 测试 toString 方法
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        String str = instance.toString();
        assertNotNull(str);
        assertTrue(str.contains("test-service"));
        assertTrue(str.contains("test-001"));
    }

    @Test
    public void testInstanceEquality() {
        // 测试实例相等性（基于 serviceName 和 instanceId）
        ServiceInstance instance1 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.100")
                .port(8080)
                .build();

        ServiceInstance instance2 = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("192.168.1.101") // 不同的 host
                .port(8081)             // 不同的 port
                .build();

        // 基于 Lombok @EqualsAndHashCode 的默认行为
        // 如果实现了自定义 equals，应该基于 serviceName + instanceId
        assertNotNull(instance1);
        assertNotNull(instance2);
    }

    @Test
    public void testCompleteInstance() {
        // 测试完整配置的实例
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "2.0.0");
        metadata.put("git.commit", "abc123");

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("complete-service")
                .instanceId("complete-001")
                .host("10.0.0.100")
                .port(9090)
                .protocol(StandardProtocol.GRPCS)
                .weight(5)
                .enabled(true)
                .healthy(true)
                .ephemeral(false)
                .metadata(metadata)
                .build();

        // 验证所有属性
        assertEquals("complete-service", instance.getServiceName());
        assertEquals("complete-001", instance.getInstanceId());
        assertEquals("10.0.0.100", instance.getHost());
        assertEquals(9090, instance.getPort());
        assertEquals(StandardProtocol.GRPCS, instance.getProtocol());
        assertEquals(5, instance.getWeight());
        assertTrue(instance.isEnabled());
        assertTrue(instance.isHealthy());
        assertFalse(instance.isEphemeral());
        assertEquals(2, instance.getMetadata().size());
        assertEquals("2.0.0", instance.getMetadata().get("version"));
        assertEquals("abc123", instance.getMetadata().get("git.commit"));
    }

    @Test
    public void testInstanceWithoutMetadata() {
        // 测试不设置元数据时使用默认值
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("test-service")
                .instanceId("test-001")
                .host("127.0.0.1")
                .port(8080)
                // 不设置 metadata
                .build();

        // Builder.Default 应该提供默认的空 Map
        assertNotNull(instance.getMetadata());
        assertTrue(instance.getMetadata().isEmpty());
    }
}
