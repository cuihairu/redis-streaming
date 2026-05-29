package io.github.cuihairu.redis.streaming.registry.keys;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified key management for the registry
 * Only manages Redis keys related to service registration and discovery; config center keys have been migrated to the config module
 */
@Getter
public class RegistryKeys {

    private static final Logger logger = LoggerFactory.getLogger(RegistryKeys.class);

    // Default prefix
    private static final String DEFAULT_PREFIX = "registry";

    // Service registration and discovery key templates
    private static final String SERVICES_INDEX_TEMPLATE = "%s:services";                           // Level 1: Service index
    private static final String SERVICE_HEARTBEATS_TEMPLATE = "%s:services:%s:heartbeats";         // Level 2: Heartbeat index (ZSet)
    private static final String SERVICE_INSTANCE_TEMPLATE = "%s:services:%s:instance:%s";          // Level 3: Instance details (Hash)
    private static final String SERVICE_CHANGE_CHANNEL_TEMPLATE = "%s:services:%s:changes";        // Service change notification channel

    /**
     * -- GETTER --
     *  Get the prefix
     */
    private final String keyPrefix;

    public RegistryKeys() {
        this(DEFAULT_PREFIX);
    }

    public RegistryKeys(String keyPrefix) {
        this.keyPrefix = keyPrefix != null && !keyPrefix.trim().isEmpty() ?
            keyPrefix.trim() : DEFAULT_PREFIX;
    }

    // ==================== Service registration related keys ====================

    /**
     * Get the service index key (Set)
     * Stores all registered service names
     */
    public String getServicesIndexKey() {
        return String.format(SERVICES_INDEX_TEMPLATE, keyPrefix);
    }

    /**
     * Get the service heartbeat index key (ZSet)
     * score=heartbeat timestamp, member=instanceId
     */
    public String getServiceHeartbeatsKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_HEARTBEATS_TEMPLATE, keyPrefix, serviceName);
    }

    /**
     * Get the service instance details key (Hash)
     * Stores the complete information of the instance, including redundant heartbeat time
     */
    public String getServiceInstanceKey(String serviceName, String instanceId) {
        validateServiceName(serviceName);
        validateInstanceId(instanceId);
        return String.format(SERVICE_INSTANCE_TEMPLATE, keyPrefix, serviceName, instanceId);
    }

    /**
     * Get the service change notification channel key
     */
    public String getServiceChangeChannelKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_CHANGE_CHANNEL_TEMPLATE, keyPrefix, serviceName);
    }

    // ==================== Instance ID safety handling methods ====================

    /**
     * Sanitize unsafe characters in the service name
     * Replaces colons and other characters that may cause parsing issues with safe characters
     */
    public static String sanitizeServiceName(String serviceName) {
        if (serviceName == null) {
            return null;
        }

        // Replace colons with underscores for readability
        // Replace whitespace characters with hyphens
        return serviceName
                .replace(":", "_")          // Replace colon with underscore
                .replace(" ", "-")          // Replace space with hyphen
                .replace("\t", "-")         // Replace tab with hyphen
                .replace("\n", "-")         // Replace newline with hyphen
                .replace("\r", "-");
    }

    /**
     * Validate and sanitize the service name
     * If it contains unsafe characters, prints a warning and returns the sanitized name
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
     * Check if the service name is safe (does not contain characters that may cause parsing issues)
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
     * Sanitize unsafe characters in the instance ID
     * Replaces colons and other characters that may cause parsing issues with safe characters
     */
    public static String sanitizeInstanceId(String instanceId) {
        if (instanceId == null) {
            return null;
        }

        // Replace colons with underscores for readability
        // Replace carriage return characters with hyphens
        return instanceId
                .replace(":", "_")          // Replace colon with underscore
                .replace(" ", "-")          // Replace space with hyphen
                .replace("\t", "-")         // Replace tab with hyphen
                .replace("\n", "-")         // Replace newline with hyphen
                .replace("\r", "-");
    }

    /**
     * Validate and sanitize the instance ID
     * If it contains unsafe characters, prints a warning and returns the sanitized ID
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
     * Check if the instance ID is safe (does not contain characters that may cause parsing issues)
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
     * Check if the key belongs to the current registry
     */
    public boolean isRegistryKey(String key) {
        return key != null && key.startsWith(keyPrefix + ":");
    }

    /**
     * Extract the service name from a full instance key
     */
    public String extractServiceNameFromInstanceKey(String instanceKey) {
        if (!isRegistryKey(instanceKey)) {
            return null;
        }

        // Key format: prefix:services:serviceName:instance:instanceId
        String[] parts = instanceKey.split(":");
        if (parts.length >= 4 && "services".equals(parts[1]) && "instance".equals(parts[3])) {
            return parts[2];
        }
        return null;
    }

    /**
     * Extract the instance ID from a full instance key
     */
    public String extractInstanceIdFromInstanceKey(String instanceKey) {
        if (!isRegistryKey(instanceKey)) {
            return null;
        }

        // Key format: prefix:services:serviceName:instance:instanceId
        String[] parts = instanceKey.split(":");
        if (parts.length >= 5 && "services".equals(parts[1]) && "instance".equals(parts[3])) {
            return parts[4];
        }
        return null;
    }

    /**
     * Extract the service name from a heartbeat key
     */
    public String extractServiceNameFromHeartbeatsKey(String heartbeatsKey) {
        if (!isRegistryKey(heartbeatsKey)) {
            return null;
        }

        // Key format: prefix:services:serviceName:heartbeats
        String[] parts = heartbeatsKey.split(":");
        if (parts.length >= 4 && "services".equals(parts[1]) && "heartbeats".equals(parts[3])) {
            return parts[2];
        }
        return null;
    }

    // ==================== Validation methods ====================

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

    // ==================== Debug and monitoring methods ====================

    /**
     * Get all key templates (for debugging)
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