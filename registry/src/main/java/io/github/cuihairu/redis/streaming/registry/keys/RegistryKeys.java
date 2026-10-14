package io.github.cuihairu.redis.streaming.registry.keys;

import java.util.regex.Pattern;

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

    /** Default tenant: no tenant key segment, keys are byte-identical to the legacy layout. */
    public static final String DEFAULT_TENANT = "default";

    /** Tenant names: 1..64 chars of {@code [A-Za-z0-9._-]} (same contract as {@code StreamKeys}). */
    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

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

    /** Normalized tenant of this view. */
    private final String tenant;

    /** {@code keyPrefix} plus the tenant segment; what the key templates are formatted with. */
    private final String effectivePrefix;

    public RegistryKeys() {
        this(DEFAULT_PREFIX);
    }

    public RegistryKeys(String keyPrefix) {
        this(keyPrefix, DEFAULT_TENANT);
    }

    /**
     * Build a tenant-scoped view: a non-default tenant inserts {@code :tenant}
     * right after the prefix ({@code registry:acme:services:...}).
     */
    public RegistryKeys(String keyPrefix, String tenant) {
        this.keyPrefix = keyPrefix != null && !keyPrefix.trim().isEmpty() ?
            keyPrefix.trim() : DEFAULT_PREFIX;
        this.tenant = normalizeTenant(tenant);
        this.effectivePrefix = keyPrefix + tenantSegment();
    }

    /**
     * Normalize a tenant name: null/blank/{@link #DEFAULT_TENANT} collapse to
     * {@link #DEFAULT_TENANT} (no key segment); anything else must be 1..64 chars of
     * {@code [A-Za-z0-9._-]} so the derived keys stay unambiguous (no colon).
     */
    public static String normalizeTenant(String tenant) {
        if (tenant == null || tenant.isBlank() || DEFAULT_TENANT.equals(tenant)) {
            return DEFAULT_TENANT;
        }
        if (!TENANT_PATTERN.matcher(tenant).matches()) {
            throw new IllegalArgumentException(
                    "tenant must match [A-Za-z0-9._-]{1,64} (no colon), got: " + tenant);
        }
        return tenant;
    }

    /** @return {@code ""} for the default tenant, else {@code ":" + tenant}. */
    public String tenantSegment() {
        return DEFAULT_TENANT.equals(tenant) ? "" : ":" + tenant;
    }

    /** A view with this view's prefix and the given tenant (normalized). */
    public RegistryKeys withTenant(String tenant) {
        return new RegistryKeys(keyPrefix, tenant);
    }

    // ==================== Service registration related keys ====================

    /**
     * Get the service index key (Set)
     * Stores all registered service names
     */
    public String getServicesIndexKey() {
        return String.format(SERVICES_INDEX_TEMPLATE, effectivePrefix);
    }

    /**
     * Get the service heartbeat index key (ZSet)
     * score=heartbeat timestamp, member=instanceId
     */
    public String getServiceHeartbeatsKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_HEARTBEATS_TEMPLATE, effectivePrefix, serviceName);
    }

    /**
     * Get the service instance details key (Hash)
     * Stores the complete information of the instance, including redundant heartbeat time
     */
    public String getServiceInstanceKey(String serviceName, String instanceId) {
        validateServiceName(serviceName);
        validateInstanceId(instanceId);
        return String.format(SERVICE_INSTANCE_TEMPLATE, effectivePrefix, serviceName, instanceId);
    }

    /**
     * Get the service change notification channel key
     */
    public String getServiceChangeChannelKey(String serviceName) {
        validateServiceName(serviceName);
        return String.format(SERVICE_CHANGE_CHANNEL_TEMPLATE, effectivePrefix, serviceName);
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
     * Check if the key belongs to the current registry (this view's tenant)
     */
    public boolean isRegistryKey(String key) {
        return tailPartsAfterPrefix(key) != null;
    }

    /**
     * Extract the service name from a full instance key
     */
    public String extractServiceNameFromInstanceKey(String instanceKey) {
        // Key format: prefix[:tenant]:services:serviceName:instance:instanceId
        String[] parts = tailPartsAfterPrefix(instanceKey);
        if (parts != null && parts.length >= 4
                && "services".equals(parts[0]) && "instance".equals(parts[2])) {
            return parts[1];
        }
        return null;
    }

    /**
     * Extract the instance ID from a full instance key
     */
    public String extractInstanceIdFromInstanceKey(String instanceKey) {
        // Key format: prefix[:tenant]:services:serviceName:instance:instanceId
        String[] parts = tailPartsAfterPrefix(instanceKey);
        if (parts != null && parts.length >= 4
                && "services".equals(parts[0]) && "instance".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }

    /**
     * Extract the service name from a heartbeat key
     */
    public String extractServiceNameFromHeartbeatsKey(String heartbeatsKey) {
        // Key format: prefix[:tenant]:services:serviceName:heartbeats
        String[] parts = tailPartsAfterPrefix(heartbeatsKey);
        if (parts != null && parts.length >= 3
                && "services".equals(parts[0]) && "heartbeats".equals(parts[2])) {
            return parts[1];
        }
        return null;
    }

    /**
     * The key with this view's prefix stripped and split on ':'; null when the
     * key does not belong to this view (other prefix or other tenant).
     */
    private String[] tailPartsAfterPrefix(String key) {
        String prefix = effectivePrefix + ":";
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        return key.substring(prefix.length()).split(":");
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
                ", tenant='" + tenant + '\'' +
                '}';
    }
}