package io.github.cuihairu.redis.streaming.core.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * System utility class
 * Provides system-related information retrieval and caching functionality
 */
public class SystemUtils {
    private static volatile String cachedHostname = null;
    private static final Object lock = new Object();
    
    /**
     * Get local hostname
     * With caching mechanism to avoid repeated system API calls
     *
     * @return local hostname
     * @throws RuntimeException if hostname cannot be obtained
     */
    public static String getLocalHostname() {
        if (cachedHostname != null) {
            return cachedHostname;
        }
        
        synchronized (lock) {
            if (cachedHostname != null) {
                return cachedHostname;
            }
            
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                cachedHostname = localHost.getHostName();
                return cachedHostname;
            } catch (UnknownHostException e) {
                throw new RuntimeException("Failed to get local hostname", e);
            }
        }
    }
    
    /**
     * Clear hostname cache
     * Primarily used for testing scenarios
     */
    public static void clearHostnameCache() {
        synchronized (lock) {
            cachedHostname = null;
        }
    }
}