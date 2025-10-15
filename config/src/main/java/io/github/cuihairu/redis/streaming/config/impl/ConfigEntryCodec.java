package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized codec for Config Hash entries (content/version/description/timestamps).
 * Keeps all conversions in one place to avoid ad-hoc map handling across code.
 */
public final class ConfigEntryCodec {
    private ConfigEntryCodec() {}

    /** Build a mutable map for config update. */
    public static Map<String, String> toMap(String content, String version, String description,
                                            long updateTimeMs, Long createTimeMsIfNew) {
        Map<String, String> m = new HashMap<>();
        if (content != null) m.put("content", content);
        if (version != null) m.put("version", version);
        if (description != null && !description.isEmpty()) m.put("description", description);
        m.put("updateTime", String.valueOf(updateTimeMs));
        if (createTimeMsIfNew != null) m.put("createTime", String.valueOf(createTimeMsIfNew));
        return m;
    }

    /** Parse a config hash map into ConfigInfo (best-effort). */
    public static ConfigInfo parseInfo(String dataId, String group, Map<String, String> map) {
        if (map == null) return null;
        String content = map.get("content");
        String version = map.get("version");
        String description = map.get("description");
        LocalDateTime updateTime = toLdt(map.get("updateTime"));
        LocalDateTime createTime = toLdt(map.get("createTime"));
        return ConfigInfo.builder()
                .dataId(dataId)
                .group(group)
                .content(content)
                .version(version)
                .description(description)
                .updateTime(updateTime)
                .createTime(createTime)
                .build();
    }

    private static LocalDateTime toLdt(String ms) {
        try {
            if (ms == null) return null;
            long v = Long.parseLong(ms);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneId.systemDefault());
        } catch (Exception e) { return null; }
    }
}
