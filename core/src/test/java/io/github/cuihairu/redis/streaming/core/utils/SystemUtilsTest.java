package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemUtils工具类的单元测试
 */
public class SystemUtilsTest {

    @Test
    public void testGetLocalHostname() {
        // 测试获取主机名
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname, "Hostname should not be null");
        assertFalse(hostname.isEmpty(), "Hostname should not be empty");
        
        // 验证缓存机制 - 再次调用应该返回相同的值
        String hostname2 = SystemUtils.getLocalHostname();
        assertEquals(hostname, hostname2, "Hostname should be cached");
    }

    @Test
    public void testClearHostnameCache() {
        // 获取原始主机名
        String originalHostname = SystemUtils.getLocalHostname();
        
        // 清除缓存
        SystemUtils.clearHostnameCache();
        
        // 再次获取应该仍然返回相同的主机名（因为我们没有改变实际的主机名）
        String newHostname = SystemUtils.getLocalHostname();
        assertEquals(originalHostname, newHostname, "Hostname should be the same after cache clear");
    }
}