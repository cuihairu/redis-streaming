package io.github.cuihairu.redis.streaming.registry.keys;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册中心统一Key管理
 * 只管理服务注册发现相关的Redis Key，配置中心的Key已迁移到config模块
 */
@Getter
public class RegistryKeys {

    private static final Logger logger = LoggerFactory.getLogger(RegistryKeys.class);

    // 默认前缀
    private static final String DEFAULT_PREFIX = "registry";

    // 服务注册发现Key模板
    private static final String SERVICES_INDEX_TEMPLATE = "%s:services";                           // 一级：服务索引
    private static final String SERVICE_HEARTBEATS_TEMPLATE = "%s:services:%s:heartbeats";         // 二级：心跳索引(ZSet)
    private static final String SERVICE_INSTANCE_TEMPLATE = "%s:services:%s:instance:%s";          // 三级：实例详情(Hash)
    private static final String SERVICE_CHANGE_CHANNEL_TEMPLATE = "%s:services:%s:changes";        // 服务变更通知频道

    /**
     * -- GETTER --
     *  获取前缀
     */
    private final String keyPrefix;

    public RegistryKeys() {
        this(DEFAULT_PREFIX);
    }

    public RegistryKeys(String keyPrefix) {
        this.keyPrefix = keyPrefix != null && !keyPrefix.trim().isEmpty() ?
            keyPrefix.trim() : DEFAULT_PREFIX;
    }

    // ==================== 服务注册相关Keys ====================

    /**
     * 获取服务索引Key (Set)
     * 存储所有注册的服务名称
     */
    public String getServicesIndexKey() {
        return String.format(SERVICES_INDEX_TEMPLATE, keyPrefix);
    }

    /**
     * 获取服务心跳索引Key (ZSet)
     * score=心跳时间戳, member=instanceId
     */
    public String getServiceHeartbeatsKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_HEARTBEATS_TEMPLATE, keyPrefix, serviceName);
    }

    /**
     * 获取服务实例详情Key (Hash)
     * 存储实例的完整信息，包括冗余的心跳时间
     */
    public String getServiceInstanceKey(String serviceName, String instanceId) {
        validateServiceName(serviceName);
        validateInstanceId(instanceId);
        return String.format(SERVICE_INSTANCE_TEMPLATE, keyPrefix, serviceName, instanceId);
    }

    /**
     * 获取服务变更通知频道Key
     */
    public String getServiceChangeChannelKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_CHANGE_CHANNEL_TEMPLATE, keyPrefix, serviceName);
    }

    // ==================== 实例ID安全处理方法 ====================

    /**
     * 清理服务名中的不安全字符
     * 将冒号和其他可能导致解析问题的字符替换为安全字符
     */
    public static String sanitizeServiceName(String serviceName) {
        if (serviceName == null) {
            return null;
        }

        // 替换冒号为下划线，保持可读性
        // 空白字符替换为连字符
        return serviceName
                .replace(":", "_")          // 冒号替换为下划线
                .replace(" ", "-")          // 空格替换为连字符
                .replace("\t", "-")         // 制表符替换为连字符
                .replace("\n", "-")         // 换行符替换为连字符
                .replace("\r", "-");
    }

    /**
     * 验证并清理服务名
     * 如果包含不安全字符，会打印警告并返回清理后的名称
     */
    public static String validateAndSanitizeServiceName(String serviceName) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name cannot be null or empty");
        }
        String sanitized = sanitizeServiceName(serviceName);
        if (!serviceName.equals(sanitized)) {
            logger.warn("Service name '{}' contains unsafe characters and was sanitized to '{}'. " +
                    "Original characters that cause issues: colon(:), space, tab, newline",
                    serviceName, sanitized);
        }

        return sanitized;
    }

    /**
     * 检查服务名是否安全（不包含可能导致解析问题的字符）
     */
    public static boolean isServiceNameSafe(String serviceName) {
        if (serviceName == null) {
            return false;
        }

        return !serviceName.contains(":") &&
               !serviceName.contains(" ") &&
               !serviceName.contains("\t") &&
               !serviceName.contains("\n") &&
               !serviceName.contains("\r");
    }

    /**
     * 清理实例ID中的不安全字符
     * 将冒号和其他可能导致解析问题的字符替换为安全字符
     */
    public static String sanitizeInstanceId(String instanceId) {
        if (instanceId == null) {
            return null;
        }

        // 替换冒号为下划线，保持可读性
        // 回车符替换为连字符
        return instanceId
                .replace(":", "_")          // 冒号替换为下划线
                .replace(" ", "-")          // 空格替换为连字符
                .replace("\t", "-")         // 制表符替换为连字符
                .replace("\n", "-")         // 换行符替换为连字符
                .replace("\r", "-");
    }

    /**
     * 验证并清理实例ID
     * 如果包含不安全字符，会打印警告并返回清理后的ID
     */
    public static String validateAndSanitizeInstanceId(String instanceId) {
        if (instanceId == null || instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance ID cannot be null or empty");
        }
        String sanitized = sanitizeInstanceId(instanceId);
        if (!instanceId.equals(sanitized)) {
            logger.warn("Instance ID '{}' contains unsafe characters and was sanitized to '{}'. " +
                    "Original characters that cause issues: colon(:), space, tab, newline",
                    instanceId, sanitized);
        }

        return sanitized;
    }

    /**
     * 检查实例ID是否安全（不包含可能导致解析问题的字符）
     */
    public static boolean isInstanceIdSafe(String instanceId) {
        if (instanceId == null) {
            return false;
        }

        return !instanceId.contains(":") &&
               !instanceId.contains(" ") &&
               !instanceId.contains("\t") &&
               !instanceId.contains("\n") &&
               !instanceId.contains("\r");
    }

    /**
     * 检查Key是否属于当前注册中心
     */
    public boolean isRegistryKey(String key) {
        return key != null && key.startsWith(keyPrefix + ":");
    }

    /**
     * 从完整的实例Key中提取服务名
     */
    public String extractServiceNameFromInstanceKey(String instanceKey) {
        if (!isRegistryKey(instanceKey)) {
            return null;
        }

        // Key格式: prefix:services:serviceName:instance:instanceId
        String[] parts = instanceKey.split(":");
        if (parts.length >= 4 && "services".equals(parts[1]) && "instance".equals(parts[3])) {
            return parts[2];
        }
        return null;
    }

    /**
     * 从完整的实例Key中提取实例ID
     */
    public String extractInstanceIdFromInstanceKey(String instanceKey) {
        if (!isRegistryKey(instanceKey)) {
            return null;
        }

        // Key格式: prefix:services:serviceName:instance:instanceId
        String[] parts = instanceKey.split(":");
        if (parts.length >= 5 && "services".equals(parts[1]) && "instance".equals(parts[3])) {
            return parts[4];
        }
        return null;
    }

    /**
     * 从心跳Key中提取服务名
     */
    public String extractServiceNameFromHeartbeatsKey(String heartbeatsKey) {
        if (!isRegistryKey(heartbeatsKey)) {
            return null;
        }

        // Key格式: prefix:services:serviceName:heartbeats
        String[] parts = heartbeatsKey.split(":");
        if (parts.length >= 4 && "services".equals(parts[1]) && "heartbeats".equals(parts[3])) {
            return parts[2];
        }
        return null;
    }

    // ==================== 验证方法 ====================

    private void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name cannot be null or empty");
        }
        if (serviceName.contains(":")) {
            throw new IllegalArgumentException("Service name cannot contain ':' character");
        }
    }

    private void validateInstanceId(String instanceId) {
        if (instanceId == null || instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance ID cannot be null or empty");
        }
        if (instanceId.contains(":")) {
            logger.warn("Instance ID '{}' contains colon character which may cause key parsing issues. " +
                    "Consider using sanitizeInstanceId() to replace colons with safe characters.", instanceId);
            throw new IllegalArgumentException("Instance ID cannot contain ':' character. Use sanitizeInstanceId() to clean it first.");
        }
    }

    // ==================== 调试和监控方法 ====================

    /**
     * 获取所有Key模板（用于调试）
     */
    public String[] getAllKeyTemplates() {
        return new String[]{
            SERVICES_INDEX_TEMPLATE,
            SERVICE_HEARTBEATS_TEMPLATE,
            SERVICE_INSTANCE_TEMPLATE,
            SERVICE_CHANGE_CHANNEL_TEMPLATE
        };
    }

    @Override
    public String toString() {
        return "RegistryKeys{" +
                "keyPrefix='" + keyPrefix + '\'' +
                '}';
    }
}