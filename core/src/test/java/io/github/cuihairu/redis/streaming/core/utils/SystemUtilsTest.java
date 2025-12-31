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

    @Test
    public void testHostnameIsNotEmpty() {
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname);
        assertFalse(hostname.trim().isEmpty(), "Hostname should not be blank");
    }

    @Test
    public void testHostnameIsConsistent() {
        String hostname1 = SystemUtils.getLocalHostname();
        String hostname2 = SystemUtils.getLocalHostname();
        String hostname3 = SystemUtils.getLocalHostname();

        assertEquals(hostname1, hostname2, "Hostname should be consistent");
        assertEquals(hostname2, hostname3, "Hostname should be consistent");
    }

    @Test
    public void testClearCacheBeforeGet() {
        // 清除初始缓存
        SystemUtils.clearHostnameCache();

        // 获取主机名
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname);
        assertFalse(hostname.isEmpty());
    }

    @Test
    public void testMultipleClearCache() {
        String hostname1 = SystemUtils.getLocalHostname();

        // 多次清除缓存
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();

        // 仍然可以获取主机名
        String hostname2 = SystemUtils.getLocalHostname();
        assertEquals(hostname1, hostname2);
    }

    @Test
    public void testHostnameContainsNoWhitespace() {
        String hostname = SystemUtils.getLocalHostname();
        assertFalse(hostname.contains(" "), "Hostname should not contain spaces");
        assertFalse(hostname.contains("\t"), "Hostname should not contain tabs");
        assertFalse(hostname.contains("\n"), "Hostname should not contain newlines");
    }

    @Test
    public void testGetLocalHostnameReturnsValidCharacters() {
        String hostname = SystemUtils.getLocalHostname();
        // 主机名通常包含字母、数字、连字符和点
        assertTrue(hostname.matches("[a-zA-Z0-9.-]+"), "Hostname should contain valid characters");
    }

    @Test
    public void testClearCacheDoesNotAffectSubsequentCalls() {
        // 获取主机名
        String hostname1 = SystemUtils.getLocalHostname();

        // 清除缓存
        SystemUtils.clearHostnameCache();

        // 再次获取
        String hostname2 = SystemUtils.getLocalHostname();

        // 应该仍然相同（实际主机名没有变化）
        assertEquals(hostname1, hostname2);

        // 第三次调用应该从缓存获取
        String hostname3 = SystemUtils.getLocalHostname();
        assertEquals(hostname2, hostname3);
    }

    @Test
    public void testHostnameLengthIsReasonable() {
        String hostname = SystemUtils.getLocalHostname();
        // RFC 1035 规定主机名最长 253 字符
        assertTrue(hostname.length() <= 253, "Hostname should not exceed 253 characters");
        assertTrue(hostname.length() > 0, "Hostname should not be empty");
    }

    @Test
    public void testCacheThreadSafety() throws InterruptedException {
        // 清除初始缓存
        SystemUtils.clearHostnameCache();

        final String[] hostnames = new String[10];
        Thread[] threads = new Thread[10];

        // 创建多个线程同时获取主机名
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                hostnames[index] = SystemUtils.getLocalHostname();
            });
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证所有线程获取的主机名相同
        for (int i = 1; i < 10; i++) {
            assertEquals(hostnames[0], hostnames[i], "All threads should get the same hostname");
        }
    }

    @Test
    public void testClearCacheAndRepopulate() {
        // 获取并缓存主机名
        String hostname1 = SystemUtils.getLocalHostname();

        // 清除缓存
        SystemUtils.clearHostnameCache();

        // 获取新主机名（应该重新调用系统API）
        String hostname2 = SystemUtils.getLocalHostname();

        // 应该相同
        assertEquals(hostname1, hostname2);
    }

    @Test
    public void testHostnameIsNotNullAfterClear() {
        SystemUtils.clearHostnameCache();
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname);
    }

    @Test
    public void testMultipleGetCallsWithoutClear() {
        String hostname = SystemUtils.getLocalHostname();

        // 多次调用不应该改变结果
        for (int i = 0; i < 100; i++) {
            assertEquals(hostname, SystemUtils.getLocalHostname());
        }
    }

    @Test
    public void testClearCacheIsIdempotent() {
        SystemUtils.getLocalHostname(); // 填充缓存

        // 多次清除缓存不应该抛出异常
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();

        // 仍然可以获取主机名
        assertNotNull(SystemUtils.getLocalHostname());
    }

    @Test
    public void testHostnameDoesNotChange() {
        String hostname = SystemUtils.getLocalHostname();

        // 等待一小段时间
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // 忽略
        }

        String hostname2 = SystemUtils.getLocalHostname();
        assertEquals(hostname, hostname2, "Hostname should not change over time");
    }

    @Test
    public void testGetLocalHostnamePerformance() {
        // 第一次调用会填充缓存
        SystemUtils.clearHostnameCache();
        long start1 = System.nanoTime();
        SystemUtils.getLocalHostname();
        long end1 = System.nanoTime();

        // 后续调用应该更快（使用缓存）
        long start2 = System.nanoTime();
        SystemUtils.getLocalHostname();
        long end2 = System.nanoTime();

        // 第二次调用应该明显更快
        assertTrue((end2 - start2) < (end1 - start1), "Cached call should be faster");
    }

    @Test
    public void testHostnameFormat() {
        String hostname = SystemUtils.getLocalHostname();
        // 主机名通常格式
        assertNotNull(hostname);
        assertFalse(hostname.isEmpty());
        // 不以点开头或结尾
        assertFalse(hostname.startsWith("."));
        assertFalse(hostname.endsWith("."));
        // 不以连字符开头或结尾
        assertFalse(hostname.startsWith("-"));
        assertFalse(hostname.endsWith("-"));
    }

    @Test
    public void testGetLocalHostnameReturnsString() {
        String hostname = SystemUtils.getLocalHostname();
        assertTrue(hostname instanceof String, "getLocalHostname should return String");
    }

    @Test
    public void testClearCacheWhenCacheIsNull() {
        // 确保缓存为空
        SystemUtils.clearHostnameCache();

        // 再次清除不应该抛出异常
        assertDoesNotThrow(() -> SystemUtils.clearHostnameCache());
    }
}