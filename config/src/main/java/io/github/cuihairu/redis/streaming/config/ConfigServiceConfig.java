package io.github.cuihairu.redis.streaming.config;

/**
 * Configuration service Redis configuration
 * Contains Redis key patterns and settings specific to the configuration service
 */
public class ConfigServiceConfig extends BaseRedisConfig {
    /**
     * Max history size to retain per config (default 10)
     */
    private int historySize = 10;

    /**
     * Interval for the reconciliation poll that re-reads subscribed configs from Redis
     * and re-delivers changes whose pub/sub notification never reached this JVM
     * (connection blip during publish — B-06). 0 disables the poll; the default bounds
     * the staleness of a missed notification to half a minute instead of forever.
     */
    private long resyncIntervalMs = 30_000;

    /**
     * At-rest content encryption for sensitive configs (Security-Hardening-Design 方案 C).
     * {@code null} (default) leaves all content in plaintext. When set, the config hash
     * and history records of any dataId starting with {@link #securePrefix} are stored
     * encrypted and decrypted transparently on read; non-matching dataIds are unaffected.
     */
    private ContentCipher contentCipher;

    /**
     * DataId prefix that marks a config as at-rest-encrypted when a {@link #contentCipher}
     * is installed. Default {@code secure.} — match it verbatim, it is a plain prefix.
     */
    private String securePrefix = "secure.";

    public ConfigServiceConfig() {
        super();
    }

    public ConfigServiceConfig(String keyPrefix) {
        super(keyPrefix);
    }

    public ConfigServiceConfig(String keyPrefix, boolean enableKeyPrefix) {
        super(keyPrefix, enableKeyPrefix);
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = Math.max(0, historySize);
    }

    public long getResyncIntervalMs() {
        return resyncIntervalMs;
    }

    public void setResyncIntervalMs(long resyncIntervalMs) {
        this.resyncIntervalMs = Math.max(0, resyncIntervalMs);
    }

    /** @return the cipher protecting matching configs at rest, or {@code null} for plaintext */
    public ContentCipher getContentCipher() {
        return contentCipher;
    }

    public void setContentCipher(ContentCipher contentCipher) {
        this.contentCipher = contentCipher;
    }

    /** @return the dataId prefix marking a config as at-rest-encrypted */
    public String getSecurePrefix() {
        return securePrefix;
    }

    public void setSecurePrefix(String securePrefix) {
        this.securePrefix = securePrefix;
    }

    /**
     * Get configuration key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration key
     */
    public String getConfigKey(String group, String dataId) {
        return formatKey("config:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration subscribers list key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration subscribers list key
     */
    public String getConfigSubscribersKey(String group, String dataId) {
        return formatKey("config_subscribers:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration history key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration history key
     */
    public String getConfigHistoryKey(String group, String dataId) {
        return formatKey("config_history:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration change channel key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration change channel key
     */
    public String getConfigChangeChannelKey(String group, String dataId) {
        return formatKey("config_change:%s:%s", sanitize(group), sanitize(dataId));
    }

    private String sanitize(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Replace Redis-unfriendly separators to avoid key parsing ambiguity
        t = t.replace(':', '_');
        return t;
    }
}
