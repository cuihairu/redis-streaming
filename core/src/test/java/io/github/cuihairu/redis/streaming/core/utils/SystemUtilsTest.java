package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SystemUtils utility class
 */
public class SystemUtilsTest {

    @Test
    public void testGetLocalHostname() {
        // Test getting hostname
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname, "Hostname should not be null");
        assertFalse(hostname.isEmpty(), "Hostname should not be empty");

        // Verify caching mechanism - calling again should return the same value
        String hostname2 = SystemUtils.getLocalHostname();
        assertEquals(hostname, hostname2, "Hostname should be cached");
    }

    @Test
    public void testClearHostnameCache() {
        // Get original hostname
        String originalHostname = SystemUtils.getLocalHostname();

        // Clear cache
        SystemUtils.clearHostnameCache();

        // Getting again should still return the same hostname (since we haven't changed the actual hostname)
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
        // Clear initial cache
        SystemUtils.clearHostnameCache();

        // Get hostname
        String hostname = SystemUtils.getLocalHostname();
        assertNotNull(hostname);
        assertFalse(hostname.isEmpty());
    }

    @Test
    public void testMultipleClearCache() {
        String hostname1 = SystemUtils.getLocalHostname();

        // Clear cache multiple times
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();

        // Hostname can still be obtained
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
        // Hostnames typically contain letters, digits, hyphens, and dots
        assertTrue(hostname.matches("[a-zA-Z0-9.-]+"), "Hostname should contain valid characters");
    }

    @Test
    public void testClearCacheDoesNotAffectSubsequentCalls() {
        // Get hostname
        String hostname1 = SystemUtils.getLocalHostname();

        // Clear cache
        SystemUtils.clearHostnameCache();

        // Get again
        String hostname2 = SystemUtils.getLocalHostname();

        // Should still be the same (actual hostname hasn't changed)
        assertEquals(hostname1, hostname2);

        // Third call should get from cache
        String hostname3 = SystemUtils.getLocalHostname();
        assertEquals(hostname2, hostname3);
    }

    @Test
    public void testHostnameLengthIsReasonable() {
        String hostname = SystemUtils.getLocalHostname();
        // RFC 1035 specifies hostname max length of 253 characters
        assertTrue(hostname.length() <= 253, "Hostname should not exceed 253 characters");
        assertTrue(hostname.length() > 0, "Hostname should not be empty");
    }

    @Test
    public void testCacheThreadSafety() throws InterruptedException {
        // Clear initial cache
        SystemUtils.clearHostnameCache();

        final String[] hostnames = new String[10];
        Thread[] threads = new Thread[10];

        // Create multiple threads to get hostname simultaneously
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                hostnames[index] = SystemUtils.getLocalHostname();
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all threads got the same hostname
        for (int i = 1; i < 10; i++) {
            assertEquals(hostnames[0], hostnames[i], "All threads should get the same hostname");
        }
    }

    @Test
    public void testClearCacheAndRepopulate() {
        // Get and cache hostname
        String hostname1 = SystemUtils.getLocalHostname();

        // Clear cache
        SystemUtils.clearHostnameCache();

        // Get new hostname (should call system API again)
        String hostname2 = SystemUtils.getLocalHostname();

        // Should be the same
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

        // Multiple calls should not change the result
        for (int i = 0; i < 100; i++) {
            assertEquals(hostname, SystemUtils.getLocalHostname());
        }
    }

    @Test
    public void testClearCacheIsIdempotent() {
        SystemUtils.getLocalHostname(); // Populate cache

        // Clearing cache multiple times should not throw exceptions
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();
        SystemUtils.clearHostnameCache();

        // Hostname can still be obtained
        assertNotNull(SystemUtils.getLocalHostname());
    }

    @Test
    public void testHostnameDoesNotChange() {
        String hostname = SystemUtils.getLocalHostname();

        // Wait a short period
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore
        }

        String hostname2 = SystemUtils.getLocalHostname();
        assertEquals(hostname, hostname2, "Hostname should not change over time");
    }

    @Test
    public void testGetLocalHostnamePerformance() {
        // First call will populate cache
        SystemUtils.clearHostnameCache();
        long start1 = System.nanoTime();
        SystemUtils.getLocalHostname();
        long end1 = System.nanoTime();

        // Subsequent calls should be faster (using cache)
        long start2 = System.nanoTime();
        SystemUtils.getLocalHostname();
        long end2 = System.nanoTime();

        // Second call should be noticeably faster
        assertTrue((end2 - start2) < (end1 - start1), "Cached call should be faster");
    }

    @Test
    public void testHostnameFormat() {
        String hostname = SystemUtils.getLocalHostname();
        // Typical hostname format
        assertNotNull(hostname);
        assertFalse(hostname.isEmpty());
        // Should not start or end with a dot
        assertFalse(hostname.startsWith("."));
        assertFalse(hostname.endsWith("."));
        // Should not start or end with a hyphen
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
        // Ensure cache is empty
        SystemUtils.clearHostnameCache();

        // Clearing again should not throw an exception
        assertDoesNotThrow(() -> SystemUtils.clearHostnameCache());
    }
}