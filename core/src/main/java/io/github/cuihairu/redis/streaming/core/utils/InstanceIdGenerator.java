package io.github.cuihairu.redis.streaming.core.utils;

/**
 * 实例ID生成器
 * 提供基于主机名和端口生成唯一实例ID的功能
 */
public class InstanceIdGenerator {
    
    /**
     * 根据服务名、主机名和端口生成实例ID
     * 
     * @param serviceName 服务名称
     * @param host 主机地址
     * @param port 端口号
     * @return 实例ID，格式为 "serviceName-host:port"
     */
    public static String generateInstanceId(String serviceName, String host, int port) {
        return serviceName + "-" + host + ":" + port;
    }
    
    /**
     * 根据服务名和端口生成实例ID（使用本地主机名）
     * 
     * @param serviceName 服务名称
     * @param port 端口号
     * @return 实例ID，格式为 "serviceName-hostname:port"
     */
    public static String generateInstanceId(String serviceName, int port) {
        String hostname = SystemUtils.getLocalHostname();
        return serviceName + "-" + hostname + ":" + port;
    }
    
    /**
     * 生成基于本地主机名的实例ID
     * 
     * @param serviceName 服务名称
     * @param port 端口号
     * @return 实例ID，格式为 "hostname:port"
     */
    public static String generateLocalInstanceId(String serviceName, int port) {
        String hostname = SystemUtils.getLocalHostname();
        return hostname + ":" + port;
    }
}