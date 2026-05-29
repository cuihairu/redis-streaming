package io.github.cuihairu.redis.streaming.core.utils;

/**
 * Instance ID generator
 * Provides functionality to generate unique instance IDs based on hostname and port
 */
public class InstanceIdGenerator {
    
    /**
     * Generate instance ID based on service name, hostname, and port
     *
     * @param serviceName service name
     * @param host host address
     * @param port port number
     * @return instance ID in the format "serviceName-host:port"
     */
    public static String generateInstanceId(String serviceName, String host, int port) {
        return serviceName + "-" + host + ":" + port;
    }
    
    /**
     * Generate instance ID based on service name and port (using local hostname)
     *
     * @param serviceName service name
     * @param port port number
     * @return instance ID in the format "serviceName-hostname:port"
     */
    public static String generateInstanceId(String serviceName, int port) {
        String hostname = SystemUtils.getLocalHostname();
        return serviceName + "-" + hostname + ":" + port;
    }
    
    /**
     * Generate instance ID based on local hostname
     *
     * @param serviceName service name
     * @param port port number
     * @return instance ID in the format "hostname:port"
     */
    public static String generateLocalInstanceId(String serviceName, int port) {
        String hostname = SystemUtils.getLocalHostname();
        return hostname + ":" + port;
    }
}