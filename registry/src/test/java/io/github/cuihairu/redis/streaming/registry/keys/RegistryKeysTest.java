package io.github.cuihairu.redis.streaming.registry.keys;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RegistryKeys 单元测试
 * 测试注册中心 Key 生成、验证和解析功能
 */
public class RegistryKeysTest {

    @Test
    public void testDefaultConstructor() {
        RegistryKeys keys = new RegistryKeys();
        assertEquals("registry", keys.getKeyPrefix());
    }

    @Test
    public void testCustomPrefix() {
        RegistryKeys keys = new RegistryKeys("custom_prefix");
        assertEquals("custom_prefix", keys.getKeyPrefix());
    }

    @Test
    public void testNullPrefixFallbackToDefault() {
        RegistryKeys keys = new RegistryKeys(null);
        assertEquals("registry", keys.getKeyPrefix());
    }

    @Test
    public void testEmptyPrefixFallbackToDefault() {
        RegistryKeys keys = new RegistryKeys("   ");
        assertEquals("registry", keys.getKeyPrefix());
    }

    @Test
    public void testGetServicesIndexKey() {
        RegistryKeys keys = new RegistryKeys("test");
        String servicesKey = keys.getServicesIndexKey();
        assertEquals("test:services", servicesKey);
    }

    @Test
    public void testGetServiceHeartbeatsKey() {
        RegistryKeys keys = new RegistryKeys("test");
        String heartbeatsKey = keys.getServiceHeartbeatsKey("my-service");
        assertEquals("test:services:my-service:heartbeats", heartbeatsKey);
    }

    @Test
    public void testGetServiceInstanceKey() {
        RegistryKeys keys = new RegistryKeys("test");
        String instanceKey = keys.getServiceInstanceKey("my-service", "instance-001");
        assertEquals("test:services:my-service:instance:instance-001", instanceKey);
    }

    @Test
    public void testGetServiceChangeChannelKey() {
        RegistryKeys keys = new RegistryKeys("test");
        String channelKey = keys.getServiceChangeChannelKey("my-service");
        assertEquals("test:services:my-service:changes", channelKey);
    }

    @Test
    public void testValidateServiceNameThrowsOnNull() {
        RegistryKeys keys = new RegistryKeys();
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceHeartbeatsKey(null);
        });
    }

    @Test
    public void testValidateServiceNameThrowsOnEmpty() {
        RegistryKeys keys = new RegistryKeys();
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceHeartbeatsKey("   ");
        });
    }

    @Test
    public void testValidateServiceNameThrowsOnColon() {
        RegistryKeys keys = new RegistryKeys();
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceHeartbeatsKey("invalid:service");
        });
    }

    @Test
    public void testValidateInstanceIdThrowsOnNull() {
        RegistryKeys keys = new RegistryKeys();
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceInstanceKey("my-service", null);
        });
    }

    @Test
    public void testValidateInstanceIdThrowsOnEmpty() {
        RegistryKeys keys = new RegistryKeys();
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceInstanceKey("my-service", "   ");
        });
    }

    @Test
    public void testValidateInstanceIdThrowsOnColon() {
        RegistryKeys keys = new RegistryKeys();
        // instanceId 包含冒号应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            keys.getServiceInstanceKey("my-service", "192.168.1.1:8080");
        });
    }

    @Test
    public void testSanitizeInstanceId() {
        // 测试实例 ID 清理
        assertEquals("192.168.1.1_8080", RegistryKeys.sanitizeInstanceId("192.168.1.1:8080"));
        assertEquals("instance-001", RegistryKeys.sanitizeInstanceId("instance-001"));
        assertEquals("test-instance-name", RegistryKeys.sanitizeInstanceId("test instance\tname"));
        assertEquals("multi--line", RegistryKeys.sanitizeInstanceId("multi\n\rline"));
    }

    @Test
    public void testSanitizeInstanceIdWithNull() {
        assertNull(RegistryKeys.sanitizeInstanceId(null));
    }

    @Test
    public void testValidateAndSanitizeInstanceId() {
        // 安全的 ID 直接返回
        String safeId = "instance-001";
        assertEquals(safeId, RegistryKeys.validateAndSanitizeInstanceId(safeId));

        // 不安全的 ID 返回清理后的版本
        String unsafeId = "192.168.1.1:8080";
        String sanitized = RegistryKeys.validateAndSanitizeInstanceId(unsafeId);
        assertEquals("192.168.1.1_8080", sanitized);
        assertFalse(sanitized.contains(":"));
    }

    @Test
    public void testValidateAndSanitizeInstanceIdThrowsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            RegistryKeys.validateAndSanitizeInstanceId(null);
        });
    }

    @Test
    public void testValidateAndSanitizeInstanceIdThrowsOnEmpty() {
        assertThrows(IllegalArgumentException.class, () -> {
            RegistryKeys.validateAndSanitizeInstanceId("   ");
        });
    }

    @Test
    public void testIsInstanceIdSafe() {
        // 安全的 ID
        assertTrue(RegistryKeys.isInstanceIdSafe("instance-001"));
        assertTrue(RegistryKeys.isInstanceIdSafe("my_instance_123"));
        assertTrue(RegistryKeys.isInstanceIdSafe("192.168.1.1_8080"));

        // 不安全的 ID
        assertFalse(RegistryKeys.isInstanceIdSafe("192.168.1.1:8080"));
        assertFalse(RegistryKeys.isInstanceIdSafe("instance 001"));
        assertFalse(RegistryKeys.isInstanceIdSafe("instance\t001"));
        assertFalse(RegistryKeys.isInstanceIdSafe("instance\n001"));
        assertFalse(RegistryKeys.isInstanceIdSafe(null));
    }

    @Test
    public void testIsRegistryKey() {
        RegistryKeys keys = new RegistryKeys("test");

        assertTrue(keys.isRegistryKey("test:services"));
        assertTrue(keys.isRegistryKey("test:services:my-service:heartbeats"));
        assertTrue(keys.isRegistryKey("test:services:my-service:instance:001"));

        assertFalse(keys.isRegistryKey("other:services"));
        assertFalse(keys.isRegistryKey("test"));
        assertFalse(keys.isRegistryKey(null));
    }

    @Test
    public void testExtractServiceNameFromInstanceKey() {
        RegistryKeys keys = new RegistryKeys("test");

        String instanceKey = "test:services:user-service:instance:instance-001";
        String serviceName = keys.extractServiceNameFromInstanceKey(instanceKey);
        assertEquals("user-service", serviceName);

        // 无效的 Key
        assertNull(keys.extractServiceNameFromInstanceKey("invalid:key"));
        assertNull(keys.extractServiceNameFromInstanceKey("other:services:test:instance:001"));
        assertNull(keys.extractServiceNameFromInstanceKey(null));
    }

    @Test
    public void testExtractInstanceIdFromInstanceKey() {
        RegistryKeys keys = new RegistryKeys("test");

        String instanceKey = "test:services:user-service:instance:instance-001";
        String instanceId = keys.extractInstanceIdFromInstanceKey(instanceKey);
        assertEquals("instance-001", instanceId);

        // 无效的 Key
        assertNull(keys.extractInstanceIdFromInstanceKey("invalid:key"));
        assertNull(keys.extractInstanceIdFromInstanceKey("other:services:test:instance:001"));
        assertNull(keys.extractInstanceIdFromInstanceKey(null));
    }

    @Test
    public void testExtractServiceNameFromHeartbeatsKey() {
        RegistryKeys keys = new RegistryKeys("test");

        String heartbeatsKey = "test:services:user-service:heartbeats";
        String serviceName = keys.extractServiceNameFromHeartbeatsKey(heartbeatsKey);
        assertEquals("user-service", serviceName);

        // 无效的 Key
        assertNull(keys.extractServiceNameFromHeartbeatsKey("invalid:key"));
        assertNull(keys.extractServiceNameFromHeartbeatsKey("other:services:test:heartbeats"));
        assertNull(keys.extractServiceNameFromHeartbeatsKey(null));
    }

    @Test
    public void testGetAllKeyTemplates() {
        RegistryKeys keys = new RegistryKeys();
        String[] templates = keys.getAllKeyTemplates();

        assertNotNull(templates);
        assertEquals(4, templates.length);
        // 验证包含所有注册相关模板
        assertTrue(templates[0].contains("services"));
        assertTrue(templates[1].contains("heartbeats"));
        assertTrue(templates[2].contains("instance"));
        assertTrue(templates[3].contains("changes"));
    }

    @Test
    public void testToString() {
        RegistryKeys keys = new RegistryKeys("custom");
        String str = keys.toString();

        assertNotNull(str);
        assertTrue(str.contains("custom"));
        assertTrue(str.contains("RegistryKeys"));
    }

    @Test
    public void testKeyGenerationConsistency() {
        // 测试多次调用生成相同的 Key
        RegistryKeys keys = new RegistryKeys("test");

        String key1 = keys.getServiceInstanceKey("my-service", "instance-001");
        String key2 = keys.getServiceInstanceKey("my-service", "instance-001");

        assertEquals(key1, key2);
    }

    @Test
    public void testDifferentServicesGenerateDifferentKeys() {
        RegistryKeys keys = new RegistryKeys("test");

        String key1 = keys.getServiceHeartbeatsKey("service-a");
        String key2 = keys.getServiceHeartbeatsKey("service-b");

        assertNotEquals(key1, key2);
        assertTrue(key1.contains("service-a"));
        assertTrue(key2.contains("service-b"));
    }

    @Test
    public void testDifferentInstancesGenerateDifferentKeys() {
        RegistryKeys keys = new RegistryKeys("test");

        String key1 = keys.getServiceInstanceKey("my-service", "instance-001");
        String key2 = keys.getServiceInstanceKey("my-service", "instance-002");

        assertNotEquals(key1, key2);
        assertTrue(key1.contains("instance-001"));
        assertTrue(key2.contains("instance-002"));
    }
}
