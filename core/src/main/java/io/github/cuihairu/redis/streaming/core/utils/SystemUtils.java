package io.github.cuihairu.redis.streaming.core.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 系统工具类
 * 提供系统相关信息的获取和缓存功能
 */
public class SystemUtils {
    private static volatile String cachedHostname = null;
    private static final Object lock = new Object();
    
    /**
     * 获取本地主机名
     * 带有缓存机制，避免重复调用系统API
     * 
     * @return 本地主机名
     * @throws RuntimeException 如果无法获取主机名
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
     * 清除主机名缓存
     * 主要用于测试场景
     */
    public static void clearHostnameCache() {
        synchronized (lock) {
            cachedHostname = null;
        }
    }
}