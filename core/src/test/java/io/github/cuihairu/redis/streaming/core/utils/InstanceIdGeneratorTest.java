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
}