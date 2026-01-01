package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * InstanceIdGenerator工具类的单元测试
 */
public class InstanceIdGeneratorTest {

    @Test
    public void testGenerateInstanceIdWithHost() {
        // 测试带主机地址的实例ID生成
        String serviceName = "user-service";
        String host = "192.168.1.100";
        int port = 8080;
        
        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("user-service-192.168.1.100:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithLocalHost() {
        // 测试使用本地主机名的实例ID生成
        String serviceName = "order-service";
        int port = 9090;
        
        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();
        assertEquals(serviceName + "-" + hostname + ":" + port, instanceId);
    }

    @Test
    public void testGenerateLocalInstanceId() {
        // 测试生成本地实例ID
        String serviceName = "payment-service";
        int port = 8888;

        String instanceId = InstanceIdGenerator.generateLocalInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();
        assertEquals(hostname + ":" + port, instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithIPv6Address() {
        // 测试使用 IPv6 地址的实例ID生成
        String serviceName = "api-gateway";
        String host = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
        int port = 8080;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("api-gateway-2001:0db8:85a3:0000:0000:8a2e:0370:7334:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithLocalhost() {
        // 测试使用 localhost 的实例ID生成
        String serviceName = "local-service";
        String host = "localhost";
        int port = 3000;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("local-service-localhost:3000", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithEmptyServiceName() {
        // 测试服务名为空的情况
        String serviceName = "";
        String host = "192.168.1.1";
        int port = 8080;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("-192.168.1.1:8080", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithSpecialCharacters() {
        // 测试服务名包含特殊字符的情况
        String serviceName = "my_service.v2";
        String host = "example.com";
        int port = 443;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("my_service.v2-example.com:443", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithZeroPort() {
        // 测试端口为 0 的情况
        String serviceName = "test-service";
        String host = "127.0.0.1";
        int port = 0;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("test-service-127.0.0.1:0", instanceId);
    }

    @Test
    public void testGenerateInstanceIdWithMaxPort() {
        // 测试最大端口号
        String serviceName = "test-service";
        String host = "10.0.0.1";
        int port = 65535;

        String instanceId = InstanceIdGenerator.generateInstanceId(serviceName, host, port);
        assertEquals("test-service-10.0.0.1:65535", instanceId);
    }

    @Test
    public void testGenerateInstanceIdFormatConsistency() {
        // 测试不同参数组合下的格式一致性
        String serviceName = "my-service";
        String host1 = "192.168.1.1";
        String host2 = "192.168.1.2";
        int port = 8080;

        String id1 = InstanceIdGenerator.generateInstanceId(serviceName, host1, port);
        String id2 = InstanceIdGenerator.generateInstanceId(serviceName, host2, port);

        // 验证格式一致：serviceName-host:port
        assertTrue(id1.matches("^[\\w.-]+-[\\w.:]+:\\d+$"));
        assertTrue(id2.matches("^[\\w.-]+-[\\w.:]+:\\d+$"));
        assertNotEquals(id1, id2);
    }

    @Test
    public void testGenerateInstanceIdUniqueness() {
        // 测试不同参数生成不同的实例ID
        String serviceName = "unique-service";

        String id1 = InstanceIdGenerator.generateInstanceId(serviceName, "host1", 8080);
        String id2 = InstanceIdGenerator.generateInstanceId(serviceName, "host1", 8081);
        String id3 = InstanceIdGenerator.generateInstanceId(serviceName, "host2", 8080);

        assertNotEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertNotEquals(id2, id3);
    }

    @Test
    public void testGenerateLocalInstanceIdWithoutServiceNamePrefix() {
        // 测试 generateLocalInstanceId 不包含服务名前缀
        String serviceName = "ignored-service";
        int port = 9999;

        String instanceId = InstanceIdGenerator.generateLocalInstanceId(serviceName, port);
        String hostname = SystemUtils.getLocalHostname();

        // 验证格式：hostname:port (不包含服务名)
        assertFalse(instanceId.startsWith(serviceName + "-"));
        assertEquals(hostname + ":" + port, instanceId);
    }
}